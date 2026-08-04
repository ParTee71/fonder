package se.partee71.fonder.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.data.repository.isPriceStale
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.usecase.FundMetadataFreshness
import se.partee71.fonder.domain.usecase.PortfolioCalc
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import java.time.LocalDate

/**
 * Uppdaterar kurser för alla fonder användaren bevakar (se issue #3), handelsdagsmedvetet sedan
 * issue #27/TP-17 — koalescerad av [se.partee71.fonder.worker.FundPriceRefreshScheduler] mellan
 * appstart (launch-gate), en gles periodisk backstop och en manuell "Uppdatera nu"-knapp.
 *
 * Fyller sedan issue #61 även på den persisterade billigare-alternativ-jämförelsen (ANA-9/HEM-6)
 * inkrementellt, [KEY_SCAN_COMPARISONS] satt — se [scanComparisons]. Ingen ny worker eller
 * schemaläggare: den här itererar redan alla bevakade fonder på ett schema som redan är
 * koalescerat, så jämförelsen rider med i stället för att duplicera den mekaniken.
 *
 * Räknar sedan issue #70 även fram bytesplanen (HEM-8) och spelar in dess facit,
 * [KEY_SCAN_SWITCH_PLAN] satt — se [scanSwitchPlan]. Samma princip: rider med på det redan
 * koalescerade schemat i stället för en egen mekanism.
 */
