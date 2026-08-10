package se.partee71.fonder.ui.hem

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.datastore.BenchmarkComponentRef
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.IndexBenchmarkSelector
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.PortfolioCalc
import se.partee71.fonder.domain.usecase.PortfolioExposureCalc
import se.partee71.fonder.domain.usecase.PortfolioFeeCalc
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import se.partee71.fonder.domain.usecase.PortfolioReturnSeriesCalc
import se.partee71.fonder.domain.usecase.PortfolioRiskCalc
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import java.time.LocalDate
import javax.inject.Inject

/** Ett innehav flaggat gul eller röd i analysen (HEM-4), med det underliggande resultatet för visning av triggade signaler. */
data class FlaggedHolding(val fund: Fund, val analysis: FundAnalysisCalc.Analysis)

/**
 * Ett enskilt föreslaget byte i bytesplanen, redo för visning (HEM-8, issue #70). Modellen och
 * uppslagslogiken bor sedan issue #85 i [SwitchPlanResolver], eftersom Fonddetaljs bytesavsnitt
 * (ANA-10) visar samma byten för en enskild fond — två kopior hade kunnat glida isär och ge två
 * olika råd om samma byte. Aliaset behålls så Hems egen kod läser som förut.
 */
typealias SwitchSuggestionUi = SwitchPlanResolver.Suggestion

/**
 * Indexjämförelsens kurva, redo för visning (HEM-10) — skuggportföljen i referensen [fundName],
 * som sedan issue #101 kan vara en viktad blandning av en aktie- och en räntedel. Namnet är inte
 * kosmetiskt: jämförelsen namnger alltid sin egen referens i teckenförklaringen (UI-3), den
 * påstår aldrig att den är "index" i abstrakt mening.
 */
data class BenchmarkSeries(
    /** Etikett för teckenförklaringen — en fond namnges rakt av, en blandning som "70 % X / 30 % Y". */
    val fundName: String,
    val points: List<Pair<Long, Double>>,
    /** Andel av portföljen som varken kunde klassificeras som aktier eller räntor (blandfonder, okänd typ) — vikterna vilar inte på den delen. */
    val unclassifiedFraction: Double = 0.0,
)

/** Summering av [FundAnalysisCalc]-status över alla innehav (issue #16, HEM-4). */
data class AnalysisSummary(
    val gronCount: Int = 0,
    val gulCount: Int = 0,
    val rodCount: Int = 0,
    /** Bara gul/röd, sorterat mest allvarligt (röd) först — se [HemScreen]. */
    val flagged: List<FlaggedHolding> = emptyList(),
)

data class HemUiState(
    val loading: Boolean = true,
    val hasHoldings: Boolean = false,
    val totalInvested: Double = 0.0,
    val totalValue: Double = 0.0,
    val totalGainLoss: Double = 0.0,
    val totalGainLossFraction: Double? = null,
    val performance: PortfolioPerformanceCalc.PortfolioPerformance = PortfolioPerformanceCalc.PortfolioPerformance(
        day = PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
        week = PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
        month = PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
    ),
    val analysisSummary: AnalysisSummary = AnalysisSummary(),
    /** Portföljens totala avkastning i procent per känd NAV-dag (HEM-9, issue #96). Tom = historiken räcker inte till en enda punkt. */
    val returnSeries: PortfolioReturnSeriesCalc.Result = PortfolioReturnSeriesCalc.Result.EMPTY,
    /** Indexjämförelsen som skuggportfölj (HEM-10), null tills referensens historik finns cachad. */
    val benchmarkSeries: BenchmarkSeries? = null,
    /** Köpdagar (epoch-day) för samtliga innehav — markeras i avkastningsdiagrammet, eftersom en insättning flyttar kurvan utan att någon avkastning skett (HEM-9). */
    val purchaseEpochDays: List<Long> = emptyList(),
    /** Äldsta NAV-datumet bland innehav med känt värde, för "per <datum>" bredvid totalen (POR-7, issue #27). */
    val navEpochDay: Long? = null,
    /** Portföljens totala fondavgift per år (HEM-5, issue #60). */
    val feeSummary: PortfolioFeeCalc.Result = PortfolioFeeCalc.Result(totalAnnualFeeKr = 0.0, byHolding = emptyList(), unknownFeeCount = 0),
    /** Riskprofilens målnivå (SET-3), null om ingen profil är satt — raden uteblir helt då (HEM-7, issue #68). */
    val riskProfile: RiskProfile? = null,
    /** Innehavens genomsnittliga risknivå, viktad på värde (HEM-7, issue #68). */
    val portfolioRisk: PortfolioRiskCalc.Result = PortfolioRiskCalc.Result(weightedAverageRisk = null, includedValueKr = 0.0, excludedCount = 0),
    /** Målfördelning mot innehavens faktiska fördelning, per risknivå — tom om ingen profil är satt (HEM-7, issue #71). */
    val riskLevelDeviations: List<PortfolioRiskCalc.LevelDeviation> = emptyList(),
    /** Rangordnad bytesplan mot målfördelningen (HEM-8, issue #70) — tom utom i ISK/KF med en avvikelse som passerat [se.partee71.fonder.domain.usecase.SwitchPlanCalc.MIN_GAP_PP]. */
    val switchPlan: List<SwitchSuggestionUi> = emptyList(),
    /**
     * Sant när en omräkning av bytesplanen (HEM-8, issue #88) över huvud taget kan ge något:
     * riskprofil satt (SET-3) **och** kontotyp ISK/KF (SET-4). Styr om knappen visas — utan
     * gaten hade den lovat något SET-4 ändå vägrar infria.
     */
    val canRecomputeSwitchPlan: Boolean = false,
    /** Sant medan en bakgrundskörning pågår — knappen är då släckt, samma signal som bakgrundsindikatorn (NAV-6). */
    val backgroundWorkRunning: Boolean = false,
) {
    val isEmpty: Boolean get() = !loading && !hasHoldings
}