@HiltWorker
class FundPriceUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val preferencesRepository: PreferencesRepository,
    private val suggestionRecordRepository: SuggestionRecordRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val success = refreshAll(transactionRepository, fundPriceRepository, force)
        if (success) preferencesRepository.setLastPriceSyncEpochMillis(System.currentTimeMillis())

        runScans(
            refreshSucceeded = success,
            scanComparisons = inputData.getBoolean(KEY_SCAN_COMPARISONS, false),
            scanSwitchPlan = inputData.getBoolean(KEY_SCAN_SWITCH_PLAN, false),
            transactionRepository = transactionRepository,
            fundPriceRepository = fundPriceRepository,
            fundMetadataRepository = fundMetadataRepository,
            preferencesRepository = preferencesRepository,
            suggestionRecordRepository = suggestionRecordRepository,
        )

        return if (success) Result.success() else Result.retry()
    }

    companion object {
        /** Input-data-nyckel för att forcera en uppdatering, bypassar staleness-gaten (manuell knapp, SET-2). */
        const val KEY_FORCE = "force"

        /**
         * Input-data-nyckel som slår på [scanComparisons] — satt **bara** av
         * [FundPriceRefreshScheduler.scheduleBackstop] (var 12:e timme), aldrig av launch-gaten
         * eller den manuella "Uppdatera nu"-knappen. En jämförelseskanning kan kosta upp till
         * ~50 hämtningar per innehav (ISIN-uppslag + kandidatfråga + köpbarhetsverifiering) —
         * att lägga den på launch-gaten hade gjort appstart dyr, exakt det HEM-5 (#60) redan
         * vägrade göra.
         */
        const val KEY_SCAN_COMPARISONS = "scan_comparisons"

        /**
         * Input-data-nyckel som slår på [scanSwitchPlan] (HEM-8, issue #70) — satt av
         * [FundPriceRefreshScheduler.scheduleBackstop] och, sedan issue #88, av
         * [FundPriceRefreshScheduler.triggerSwitchPlanScan] när planens indata just ändrats
         * (riskprofil SET-3, kontotyp SET-4) eller användaren bett om en omräkning.
         *
         * Fortfarande **aldrig** av launch-gaten eller den manuella "Uppdatera nu"-knappen, av
         * samma skäl som [KEY_SCAN_COMPARISONS]: en plan kräver en källfråga plus budgeterad
         * köpbarhetsverifiering per underviktad nivå, och båda de triggarna löper ofta. De två
         * vägar som finns är i stället glesa (var 12:e timme) respektive uttryckligen begärda.
         */
        const val KEY_SCAN_SWITCH_PLAN = "scan_switch_plan"

        /**
         * Högst så här många innehav jämförs per körning — inkrementell ifyllnad i stället för
         * en engångsskanning, så en normalstor portfölj är genomsökt inom några dygns
         * bakgrundskörningar utan en dyr engångs-burst. Störst innehavsvärde (mest potential)
         * prioriteras, se [scanComparisons].
         */
        private const val MAX_COMPARISONS_PER_RUN = 2

        /**
         * Högst så här många köpkandidat-NAV fylls på per körning för facit (SET-5, issue #80),
         * nyast förslag först — se [scanOutcomeNavs]. Samma inkrementella princip som
         * [MAX_COMPARISONS_PER_RUN], men lite generösare: varje ISIN kostar ett uppslag plus en
         * kort kursuppdatering, inte en hel kandidatsökning med köpbarhetsverifiering.
         */
        private const val MAX_OUTCOME_NAVS_PER_RUN = 4

        /**
         * Högst så här många avgiftsbyten spelas in i facit per körning (ANA-9/SET-5, issue
         * #93), störst innehavsvärde först. Varje inspelning kostar ett NAV-uppslag för
         * kandidaten, precis som [MAX_OUTCOME_NAVS_PER_RUN] — men ett innehav kan visa upp till
         * tre alternativ, så budgeten räknas i **rader**, inte i innehav. Redan inspelade rader
         * (dedup per dygn och sort) drar ingen budget: då hinner de innehav som ligger längre
         * ner i värdeordningen med i samma körning i stället för att stängas ute av dem som
         * redan är klara.
         */
        private const val MAX_FEE_RECORDINGS_PER_RUN = 6

        /**
         * Hur långt bak [resolveBuyNav] hämtar historik för en köpkandidat. Bara den senaste
         * kursen behövs — fönstret ska bara vara långt nog att överleva helger och röda dagar,
         * inte backfilla en historik appen ändå aldrig haft för fonden.
         */
        private const val BUY_NAV_LOOKBACK_DAYS = 30L

        /**
         * Historikhorisont för en ISIN-fond **utan** köphistorik (bevakad men aldrig köpt) — se
         * grenvalet i [refreshAll]. Samma princip som [BUY_NAV_LOOKBACK_DAYS]: bara den senaste
         * kursen behövs, fönstret ska överleva helger och röda dagar, inte backfilla en historik
         * appen ändå aldrig haft.
         */
        private const val ISIN_FALLBACK_LOOKBACK_DAYS = 30L

        /**
         * Kör de begärda skanningarna — men **bara** efter en lyckad kursuppdatering.
         *
         * Misslyckades [refreshSucceeded] läser skanningarna ur en cache workern precis bevisat
         * att den inte kunde uppdatera, och [scanSwitchPlan] skriver då en `SuggestionRecord`
         * vars NAV-utgångsläge är flera dagar gammalt — ett korrumperat facit som inte går att
         * rätta i efterhand, och hela poängen med tabellen är att mäta utfallet mot NAV *vid
         * förslagstillfället* (HEM-8). Körningen returnerar dessutom `Result.retry()` i det läget,
         * så hela skanningen inklusive dess källfrågor skulle köras om vid varje backoff-försök
         * (issue #75). Ren logik utan `CoroutineWorker`-beroende, av samma skäl som [refreshAll].
         */
        internal suspend fun runScans(
            refreshSucceeded: Boolean,
            scanComparisons: Boolean,
            scanSwitchPlan: Boolean,
            transactionRepository: TransactionRepository,
            fundPriceRepository: FundPriceRepository,
            fundMetadataRepository: FundMetadataRepository,
            preferencesRepository: PreferencesRepository,
            suggestionRecordRepository: SuggestionRecordRepository,
        ) {
            if (!refreshSucceeded) return
            if (scanComparisons) {
                scanComparisons(transactionRepository, fundPriceRepository, fundMetadataRepository, suggestionRecordRepository)
            }
            if (scanSwitchPlan) {
                scanSwitchPlan(transactionRepository, fundPriceRepository, fundMetadataRepository, preferencesRepository, suggestionRecordRepository)
                scanOutcomeNavs(transactionRepository, fundPriceRepository, suggestionRecordRepository)
            }
        }

        /**
         * Håller kurscachen färsk för de **köpkandidater** facit (SET-5, issue #80) ska
         * utvärderas mot — fonder appen aldrig ägt och som därför inte nås av [refreshAll].
         * Utan den här ifyllnaden hade facit-skärmen behövt hämta dem själv vid varje öppning,
         * och en historik på hundratals förslag gör den kostnaden orimlig (samma skäl som
         * lägger HEM-6/HEM-8 här i stället för på skärmen).
         *
         * Gated av [KEY_SCAN_SWITCH_PLAN], inte av en egen nyckel: det är samma HEM-8-familj,
         * samma budgetresonemang och samma backstop — en till input-nyckel hade bara gett två
         * flaggor som alltid sätts ihop.
         *
         * Säljsidan hoppas över: den är per definition en bevakad fond, vars kurs [refreshAll]
         * redan uppdaterat under fondens *egen* fundId. Att hämta den igen via ISIN-vägen hade
         * kostat ett nätverksanrop till och lagt en dubblettrad i cachen under en annan nyckel.
         *
         * Nyast först och högst [MAX_OUTCOME_NAVS_PER_RUN] per körning — samma inkrementella
         * princip som [scanComparisons]. En lång historik blir därför komplett över några dygns
         * bakgrundskörningar i stället för i en dyr engångs-burst.
         */
        internal suspend fun scanOutcomeNavs(
            transactionRepository: TransactionRepository,
            fundPriceRepository: FundPriceRepository,
            suggestionRecordRepository: SuggestionRecordRepository,
            today: LocalDate = LocalDate.now(),
        ) {
            val history = suggestionRecordRepository.observeHistory().first()
            if (history.isEmpty()) return

            val trackedIsins = transactionRepository.observeFunds().first().mapNotNull { it.isin }.toSet()
            val targets = history.map { it.buyIsin }
                .filterNot { it in trackedIsins }
                .distinct()
                .take(MAX_OUTCOME_NAVS_PER_RUN)

            targets.forEach { isin -> resolveBuyNav(isin, fundPriceRepository, today) }
        }

        /**
         * Ren logik utan `CoroutineWorker`-beroende, så den kan enhetstestas direkt (issue:
         * kodgranskning fann att den dagliga uppdateringen aldrig hämtade kurser för
         * ISIN-matchade fonder, se KRAVLISTA TP-14 — [FundPriceRepository.refresh] nycklas på
         * Handelsbankens FundId, som ISIN-matchade fonder saknar).
         *
         * Fonder med känt ISIN ([se.partee71.fonder.domain.model.Fund.isin] != null, t.ex.
         * fonder från andra fondbolag matchade via [FundPriceRepository.findFundByIsin])
         * uppdateras via [FundPriceRepository.refreshSince] i stället för [FundPriceRepository.refresh]
         * — annars nås de aldrig av den dagliga uppdateringen (samma gren som
         * `FondDetaljViewModel`/`ImportHoldingsViewModel` redan använder).
         *
         * @param force hoppar över [isPriceStale]-gaten och uppdaterar alla bevakade fonder,
         *   oavsett om cachen redan är aktuell (TP-17, den manuella "Uppdatera nu"-knappen, SET-2).
         *   Annars uppdateras bara fonder vars cachade kurs faktiskt är inaktuell — gör den
         *   periodiska backstopen billig (inget nätverksanrop när kursen redan är färsk).
         * @return true om det inte finns några bevakade fonder, om ingen fond var inaktuell, eller
         *   om minst en fonds uppdatering lyckades — false bara om samtliga (inaktuella) fonder
         *   misslyckades (troligen ett tillfälligt nätverksfel), så [CoroutineWorker.Result.retry]
         *   kan användas i stället för att vänta på nästa schemalagda körning.
         */
        internal suspend fun refreshAll(
            transactionRepository: TransactionRepository,
            fundPriceRepository: FundPriceRepository,
            force: Boolean = false,
        ): Boolean {
            val funds = transactionRepository.observeFunds().first()
            if (funds.isEmpty()) return true

            val targets = if (force) funds else funds.filter { fundPriceRepository.isPriceStale(it.fundId) }
            if (targets.isEmpty()) return true

            val earliestPurchaseByFund = transactionRepository.observeTransactions().first()
                .groupBy { it.fundId }
                .mapValues { (_, txs) -> LocalDate.ofEpochDay(txs.minOf { it.epochDay }) }

            var anySuccess = false
            targets.forEach { fund ->
                val isin = fund.isin
                // Utan köphistorik finns ingen historikhorisont att backfilla mot — en bevakad
                // men aldrig köpt fond behöver bara en färsk kurs, inte trettio år bakåt
                // (TP-18). Repositoryt hämtar då bara sitt korta fönster.
                val since = earliestPurchaseByFund[fund.fundId]
                // Villkoret är **bara** `isin != null`. Med `&& since != null` föll en fond vars
                // fundId *är* dess ISIN (findFundByIsin-vägen, TP-13/TP-14) men som saknar
                // transaktioner ner i else-grenen, där `refresh` frågar fondlista med ISIN:et som
                // fondnyckel utan ISIN-fallback — samma dödläge som issue #75 punkt 2 beskrev,
                // och fonden fick aldrig någon kurs. `refreshSince` degraderar korrekt utan
                // historikhorisont: `since` används bara i fondlista-grenen, som hoppas över för
                // en fond utan plattformskod.
                val success = if (isin != null) {
                    fundPriceRepository.refreshSince(fund.fundId, isin, since ?: LocalDate.now().minusDays(ISIN_FALLBACK_LOOKBACK_DAYS))
                } else {
                    fundPriceRepository.refresh(fund.fundId, since)
                }
                if (success) anySuccess = true
            }
            return anySuccess
        }

        /**
         * Fyller inkrementellt på den persisterade billigare-alternativ-jämförelsen (ANA-9/HEM-6,
         * issue #61) **och** spelar in de visade alternativen i facit (SET-5, issue #91/#93).
         * Innehav utan ISIN kan aldrig jämföras (samma princip som [PortfolioFeeCalc.compute]s
         * `unknownFeeCount`) och utelämnas. Ren logik utan `CoroutineWorker`-beroende, samma
         * mönster som [refreshAll].
         *
         * De två passen har **skilda urval**, och det är hela poängen (issue #93):
         *
         * - *Omskanningen* är dyr (källfråga + budgeterad köpbarhetsverifiering) och gäller
         *   därför bara innehav vars jämförelse hunnit bli inaktuell — högst
         *   [MAX_COMPARISONS_PER_RUN] per körning, störst värde först.
         * - *Inspelningen* gäller **alla** innehav med ett sparat jämförelseresultat, oavsett
         *   vem som räknade fram det. Fondkortet kör samma jämförelse vid varje skärmöppning och
         *   stämplar då `comparisonResolvedAtEpochDay`, så hängde inspelningen på omskanningens
         *   urval blev just de fonder användaren tittar på permanent överhoppade — kvitteringen
         *   dök aldrig upp för dem. Ett råd spelas in för att det **gavs**, inte för att det
         *   råkade räknas om här.
         *
         * [se.partee71.fonder.data.repository.FundMetadataRepository.suggestCheaperAlternatives]
         * gör själva jämförelsen (och skriver resultatet) och degraderar redan tyst vid nätverksfel
         * — inget här behöver fånga fel för att undvika en krascha-worker.
         */
        internal suspend fun scanComparisons(
            transactionRepository: TransactionRepository,
            fundPriceRepository: FundPriceRepository,
            fundMetadataRepository: FundMetadataRepository,
            suggestionRecordRepository: SuggestionRecordRepository,
            today: LocalDate = LocalDate.now(),
            /** Körnings-id som grupperar den här skanningens rader — se [SuggestionRecord.batchEpochMillis]. */
            batchEpochMillis: Long = System.currentTimeMillis(),
        ) {
            val funds = transactionRepository.observeFunds().first().filter { it.isin != null }
            if (funds.isEmpty()) return

            val holdings = PortfolioCalc.computeHoldings(funds, transactionRepository.observeTransactions().first())
            if (holdings.isEmpty()) return

            val prices = fundPriceRepository.observeLatestPrices(holdings.map { it.fund.fundId }).first()
            val withValue = PortfolioCalc.withCurrentValue(holdings, prices)
            val metadataByIsin = fundMetadataRepository.metadataFor(holdings.mapNotNull { it.fund.isin })

            val comparable = withValue
                .filter { it.fund.isin != null && it.currentValue != null }
                .sortedByDescending { it.currentValue }

            val rescanTargets = comparable
                .filter { holding ->
                    val resolvedAt = metadataByIsin[holding.fund.isin]?.comparisonResolvedAtEpochDay
                    resolvedAt == null || FundMetadataFreshness.isStale(resolvedAt, today, FundMetadataFreshness.COMPARISON_TTL_DAYS)
                }
                .take(MAX_COMPARISONS_PER_RUN)

            // Omskanningens färska svar går före den sparade listan: metadataByIsin lästes innan
            // den här körningen skrev något, och ska inte spela in gårdagens kandidater.
            val rescanned = rescanTargets.associate { holding ->
                val sellIsin = holding.fund.isin!!
                val alternatives = fundMetadataRepository.suggestCheaperAlternatives(sellIsin, holding.currentValue!!)
                sellIsin to alternatives?.map { it.candidate.isin }
            }

            recordFeeSwitches(
                comparable = comparable,
                shownIsinsFor = { sellIsin ->
                    // null i `rescanned` = jämförelsen kunde inte göras just nu (fonden saknar
                    // känd avgift eller källan svarade inte). Då spelas inget in för den — men
                    // den sparade listan får inte heller läsas, för den hör till ett resultat
                    // körningen just försökte ersätta.
                    if (sellIsin in rescanned) rescanned[sellIsin].orEmpty()
                    else metadataByIsin[sellIsin]?.shownAlternativeIsins.orEmpty()
                },
                prices = prices,
                fundPriceRepository = fundPriceRepository,
                suggestionRecordRepository = suggestionRecordRepository,
                today = today,
                batchEpochMillis = batchEpochMillis,
            )
        }

        /**
         * Spelar in avgiftsbytena (ANA-9) i facit (SET-5, issue #91) — samma tabell och samma
         * utgångsläge som bytesplanens rader, men med [SuggestionKind.FEE] så de två sorterna
         * kan mätas var för sig och Hems bytesplan aldrig ser dem.
         *
         * **Varje visat alternativ** får en egen rad (issue #93), inte bara det billigaste: de
         * tre alternativen är varandras alternativ, och vilket som helst kan vara det användaren
         * faktiskt byter till. Spelades bara det översta in gick de andra två aldrig att
         * kvittera, och facit kunde inte mäta ett byte som faktiskt gjordes.
         *
         * Skillnaden mot ett riskplansbyte är beloppet: ett avgiftsbyte avser **hela**
         * positionen (man byter andelsklass/fond, inte en del av den), medan planens byte
         * storleksbestäms till gapet (issue #75).
         *
         * Saknas endera NAV spelas raden **inte** in. Utfallet mäts mot just de kurserna
         * ([SwitchOutcomeCalc]), så en halv rad vore en rad som aldrig kan utvärderas — och den
         * skulle ändå räknas i "antal givna råd".
         */
        private suspend fun recordFeeSwitches(
            comparable: List<Holding>,
            shownIsinsFor: (String) -> List<String>,
            prices: Map<String, FundPrice>,
            fundPriceRepository: FundPriceRepository,
            suggestionRecordRepository: SuggestionRecordRepository,
            today: LocalDate,
            batchEpochMillis: Long,
        ) {
            var budget = MAX_FEE_RECORDINGS_PER_RUN
            for (holding in comparable) {
                if (budget == 0) return
                val sellIsin = holding.fund.isin ?: continue
                val sellNav = prices[holding.fund.fundId]?.nav ?: continue

                for (buyIsin in shownIsinsFor(sellIsin)) {
                    if (budget == 0) return
                    // Dedupen först: en redan inspelad rad ska varken kosta ett nätverksanrop
                    // eller ta en plats i budgeten från ett innehav som ännu inte hunnit med.
                    if (suggestionRecordRepository.hasRecordedToday(sellIsin, buyIsin, today.toEpochDay(), SuggestionKind.FEE)) continue
                    val buyNav = resolveBuyNav(buyIsin, fundPriceRepository, today) ?: continue

                    suggestionRecordRepository.record(
                        SuggestionRecord(
                            suggestedAtEpochDay = today.toEpochDay(),
                            // Ingen girig, sekventiell plan finns för ett avgiftsbyte — platsen
                            // är meningslös här och räknas därför inte i facits `byPlanIndex`
                            // (SET-5). Alternativens inbördes ordning bärs av listan i
                            // `fund_metadata`, inte av den här kolumnen.
                            planIndex = 0,
                            sellIsin = sellIsin,
                            buyIsin = buyIsin,
                            sellNavAtSuggestion = sellNav,
                            buyNavAtSuggestion = buyNav,
                            switchValueKr = holding.currentValue,
                            batchEpochMillis = batchEpochMillis,
                            kind = SuggestionKind.FEE,
                        ),
                    )
                    budget--
                }
            }
        }

        /**
         * Räknar fram bytesplanen (SwitchPlanCalc, HEM-8/issue #70) och spelar in varje nytt
         * byte i facit ([SuggestionRecordRepository]) — ren logik utan `CoroutineWorker`-
         * beroende, samma mönster som [refreshAll]/[scanComparisons]. Ingen plan alls utan
         * kontotyp ISK/KF (SET-4) eller utan satt riskprofil (SET-3/#71) — appen gissar aldrig
         * någotdera.
         *
         * Köpkandidatens NAV ([Holding.currentValue] finns bara för redan bevakade fonder)
         * löses upp här, inte i [se.partee71.fonder.ui.hem.HemViewModel] — precis den
         * nätverkskostnaden (ISIN-uppslag + kursuppdatering för en fond appen aldrig ägt) är
         * skälet till att facit-inspelningen ligger på backstopen och inte körs live vid varje
         * öppning av Hem, se [KEY_SCAN_SWITCH_PLAN].
         */
        internal suspend fun scanSwitchPlan(
            transactionRepository: TransactionRepository,
            fundPriceRepository: FundPriceRepository,
            fundMetadataRepository: FundMetadataRepository,
            preferencesRepository: PreferencesRepository,
            suggestionRecordRepository: SuggestionRecordRepository,
            today: LocalDate = LocalDate.now(),
            /** Körnings-id som grupperar den här skanningens rader till **en** plan — se [SuggestionRecord.batchEpochMillis]. */
            batchEpochMillis: Long = System.currentTimeMillis(),
        ) {
            if (preferencesRepository.accountType.first() != AccountType.ISK_KF) return
            val riskProfile = preferencesRepository.riskProfile.first() ?: return
            val targetAllocation = riskProfile.effectiveAllocation
            if (targetAllocation.isEmpty()) return

            val funds = transactionRepository.observeFunds().first()
            if (funds.isEmpty()) return
            val holdings = PortfolioCalc.computeHoldings(funds, transactionRepository.observeTransactions().first())
            if (holdings.isEmpty()) return

            val prices = fundPriceRepository.observeLatestPrices(holdings.map { it.fund.fundId }).first()
            val withValue = PortfolioCalc.withCurrentValue(holdings, prices)
            val heldIsins = withValue.mapNotNull { it.fund.isin }.toSet()
            val metadataByIsin = fundMetadataRepository.metadataFor(heldIsins.toList())

            // Bara **underviktade** nivåer, inte varje nivå i målfördelningen: gapen räknas ur
            // redan känd data utan nätverk, medan varje nivå kostar en källfråga plus upp till
            // MAX_SWITCH_VERIFICATION_ATTEMPTS köpbarhetsuppslag. En femnivåfördelning gav
            // tidigare 5 frågor och upp till 50 verifieringar var 12:e timme, för nivåer planen
            // ändå aldrig skulle köpa på — i strid med den budget KEY_SCAN_SWITCH_PLAN
            // dokumenterar (issue #75, punkt 4).
            val levels = SwitchPlanCalc.underweightedLevels(withValue, metadataByIsin, targetAllocation)
            if (levels.isEmpty()) return
            val candidates = levels.flatMap { level ->
                fundMetadataRepository.findSwitchCandidates(level, excludeIsins = heldIsins)
            }
            val plan = SwitchPlanCalc.plan(withValue, metadataByIsin, candidates, targetAllocation)

            plan.forEachIndexed { index, switch ->
                val sellIsin = switch.sellFund.isin ?: return@forEachIndexed
                if (suggestionRecordRepository.hasRecordedToday(sellIsin, switch.buyIsin, today.toEpochDay(), SuggestionKind.RISK_PLAN)) return@forEachIndexed

                // Säljfondens NAV läses direkt ur kurserna. Att härleda det som
                // `currentValue / netShares` gav exakt samma tal via en division som krävde en
                // nollvakt och införde flyttalsfel utan vinst (issue #75, punkt 9).
                val sellNav = prices[switch.sellFund.fundId]?.nav ?: return@forEachIndexed
                val buyNav = resolveBuyNav(switch.buyIsin, fundPriceRepository, today) ?: return@forEachIndexed

                suggestionRecordRepository.record(
                    SuggestionRecord(
                        suggestedAtEpochDay = today.toEpochDay(),
                        planIndex = index,
                        sellIsin = sellIsin,
                        buyIsin = switch.buyIsin,
                        sellNavAtSuggestion = sellNav,
                        buyNavAtSuggestion = buyNav,
                        switchValueKr = switch.sellValueKr,
                        batchEpochMillis = batchEpochMillis,
                    ),
                )
            }

            // Tak mot obegränsad tillväxt — se SuggestionRecordRepository.prune. Körs efter
            // inspelningen, så dagens rader aldrig kan falla offer för sin egen gallring.
            suggestionRecordRepository.prune(today)
        }

        /**
         * Löser upp en köpkandidats aktuella NAV via samma ISIN-uppslagskedja som importflödena
         * (TP-13/TP-14) — null om källan inte kan slå upp fonden eller sakna en kurs.
         *
         * **[FundPriceRepository.refreshSince], inte [FundPriceRepository.refresh]:** en
         * köpkandidat är per definition en fond appen aldrig ägt, så den saknar Handelsbankens
         * FundId och har ISIN:et som identitet ([FundPriceRepository.findFundByIsin]). `refresh`
         * frågar fondlista med `fundId` rakt av och skulle alltså skicka ISIN:et som fondnyckel
         * — det ger aldrig någon kurs, och hela facit-inspelningen föll tyst på den raden (issue
         * #75, punkt 2). `refreshSince` går via ISIN-källkedjan, som är byggd just för fonder
         * utan plattformskod.
         */
        private suspend fun resolveBuyNav(isin: String, fundPriceRepository: FundPriceRepository, today: LocalDate): Double? {
            val fund = fundPriceRepository.findFundByIsin(isin) ?: return null
            fundPriceRepository.refreshSince(fund.fundId, isin, today.minusDays(BUY_NAV_LOOKBACK_DAYS))
            return fundPriceRepository.latestPrice(fund.fundId)?.nav
        }
    }
}