/** Hur långt tillbaka ett innehavs kurshistorik hämtas för analysen (issue #16) om inget köp finns (bör inte hända för ett verkligt innehav, men skyddar mot en tom historik-hämtning). */
private const val ANALYSIS_FALLBACK_LOOKBACK_YEARS = 1L

/**
 * Hem — ny startskärm (issue #14) med portföljens totala värde/vinst/procent (samma
 * beräkning som Portfölj, [PortfolioCalc]) plus dag/vecka/månads-förändring
 * ([PortfolioPerformanceCalc]) och en analys-summering av gul-/rödflaggade fonder
 * ([FundAnalysisCalc], issue #16, HEM-4). Ingen egen "uppdatera nyss tillagd fond"-logik
 * behövs här (jämför [se.partee71.fonder.ui.portfolj.PortfoljViewModel]) — fonder läggs bara
 * till via Portfölj-fliken (NAV-3), som redan äger den engångsuppdateringen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HemViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val preferencesRepository: PreferencesRepository,
    private val suggestionRecordRepository: SuggestionRecordRepository,
    private val fundPriceRefreshScheduler: FundPriceRefreshScheduler,
) : ViewModel() {

    /**
     * [PortfolioInput.funds] bärs med, inte bara de sammanräknade innehaven: avkastningskurvan
     * (HEM-9) räknar om innehaven för varje historisk dag, och en fond som sedan dess sålts av
     * helt saknas i [PortfolioInput.holdings] trots att den fanns i portföljen då. Utan hela
     * fondlistan hade kurvan ritat om historien som om den fonden aldrig ägts.
     */
    private data class PortfolioInput(
        val funds: List<Fund>,
        val holdings: List<Holding>,
        val transactions: List<Transaction>,
    )

    private val baseHoldings: Flow<PortfolioInput> =
        combine(transactionRepository.observeFunds(), transactionRepository.observeTransactions()) { funds, transactions ->
            PortfolioInput(funds, PortfolioCalc.computeHoldings(funds, transactions), transactions)
        }

    /**
     * Inställningarna **i** flödet, inte lästa med `first()` inne i `map`. Bottennavigeringen
     * använder `popUpTo(saveState = true)`, så den här ViewModel:en överlever ett besök i
     * Inställningar: läste vi värdena i map-kroppen emitterade uppströmsflödet aldrig om på en
     * ändring, och SET-4-gaten utvärderades mot det gamla valet tills en kursuppdatering eller
     * en ny transaktion råkade trigga flödet — upp till 12 timmar senare (issue #75).
     */
    private val settings: Flow<Settings> =
        combine(
            preferencesRepository.riskProfile,
            preferencesRepository.accountType,
            preferencesRepository.benchmark,
        ) { riskProfile, accountType, benchmark ->
            Settings(riskProfile, accountType, benchmark)
        }

    /**
     * Fondmetadata i ett eget flöde — samma skäl som i
     * [se.partee71.fonder.ui.portfolj.PortfoljViewModel]: [FundMetadataRepository.metadataFor]
     * gör ett sekventiellt nätverksuppslag per okänd/inaktuell ISIN, och låg det i `map`
     * blockerades hela Hem på nätverket. Slår upp både innehavens och den visade planens
     * köpkandidater i **ett** anrop.
     */
    private val metadata = MutableStateFlow<Map<String, FundMetadata>>(emptyMap())
    private var metadataJob: Job? = null
    private var metadataIsins: List<String>? = null

    /** Se [requestBenchmarkIfMissing] — flödet emitterar om vid varje kursändring, begäran ska ändå bara gå en gång. */
    private var benchmarkScanRequested = false

    private fun refreshMetadata(isins: List<String>) {
        val distinct = isins.distinct().sorted()
        if (distinct == metadataIsins) return
        metadataIsins = distinct
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch { metadata.value = fundMetadataRepository.metadataFor(distinct) }
    }

    val uiState: StateFlow<HemUiState> =
        baseHoldings.flatMapLatest { (funds, holdings, transactions) ->
            val fundIds = holdings.map { it.fund.fundId }
            combine(
                fundPriceRepository.observeLatestPrices(fundIds),
                metadata,
                settings,
                suggestionRecordRepository.observeLatestBatch(),
            ) { prices, metadataByIsin, currentSettings, latestBatch ->
                val enriched = PortfolioCalc.withCurrentValue(holdings, prices)
                val today = LocalDate.now()
                val firstPurchaseByFund = transactions
                    .groupBy { it.fundId }
                    .mapValues { (_, txs) -> LocalDate.ofEpochDay(txs.minOf { it.epochDay }) }
                // **En** hämtning per fond, inte en per läsare: fönstret är det vidaste någon av
                // dem behöver (sedan första köpet för analysen och avkastningskurvan, minst
                // HISTORY_LOOKBACK_DAYS bakåt för dag/vecka/månad även för en nyss köpt fond).
                // Var och en klipper sedan till sitt eget fönster i minnet — analysen räknar
                // volatilitet på hela listan den får (ANA-7) och måste därför få exakt sitt.
                val historyByFundId = funds.associate { fund ->
                    val since = firstPurchaseByFund[fund.fundId]
                        ?.let { minOf(it, today.minusDays(PortfolioPerformanceCalc.HISTORY_LOOKBACK_DAYS)) }
                        ?: today.minusYears(ANALYSIS_FALLBACK_LOOKBACK_YEARS)
                    fund.fundId to fundPriceRepository.priceHistory(
                        fundId = fund.fundId,
                        fromEpochDay = since.toEpochDay(),
                        toEpochDay = today.toEpochDay(),
                    )
                }
                refreshMetadata(enriched.mapNotNull { it.fund.isin } + latestBatch.map { it.buyIsin })
                requestBenchmarkIfMissing(currentSettings.benchmark, hasHoldings = enriched.isNotEmpty())
                val riskProfile = currentSettings.riskProfile
                val exposure = PortfolioExposureCalc.compute(enriched, metadataByIsin)
                val switchPlan = SwitchPlanResolver.resolve(currentSettings.accountType, latestBatch, metadataByIsin, today)
                HemUiState(
                    loading = false,
                    hasHoldings = enriched.isNotEmpty(),
                    totalInvested = PortfolioCalc.totalInvested(enriched),
                    totalValue = PortfolioCalc.totalValue(enriched),
                    totalGainLoss = PortfolioCalc.totalGainLoss(enriched),
                    totalGainLossFraction = PortfolioCalc.totalGainLossFraction(enriched),
                    performance = PortfolioPerformanceCalc.totalPerformance(enriched, today, historyByFundId),
                    analysisSummary = buildAnalysisSummary(enriched, firstPurchaseByFund, historyByFundId, today),
                    returnSeries = PortfolioReturnSeriesCalc.compute(funds, transactions, historyByFundId),
                    benchmarkSeries = buildBenchmark(
                        refs = currentSettings.benchmark,
                        transactions = transactions,
                        unclassifiedFraction = IndexBenchmarkSelector.exposureSplit(exposure.byType).unclassifiedFraction,
                        today = today,
                    ),
                    purchaseEpochDays = transactions
                        .filter { it.type == TransactionType.KOP }
                        .map { it.epochDay }
                        .distinct()
                        .sorted(),
                    navEpochDay = PortfolioCalc.oldestKnownNavEpochDay(enriched),
                    feeSummary = PortfolioFeeCalc.compute(enriched, metadataByIsin, today),
                    riskProfile = riskProfile,
                    portfolioRisk = PortfolioRiskCalc.compute(enriched, metadataByIsin),
                    riskLevelDeviations = riskProfile?.let {
                        PortfolioRiskCalc.deviationByLevel(
                            targetAllocation = it.effectiveAllocation,
                            actualAllocation = PortfolioRiskCalc.actualAllocation(exposure.byRiskLevel),
                        )
                    }.orEmpty(),
                    switchPlan = switchPlan,
                    canRecomputeSwitchPlan = riskProfile != null && currentSettings.accountType == AccountType.ISK_KF,
                )
            }
        }.combine(fundPriceRefreshScheduler.observeIsRunning()) { state, running ->
            // Eget flöde, inte en femte gren i combinen ovan: körstatusen kommer från
            // WorkManager och har ingenting med innehav, kurser eller inställningar att göra —
            // den ska inte kunna räkna om portföljen bara för att ett jobb startade.
            state.copy(backgroundWorkRunning = running)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HemUiState(),
        )

    /**
     * Analyserar varje innehav och summerar statusarna (HEM-4). Kurshistoriken kommer från den
     * gemensamma hämtningen i flödet ovan (lokal cache, Room — ingen nätverksuppdatering) och
     * **klipps här till sedan första köpet**, samma räckvidd som
     * [se.partee71.fonder.ui.fond.FondDetaljViewModel] använder för sin egen fond. Klippet är
     * inte kosmetiskt: [FundAnalysisCalc] räknar volatilitet och Sharpe på hela listan den får
     * (ANA-7), så en vidare historik hade tyst gett andra siffror än fondkortet visar.
     */
    private fun buildAnalysisSummary(
        enriched: List<Holding>,
        firstPurchaseByFund: Map<String, LocalDate>,
        fullHistoryByFundId: Map<String, List<FundPrice>>,
        today: LocalDate,
    ): AnalysisSummary {
        if (enriched.isEmpty()) return AnalysisSummary()

        val portfolioTotalValue = PortfolioCalc.totalValue(enriched)

        val historyByFundId = enriched.associate { holding ->
            val since = firstPurchaseByFund[holding.fund.fundId] ?: today.minusYears(ANALYSIS_FALLBACK_LOOKBACK_YEARS)
            holding.fund.fundId to fullHistoryByFundId[holding.fund.fundId].orEmpty()
                .filter { it.epochDay >= since.toEpochDay() }
        }

        val analyses = enriched.mapNotNull { holding ->
            val firstPurchase = firstPurchaseByFund[holding.fund.fundId] ?: return@mapNotNull null
            val otherHistories = historyByFundId.filterKeys { it != holding.fund.fundId }
            val analysis = FundAnalysisCalc.analyze(
                today = today,
                holding = holding,
                priceHistory = historyByFundId[holding.fund.fundId].orEmpty(),
                firstPurchaseDate = firstPurchase,
                portfolioTotalValue = portfolioTotalValue,
                otherHoldingsAverageThreeMonthReturn = FundAnalysisCalc.averageThreeMonthReturn(today, otherHistories),
            ) ?: return@mapNotNull null
            FlaggedHolding(holding.fund, analysis)
        }

        return AnalysisSummary(
            gronCount = analyses.count { it.analysis.status == FundAnalysisCalc.SignalLevel.GRON },
            gulCount = analyses.count { it.analysis.status == FundAnalysisCalc.SignalLevel.GUL },
            rodCount = analyses.count { it.analysis.status == FundAnalysisCalc.SignalLevel.ROD },
            flagged = analyses
                .filter { it.analysis.status == FundAnalysisCalc.SignalLevel.GUL || it.analysis.status == FundAnalysisCalc.SignalLevel.ROD }
                .sortedByDescending { it.analysis.status == FundAnalysisCalc.SignalLevel.ROD },
        )
    }

    /**
     * Ber bakgrundsjobbet välja referensfond (HEM-10) när ingen är vald än. Bara backstopen
     * satte tidigare den skanningen, så en ny installation eller uppgradering visade ingen
     * indexkurva alls förrän en körning råkat passera — upp till ett halvt dygn av "Ingen
     * indexjämförelse än" utan att något var fel.
     *
     * Högst en begäran per ViewModel-livstid, och bara med innehav: skanningen kostar en
     * källfråga plus en backfill av referensfondens historik, och flödet emitterar om vid varje
     * kursändring. Misslyckas den (källan nere, ingen global indexfond i katalogen) står
     * referensblandningen tom och nästa appstart frågar igen — inget tyst förevigat
     * misslyckande, men heller ingen omförsökssnurra som pollar källan.
     */
    private fun requestBenchmarkIfMissing(benchmark: List<BenchmarkComponentRef>, hasHoldings: Boolean) {
        if (benchmark.isNotEmpty() || !hasHoldings || benchmarkScanRequested) return
        benchmarkScanRequested = true
        fundPriceRefreshScheduler.triggerBenchmarkScan()
    }

    /**
     * Indexjämförelsen (HEM-10) — skuggportföljen i referensfonden, byggd **enbart ur cachen**.
     * Referensfonden är vald av bakgrundsjobbet
     * ([se.partee71.fonder.worker.FundPriceUpdateWorker.scanBenchmark]) och dess kurshistorik
     * hämtad där; Hem gör aldrig ett nätverksanrop när det öppnas, av samma skäl som
     * HEM-6/HEM-8 ligger i workern. `cachedMetadataFor` går per kontrakt aldrig till nätet.
     *
     * Null i varje läge där jämförelsen inte kan bli ärlig — ingen historik alls, historik som
     * inte når tillbaka till första köpet (se [PortfolioReturnSeriesCalc.benchmark]), eller en
     * fond appen inte kan namnge. Kortet säger då varför, i stället för att rita en halv
     * jämförelse.
     */
    private suspend fun buildBenchmark(
        refs: List<BenchmarkComponentRef>,
        transactions: List<Transaction>,
        unclassifiedFraction: Double,
        today: LocalDate,
    ): BenchmarkSeries? {
        if (refs.isEmpty() || transactions.isEmpty()) return null
        val since = LocalDate.ofEpochDay(transactions.minOf { it.epochDay })
        val components = refs.map { ref ->
            PortfolioReturnSeriesCalc.BenchmarkComponent(
                weight = ref.weight,
                history = fundPriceRepository.priceHistory(ref.isin, since.toEpochDay(), today.toEpochDay()),
            )
        }
        val series = PortfolioReturnSeriesCalc.benchmark(transactions, components) ?: return null
        if (series.isEmpty) return null

        val names = fundMetadataRepository.cachedMetadataFor(refs.map { it.isin })
        // Kan appen inte namnge varje del kan den inte heller redovisa blandningen ärligt, och
        // en teckenförklaring med ett ISIN i vore obegriplig — hellre ingen jämförelse.
        val labels = refs.map { ref -> names[ref.isin]?.name ?: return null }
        val label = if (refs.size == 1) {
            labels.single()
        } else {
            refs.zip(labels).joinToString(" / ") { (ref, name) -> "${MoneyFormat.percent(ref.weight)} $name" }
        }
        return BenchmarkSeries(fundName = label, points = series.points, unclassifiedFraction = unclassifiedFraction)
    }

    /**
     * Markerar ett förslag i den visade planen som genomfört (HEM-8/SET-5, issue #80). Skriver
     * bara flaggan — inget byte utförs, appen rör aldrig användarens innehav. Facit (SET-5)
     * läser den för att mäta följda råd separat från alla givna råd.
     */
    fun setSwitchFollowed(recordId: Long, followed: Boolean) {
        viewModelScope.launch { suggestionRecordRepository.setFollowed(recordId, followed) }
    }

    /**
     * Ber om en omräkning av bytesplanen (HEM-8, issue #88) — knappen på riskkortet. Kör inte
     * själv: skanningen kostar en källfråga plus budgeterad köpbarhetsverifiering per
     * underviktad nivå och hör därför hemma i WorkManager, med nätverksvillkor och
     * koalescering, precis som backstopens egen körning.
     */
    fun recomputeSwitchPlan() {
        fundPriceRefreshScheduler.triggerSwitchPlanScan()
    }

    private data class Settings(
        val riskProfile: RiskProfile?,
        val accountType: AccountType?,
        /** Referensblandningen (HEM-10) — härledd cache, satt av bakgrundsjobbet, inte ett användarval. */
        val benchmark: List<BenchmarkComponentRef>,
    )
}
