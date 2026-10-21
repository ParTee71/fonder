package se.partee71.fonder.ui.fond

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.common.AppErrors
import se.partee71.fonder.data.common.suspendRunCatching
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.SwitchWatchRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.data.repository.isPriceStale
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.SuggestionDismissal
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.ChartSeriesNormalizer
import se.partee71.fonder.domain.usecase.CheaperAlternatives
import se.partee71.fonder.domain.usecase.DismissedPlanSource
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.InProgressSwitches
import se.partee71.fonder.domain.usecase.MovingAverageCalc
import se.partee71.fonder.domain.usecase.PortfolioCalc
import se.partee71.fonder.domain.usecase.RecordTransaction
import se.partee71.fonder.domain.usecase.SuggestionDismissals
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.ui.common.RetrySignal
import se.partee71.fonder.ui.common.catchAsDetailError
import se.partee71.fonder.ui.common.errorOrNull
import se.partee71.fonder.ui.common.restartOn
import se.partee71.fonder.ui.common.valueOrNull
import se.partee71.ui.common.DetailUiState
import se.partee71.ui.common.UiError

/**
 * Det Fonddetalj läser ur Room för fonden: namn, ISIN, kurshistorik, innehavets utgångsläge och
 * analysen — innehållet i [FondDetaljUiState.fund], som detaljramen (`EntityDetailScreen`) visar.
 * Vid ett läsfel, eller när fonden inte finns längre, finns inget av det, och ramen visar felet i
 * stället för en tom fond (NFR-6).
 */
@Immutable
data class FundDetail(
    val fundName: String? = null,
    val isin: String? = null,
    val suggestedIsin: String? = null,
    val prices: ImmutableList<FundPrice> = persistentListOf(),
    /**
     * 200-dagarssnittet (epochDay, NAV) för varje kurspunkt där historiken räcker, i stigande
     * ordning (ANA-14, [MovingAverageCalc]) — räknat ur hela den laddade historiken, inte ur
     * diagrammets valda period, så diagrammet bara behöver beskära den. Tom = historiken räcker
     * inte, ingen snittlinje (ANA-4).
     */
    val movingAverage: ImmutableList<Pair<Long, Double>> = persistentListOf(),
    /** Första köp-datum och kvarvarande (FIFO) inköpsvärde — null om fonden inte är ett kvarvarande innehav (POR-6, issue #18). */
    val firstPurchaseEpochDay: Long? = null,
    val netInvested: Double? = null,
    /** Köpdagar (epochDay) för den här fonden — markeras i diagrammet när de ingår i den
     *  valda perioden, se [se.partee71.fonder.ui.diagram.FundLineChart] (issue #55). */
    val purchaseEpochDays: ImmutableList<Long> = persistentListOf(),
    /** Nyckeltal och säljsignaler (issue #16) — null om fonden inte är ett kvarvarande innehav. */
    val analysis: FundAnalysisCalc.Analysis? = null,
    /** Innehavets nuvarande värde (netShares × senaste NAV) — null om okänt/inte ett innehav. Underlag för [FondDetaljUiState.feeComparison]. */
    val holdingValue: Double? = null
)

@Immutable
data class FondDetaljUiState(
    /** Fonden — laddning, läsfel eller "finns inte längre" (NFR-6), eller innehåll; detaljramens tillstånd. */
    val fund: DetailUiState<FundDetail> = DetailUiState.Loading,
    /** Billigare-alternativ-jämförelse (ANA-9, issue #59). Null = fonden är inte ett kvarvarande innehav, inget kort visas. */
    val feeComparison: FeeComparisonUiState? = null,
    /** Fondens egen risknivå på källans skala 1–7 (TP-21), null om okänd — visas ut som okänd, aldrig gissad (UI-10, issue #85). */
    val riskLevel: Int? = null,
    /**
     * De byten i riskprofilens bytesplan (HEM-8) som rör just den här fonden — sälj härifrån
     * eller köp hit (ANA-10, issue #85). Tom lista utan ISK/KF (SET-4), utan inspelad plan,
     * eller när planen inte nämner fonden.
     */
    val switchPlan: ImmutableList<SwitchPlanResolver.Suggestion> = persistentListOf(),
    /**
     * Kurshistorik för föreslagna fonder, nycklad på kandidatens ISIN (ANA-11) — hämtas först
     * när ett förslag fälls ut ([FondDetaljViewModel.onSuggestionExpanded]) och hålls bara i
     * minnet, aldrig i kurscachen (se [FundPriceRepository.historyForIsin]).
     */
    val comparisons: ImmutableMap<String, ComparisonUiState> = persistentMapOf(),
    /**
     * De inspelade avgiftsbytena (ANA-9, issue #91) för den här fonden, nycklade på
     * kandidatens ISIN. Ett alternativ som saknas här har ingen inspelad rad — då visas ingen
     * kvittering alls, aldrig en kryssruta som inte skriver någonstans. Asymmetrin är väntad:
     * listan räknas fram live vid skärmöppning, raden skrivs av bakgrundsskanningen.
     */
    val recordedFeeSwitches: ImmutableMap<String, RecordedFeeSwitch> = persistentMapOf(),
    /**
     * Bytesplanens förslag för fonden som användaren avstått från (HEM-14) — de saknas i [switchPlan]
     * och visas bara som ett antal med vägen till Ångra på Beslut.
     */
    val dismissedPlanCount: Int = 0,
    /** Billigare alternativ (ANA-9) användaren avstått från — de saknas i [feeComparison], se [dismissedPlanCount]. */
    val dismissedFeeCount: Int = 0,
    /** Säljfonder som redan har en öppen bevakning (ANA-12) — styr om ett kvitterat byte erbjuder "Bevaka alternativ". */
    val watchedSellIsins: ImmutableSet<String> = persistentSetOf(),
    /** Sätts när en bevakning just startats, så skärmen kan navigera dit. Kvitteras med [FondDetaljViewModel.onSwitchWatchOpened]. */
    val startedSwitchWatchId: Long? = null,
    /**
     * Sant medan skärmens engångsuppdatering av fondens kurs pågår (se [FondDetaljViewModel]s
     * `init`) — rubrikens och diagrammets väntesnurra (NAV-6). En backfillad historik tar
     * sekunder, och utan snurran ser en halvfylld kurva ut som hela sanningen.
     */
    val priceRefreshing: Boolean = false
) {
    val loading: Boolean get() = fund is DetailUiState.Loading

    /** Läsfelet som visas i stället för fonden (NFR-6). */
    val error: UiError? get() = fund.errorOrNull()

    /** Det lästa, eller tomt medan fonden laddas eller inte gick att läsa (då visar ramen sitt läge i stället). */
    val content: FundDetail get() = fund.valueOrNull() ?: FundDetail()

    val isEmpty: Boolean get() = fund.valueOrNull()?.prices?.isEmpty() == true

    /** Avstådda förslag av båda sorterna för fonden. */
    val dismissedCount: Int get() = dismissedPlanCount + dismissedFeeCount

    /** Rubriken och kurshistoriken vilar båda på samma hämtning — härlett här, inte i skärmen. */
    val chartWorking: Boolean get() = loading || priceRefreshing
}

/** Ett inspelat avgiftsbyte (issue #91) — nyckeln facit skrivs mot när kvitteringen används. */
data class RecordedFeeSwitch(val recordId: Long, val followed: Boolean)

/** Kurshistoriken för en föreslagen fond, till jämförelsediagrammet (ANA-11, issue #85). */
@Immutable
sealed interface ComparisonUiState {
    /** Hämtningen pågår — ett nätverksanrop mot ISIN-källkedjan (TP-14). */
    data object Loading : ComparisonUiState

    /** Ingen källa kunde ge kandidatens historik — diagrammet utelämnas och sägs ut, aldrig ett tomt diagram. */
    data object Unavailable : ComparisonUiState

    /** Kandidatens kurshistorik, (epochDay, NAV) i stigande datumordning. */
    data class Ready(val points: ImmutableList<Pair<Long, Double>>) : ComparisonUiState
}

/**
 * Resultatet av att jämföra innehavets avgift mot billigare, likvärdiga alternativ (ANA-9,
 * issue #59) — appens första rådgivande funktion (ANA-3:s tidigare "aldrig
 * rådgivning"-princip är struken, se KRAVLISTA). Beräknas en gång per skärmöppning
 * ("engångsuppdatering", samma princip som prisuppdateringen nedan), inte reaktivt vid varje
 * NAV-tick, eftersom den kan kosta flera nätverksanrop (budgeterad köpbarhetsverifiering).
 */
@Immutable
sealed interface FeeComparisonUiState {
    /** Jämförelsen pågår — flera nätverksanrop kan behövas. */
    data object Loading : FeeComparisonUiState

    /** Fonden saknar ISIN, finns inte i källans universum, eller saknar känd avgift (ANA-4-principen). */
    data object Unavailable : FeeComparisonUiState

    /** Inga alternativ med identisk exponering och lägre avgift hittades — fonden är redan bland de billigaste i sin kategori. */
    data object NoCheaperAlternative : FeeComparisonUiState

    data class Found(val alternatives: ImmutableList<FeeComparisonCalc.Alternative>) : FeeComparisonUiState
}

private data class Snapshot(
    val funds: List<Fund>,
    val transactions: List<Transaction>,
    val since: LocalDate?,
    val suggestedIsin: String?,
    val purchaseEpochDays: List<Long>
)

/** Bytesplanens och avgiftsbytenas indata innan de resolvas — se [FondDetaljViewModel.planInput]. */
private data class PlanInput(
    val accountType: AccountType?,
    val batch: List<SuggestionRecord>,
    val metadataByIsin: Map<String, FundMetadata>,
    /** Hela inspelade historiken — avgiftsbytena kan vara äldre än den senaste körningen (issue #91). */
    val history: List<SuggestionRecord>,
    /** Avståendena (HEM-14) — ett avstått förslag visas inte heller på fondkortet. */
    val dismissals: List<SuggestionDismissal>,
    /** De avstådda planbytena ur [DismissedPlanSource] — samma källa och samma resolvering som Beslut, så antalen aldrig skiljer sig. */
    val dismissedPlan: List<SwitchPlanResolver.Suggestion> = emptyList(),
    /** Se [se.partee71.fonder.data.datastore.PreferencesRepository.switchPlanEmptiedAtEpochMillis]. */
    val emptiedAtEpochMillis: Long? = null
)

/** Hur långt tillbaka övriga innehavs kurshistorik hämtas ur cachen för momentum-signalen (S3, ANA-2) — tre månader plus en buffert för helger/röda dagar utan NAV. */
private const val OTHER_HOLDINGS_HISTORY_LOOKBACK_MONTHS = 4L

/**
 * Hur långt tillbaka en föreslagen fonds kurshistorik hämtas för jämförelsediagrammet (ANA-11).
 * Täcker diagrammets längsta fasta period ("1 år") med marginal; "Allt" beskärs då till den
 * gemensamma delen och markeras som en delvis jämförelse ([ChartSeriesNormalizer]) — hellre det
 * än att hämta hela historiken för en fond användaren bara tittar på.
 */
private const val COMPARISON_HISTORY_YEARS = 3L

/**
 * Fonddetalj — kurshistorik sedan första köpet i diagram, inte bara senaste året
 * (issue #7-uppföljning: se KRAVLISTA TP-14/TP-18). Har fonden ett känt ISIN
 * (`Fund.isin`) provas fondlista-källan först och en ISIN-baserad källkedja (Avanza m.fl.)
 * som reserv. Saknas ISIN föreslås ett via namnsökning — användaren bekräftar/rättar innan
 * det sparas (samma "föreslå men kräv bekräftelse"-princip som importflödet, IMP-2).
 *
 * Bygger även [FundAnalysisCalc]-nyckeltal/säljsignaler (issue #16) — kräver, utöver den
 * redan reaktivt laddade kurshistoriken för den här fonden, portföljens totala värde (för
 * portföljandelen) och övriga innehavs tremånadershistorik (för momentum-signalen S3), som
 * hämtas ur den lokala cachen (ingen extra nätverksuppdatering).
 *
 * Bär dessutom hela **bytesbeslutet** för fonden (ANA-10, issue #85): billigare, likvärdiga
 * alternativ (ANA-9, issue #59, se [FeeComparisonUiState]), de av riskprofilens inspelade
 * byten som rör just den här fonden ([SwitchPlanResolver], HEM-8) och fondens egen risknivå
 * (UI-10). Kurshistoriken för en föreslagen fond hämtas först när förslaget fälls ut
 * ([onSuggestionExpanded]) — inte vid öppning, eftersom varje kandidat kostar ett nätverksanrop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = FondDetaljViewModel.Factory::class)
class FondDetaljViewModel @AssistedInject constructor(
    /** Fonden skärmen visar — ur navigationsnyckeln (`FondDetaljKey`). */
    @Assisted private val fundId: String,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val preferencesRepository: PreferencesRepository,
    private val suggestionRecordRepository: SuggestionRecordRepository,
    switchWatchRepository: SwitchWatchRepository,
    private val recordTransaction: RecordTransaction,
    dismissedPlanSource: DismissedPlanSource,
    inProgressSwitches: InProgressSwitches
) : ViewModel() {

    /** Se [FondDetaljUiState.startedSwitchWatchId] — engångshändelse, inte ett bestående tillstånd. */
    private val startedSwitchWatchId = MutableStateFlow<Long?>(null)
    private val suggestedIsin = MutableStateFlow<String?>(null)
    private val feeComparisonState = MutableStateFlow<FeeComparisonUiState?>(null)

    /** Se [FondDetaljUiState.priceRefreshing] — skärmens egen engångsuppdatering, inte WorkManager. */
    private val priceRefreshing = MutableStateFlow(false)

    /**
     * Fondmetadata i ett eget flöde, inte hämtad inne i tillståndets `map` — samma skäl som i
     * [se.partee71.fonder.ui.hem.HemViewModel]/[se.partee71.fonder.ui.portfolj.PortfoljViewModel]:
     * [FundMetadataRepository.metadataFor] gör ett sekventiellt nätverksuppslag per okänd eller
     * inaktuell ISIN, och låg det i flödet blockerades hela fondkortet på nätverket. Slår upp
     * både fondens egen risknivå (UI-10) och bytesplanens ISIN (ANA-10) i **ett** anrop.
     */
    private val metadata = MutableStateFlow<Map<String, FundMetadata>>(emptyMap())
    private var metadataJob: Job? = null
    private var metadataIsins: List<String>? = null

    /** Kandidaternas kurshistorik, hämtad först vid utfällning — se [onSuggestionExpanded]. */
    private val comparisons = MutableStateFlow<Map<String, ComparisonUiState>>(emptyMap())

    private fun refreshMetadata(isins: List<String>) {
        val distinct = isins.distinct().sorted()
        if (distinct == metadataIsins) return
        metadataIsins = distinct
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch { metadata.value = fundMetadataRepository.metadataFor(distinct) }
    }

    private val fundTransactions: Flow<List<Transaction>> =
        transactionRepository.observeTransactionsForFund(fundId)

    private val earliestPurchase: Flow<LocalDate?> =
        fundTransactions.map { transactions -> transactions.minOfOrNull { it.epochDay }?.let(LocalDate::ofEpochDay) }

    /** Köpdagar (epochDay) — se [FondDetaljUiState.purchaseEpochDays]. */
    private val purchaseEpochDays: Flow<List<Long>> =
        fundTransactions.map { transactions ->
            transactions.filter { it.type == TransactionType.KOP }.map { it.epochDay }.distinct()
        }

    private val baseUiState: Flow<FondDetaljUiState> = combine(
        transactionRepository.observeFunds(),
        transactionRepository.observeTransactions(),
        earliestPurchase,
        suggestedIsin,
        purchaseEpochDays
    ) { funds, transactions, since, suggested, purchases -> Snapshot(funds, transactions, since, suggested, purchases) }
        .flatMapLatest { snapshot ->
            val fundIds = snapshot.funds.map { it.fundId }
            val priceHistoryFlow = fundPriceRepository.observePriceHistory(
                fundId = fundId,
                fromEpochDay = (snapshot.since ?: LocalDate.now().minusYears(1)).toEpochDay(),
                toEpochDay = LocalDate.now().toEpochDay()
            )
            combine(fundPriceRepository.observeLatestPrices(fundIds), priceHistoryFlow) { latestPrices, history ->
                Triple(snapshot, latestPrices, history)
            }
        }
        .map { (snapshot, latestPrices, history) ->
            // En fond som inte finns (längre) – borttagen, eller databasen tömd – sägs ut utan
            // "Försök igen", aldrig som en tom fond (NFR-6).
            val fund = snapshot.funds.firstOrNull { it.fundId == fundId }
                ?: return@map DetailUiState.Error(AppErrors.notFound())
            val holdings = PortfolioCalc.computeHoldings(snapshot.funds, snapshot.transactions)
            val holding = holdings.firstOrNull { it.fund.fundId == fundId }
            val holdingValue = holding?.let { PortfolioCalc.withCurrentValue(listOf(it), latestPrices).first().currentValue }
            val detail = FundDetail(
                fundName = fund.name,
                isin = fund.isin,
                suggestedIsin = snapshot.suggestedIsin,
                prices = history.sortedByDescending { it.epochDay }.toImmutableList(),
                movingAverage = MovingAverageCalc.series(history.map { it.epochDay to it.nav }).toImmutableList(),
                firstPurchaseEpochDay = holding?.firstPurchaseEpochDay,
                netInvested = holding?.netInvested,
                purchaseEpochDays = snapshot.purchaseEpochDays.toImmutableList(),
                analysis = buildAnalysis(holdings, holding, latestPrices, history),
                holdingValue = holdingValue
            )
            DetailUiState.Content(detail)
        }
        .map { FondDetaljUiState(fund = it) }

    /** Bytesplanens råmaterial: kontotyp (SET-4-gaten), senast inspelade batch och metadatan de slås upp mot. */
    private val planInput: Flow<PlanInput> = combine(
        preferencesRepository.accountType,
        suggestionRecordRepository.observeLatestBatch(),
        metadata,
        // Historiken, inte batchen: ett avgiftsbyte spelas in av jämförelseskanningen (HEM-6),
        // som går sin egen takt — den senaste körningens rader säger inget om huruvida just den
        // här fondens alternativ har en inspelad rad.
        suggestionRecordRepository.observeHistory(),
        // Avståendena och de avstådda planbytenas rader ur samma källa och samma emission som Beslut.
        dismissedPlanSource.observe()
    ) { accountType, batch, metadataByIsin, history, dismissed ->
        PlanInput(accountType, batch, metadataByIsin, history, dismissed.dismissals, dismissed.suggestions)
    }
        .combine(preferencesRepository.switchPlanEmptiedAtEpochMillis) { input, emptiedAt -> input.copy(emptiedAtEpochMillis = emptiedAt) }

    /** Öppna bevakningar, de pågående raderna (BYT-1) och den nystartades id — hållna ihop så combinen nedan inte växer i onödan. */
    private class SwitchWatchState(val watchedSellIsins: Set<String>, val inProgress: InProgressSwitches.Keys, val startedWatchId: Long?)

    private val switchWatchState: Flow<SwitchWatchState> = combine(
        switchWatchRepository.observeOpen().map { watches -> watches.mapTo(mutableSetOf<String>()) { it.sellIsin } },
        inProgressSwitches.observe(),
        startedSwitchWatchId
    ) { sellIsins, inProgress, startedId -> SwitchWatchState(sellIsins, inProgress, startedId) }

    /** Läsfelets "Försök igen" (NFR-6) – se [retry]. */
    private val retrySignal = RetrySignal()

    val uiState: StateFlow<FondDetaljUiState> = combine(
        baseUiState,
        feeComparisonState,
        planInput,
        comparisons,
        switchWatchState
    ) { state, feeComparison, plan, comparisonsByIsin, watchState ->
        // Metadatan behövs för fondens egen risknivå **och** för att kunna slå upp planens
        // fondnamn/risknivåer — begärs här, där båda ISIN-mängderna är kända, och landar via
        // `metadata`-flödet ovan utan att blockera tillståndet.
        refreshMetadata(listOfNotNull(state.content.isin) + plan.batch.flatMap { listOf(it.sellIsin, it.buyIsin) })
        val today = LocalDate.now()
        val resolvedPlan = SwitchPlanResolver.resolve(plan.accountType, plan.batch, plan.metadataByIsin, today, plan.emptiedAtEpochMillis)
        // Ett avstått förslag (HEM-14) döljs här också, med samma regel som på Beslut — fondkortet
        // och Beslut får inte ge olika besked om samma förslag. Antalet avstådda kommer ur samma
        // källa som Beslut (DismissedPlanSource), inte ur den visade planen: ett avstått par tas ur
        // planen vid nästa omräkning, och fondkortet ska ändå säga att det finns något att ångra.
        val (fundPlan, _) = SuggestionDismissals.partition(
            // Samma regel som Beslut för "sålt, väntar på köp" (BYT-1).
            watchState.inProgress.mark(SwitchPlanResolver.forFund(resolvedPlan, state.content.isin)),
            plan.dismissals,
            today
        )
        val dismissedPlan = SwitchPlanResolver.forFund(plan.dismissedPlan, state.content.isin)
        val recordedFee = recordedFeeSwitches(plan.history, state.content.isin)
        val (visibleFeeComparison, dismissedFeeCount) = feeComparison.withoutDismissed(
            isin = state.content.isin,
            recorded = recordedFee,
            activeDismissals = SuggestionDismissals.activeKeys(plan.dismissals, today)
        )
        state.copy(
            feeComparison = visibleFeeComparison,
            riskLevel = state.content.isin?.let { plan.metadataByIsin[it]?.risk },
            switchPlan = fundPlan.toImmutableList(),
            dismissedPlanCount = dismissedPlan.size,
            dismissedFeeCount = dismissedFeeCount,
            comparisons = comparisonsByIsin.toImmutableMap(),
            recordedFeeSwitches = recordedFee.toImmutableMap(),
            watchedSellIsins = watchState.watchedSellIsins.toImmutableSet(),
            startedSwitchWatchId = watchState.startedWatchId
        )
    }.combine(priceRefreshing) { state, refreshing ->
        // Eget led i kedjan i stället för en sjätte gren i combinen ovan: `combine` tar som mest
        // fem flöden med olika typer, och körstatusen ska ändå inte kunna räkna om fondens data.
        state.copy(priceRefreshing = refreshing)
    }
        // Sist i kedjan: ett läsfel i vilken källa som helst (fonden, kurserna, planen,
        // bevakningarna) visas som fel i stället för att krascha fondkortet (NFR-6).
        .catchAsDetailError { FondDetaljUiState(fund = it) }
        // Varje läsning – även efter "Försök igen" – börjar i laddningsläget, så att ramen visar
        // laddningen och inte det gamla felet medan fonden läses om (NFR-6).
        .onStart { emit(FondDetaljUiState()) }
        .restartOn(retrySignal)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FondDetaljUiState()
        )

    /**
     * Startar en bevakning av ett pågående byte ur ett kvitterat bytesförslag (ANA-12, issue
     * #114) — samma väg som Beslut ([RecordTransaction.startWatchFromSuggestion]), eftersom det är
     * samma förslag och samma inspelade rad: fondkortet och Beslut får aldrig ge två olika
     * bevakningar av samma byte.
     */
    fun startSwitchWatch(suggestion: SwitchPlanResolver.Suggestion) {
        viewModelScope.launch {
            recordTransaction.startWatchFromSuggestion(suggestion)?.let { startedSwitchWatchId.value = it }
        }
    }

    /** Kvitterar navigeringen till en nystartad bevakning, så den inte upprepas vid nästa emission. */
    fun onSwitchWatchOpened() {
        startedSwitchWatchId.value = null
    }

    /**
     * De inspelade avgiftsbytena för fonden [isin], nycklade på kandidatens ISIN (issue #91) — den
     * **senaste** raden per kandidat ([CheaperAlternatives.latestPerPair], samma urval som Beslut):
     * kvitteringen ska gälla det senaste rådet, inte ett halvår gammalt.
     */
    private fun recordedFeeSwitches(history: List<SuggestionRecord>, isin: String?): Map<String, RecordedFeeSwitch> {
        if (isin == null) return emptyMap()
        return CheaperAlternatives.latestPerPair(history)
            .filter { it.sellIsin == isin }
            .associate { it.buyIsin to RecordedFeeSwitch(recordId = it.id, followed = it.followed == true) }
    }

    /**
     * Avgiftsjämförelsen utan de alternativ användaren avstått från (HEM-14), och hur många som
     * dolts. Ett kvitterat alternativ döljs aldrig, samma regel som på Beslut ([SuggestionDismissals.hides]).
     */
    private fun FeeComparisonUiState?.withoutDismissed(
        isin: String?,
        recorded: Map<String, RecordedFeeSwitch>,
        activeDismissals: Set<SuggestionDismissals.Key>
    ): Pair<FeeComparisonUiState?, Int> {
        if (this !is FeeComparisonUiState.Found || isin == null || activeDismissals.isEmpty()) return this to 0
        val (visible, dismissed) = alternatives.partition { alternative ->
            val candidateIsin = alternative.candidate.isin
            !SuggestionDismissals.hides(activeDismissals, SuggestionKind.FEE, isin, candidateIsin, recorded[candidateIsin]?.followed == true)
        }
        return FeeComparisonUiState.Found(visible.toImmutableList()) to dismissed.size
    }

    /**
     * Hämtar kurshistoriken för den föreslagna fonden [isin] till jämförelsediagrammet (ANA-11)
     * — anropas när ett förslag fälls ut, inte när kortet öppnas: varje kandidat kostar ett
     * nätverksanrop mot ISIN-källkedjan (TP-14), och de flesta förslag fälls aldrig ut. Ett
     * redan hämtat (eller pågående) ISIN hämtas aldrig om under skärmens livstid.
     */
    fun onSuggestionExpanded(isin: String) {
        if (comparisons.value.containsKey(isin)) return
        comparisons.value = comparisons.value + (isin to ComparisonUiState.Loading)
        viewModelScope.launch {
            val today = LocalDate.now()
            val points = fundPriceRepository.historyForIsin(isin, today.minusYears(COMPARISON_HISTORY_YEARS), today)
            comparisons.value = comparisons.value + (
                isin to if (points.isEmpty()) {
                    ComparisonUiState.Unavailable
                } else {
                    ComparisonUiState.Ready(points.map { it.epochDay to it.nav }.sortedBy { it.first }.toImmutableList())
                }
                )
        }
    }

    /**
     * Bygger [FundAnalysisCalc.Analysis] för den visade fonden. Null om fonden inte är ett
     * kvarvarande innehav (inga andelar kvar, eller aldrig köpt — bara bevakad). Övriga
     * innehavs tremånadershistorik läses ur den lokala kurscachen (Room), ingen ny
     * nätverksuppdatering — samma princip som POR-5/HEM-1 (`fundPriceRepository.priceHistory`).
     */
    private suspend fun buildAnalysis(
        holdings: List<Holding>,
        holding: Holding?,
        latestPrices: Map<String, FundPrice>,
        thisFundHistory: List<FundPrice>
    ): FundAnalysisCalc.Analysis? {
        if (holding == null) return null
        val firstPurchase = holding.firstPurchaseEpochDay?.let(LocalDate::ofEpochDay) ?: return null

        val portfolioTotalValue = PortfolioCalc.totalValue(PortfolioCalc.withCurrentValue(holdings, latestPrices))

        val today = LocalDate.now()
        val since = today.minusMonths(OTHER_HOLDINGS_HISTORY_LOOKBACK_MONTHS)
        val otherHistories = holdings
            .map { it.fund.fundId }
            .filter { it != fundId }
            .associateWith { otherId -> fundPriceRepository.priceHistory(otherId, since.toEpochDay(), today.toEpochDay()) }
        val otherAverage = FundAnalysisCalc.averageThreeMonthReturn(today, otherHistories)

        return FundAnalysisCalc.analyze(
            today = today,
            holding = holding,
            priceHistory = thisFundHistory,
            firstPurchaseDate = firstPurchase,
            portfolioTotalValue = portfolioTotalValue,
            otherHoldingsAverageThreeMonthReturn = otherAverage
        )
    }

    // Engångsuppdatering per öppning av skärmen — samma "inte en ny bakgrundsjobb"-princip
    // som PortfoljViewModel (issue #6): har fonden ISIN, hämta hela historiken sedan första
    // köpet; annars fondlista-källan med samma horisont. Repositoryt avgör själv om det räcker
    // med ett kort färskt fönster eller om historiken behöver backfillas (TP-18), så den
    // tidigare gaten "bara om cachen är helt tom" är utbytt mot den delade staleness-regeln
    // (isPriceStale, TP-17, regel 4) — en fond med inaktuell men befintlig kurs uppdateras nu
    // när man öppnar den, i stället för att stå kvar på gammal data till nästa worker-körning.

    /** "Försök igen" efter ett läsfel (NFR-6): läser om fonden, kurserna och planen från början. */
    fun retry() {
        retrySignal.retry()
    }

    /** Pågående kurshämtning — se [refresh]. */
    private var priceRefreshJob: Job? = null

    /**
     * Hämtar fondens kurs och historik. Körs en gång när skärmen öppnas och sedan **på begäran**
     * när användaren drar ned (UI-11) — samma kodväg, så en manuell uppdatering hämtar exakt det
     * skärmöppningen hämtar och inte en approximation av det.
     *
     * En pågående hämtning återanvänds i stället för att avbrytas: dragningen ska inte kunna
     * kasta bort en backfill som redan är halvvägs, och två parallella hämtningar av samma fond
     * vore två anrop mot en odokumenterad källa (TP-14) för ett svar.
     */
    fun refresh() {
        if (priceRefreshJob?.isActive == true) return
        priceRefreshJob = viewModelScope.launch {
            // `finally`, inte en rad efter hämtningen: en fond vars källa svarar med fel skulle
            // annars bli stående med en snurra som aldrig slutar (NAV-6).
            priceRefreshing.value = true
            try {
                // Ett läsfel här visas redan av uiState (NFR-6) — hämtningen ger då upp i stället
                // för att krascha skärmen, och den cachade historiken står kvar.
                suspendRunCatching {
                    val fund = transactionRepository.observeFunds().first().firstOrNull { it.fundId == fundId }
                    val since = earliestPurchase.first()
                    // Lokal kopia: Fund ligger i :core, och Kotlin smart-castar inte en publik egenskap från en annan modul.
                    val isin = fund?.isin

                    if (isin != null && since != null) {
                        fundPriceRepository.refreshSince(fundId, isin, since)
                    } else if (fundPriceRepository.isPriceStale(fundId)) {
                        fundPriceRepository.refresh(fundId, since)
                    }

                    if (fund != null && fund.isin == null) {
                        suggestedIsin.value = fundPriceRepository.suggestIsin(fund.name)
                    }
                }
            } finally {
                priceRefreshing.value = false
            }
        }
    }

    init {
        refresh()

        // Billigare-alternativ-jämförelsen (ANA-9) är också en engångsuppdatering — den kan
        // kosta flera nätverksanrop (budgeterad köpbarhetsverifiering) och ska inte köras om
        // för varje NAV-tick. Kortet visas bara för ett kvarvarande innehav (analysis != null);
        // saknar det ISIN eller känt värde visas kortet ändå, men som "kunde inte jämföras"
        // (ANA-4-principen) — det ska inte se ut som att inget hände.
        viewModelScope.launch {
            // Vänta på ett tillstånd som faktiskt är *avgjort*. `first { !it.loading }` räckte
            // inte: för ett innehav vars kurscache ännu är tom är analysen null i den första
            // icke-laddande emissionen, och jobbet gav upp för gott — kortet dök aldrig upp
            // trots att refreshSince i samma init fyllde cachen sekunder senare. Saknas
            // netInvested är fonden inget kvarvarande innehav, och då finns inget att jämföra.
            // Ett läsfel är också avgjort: då finns inget innehav att jämföra (NFR-6).
            val loaded = uiState.first { it.error != null || (!it.loading && (it.content.analysis != null || it.content.netInvested == null)) }.content
            if (loaded.analysis == null) return@launch

            val isin = loaded.isin
            val holdingValue = loaded.holdingValue
            if (isin == null || holdingValue == null) {
                feeComparisonState.value = FeeComparisonUiState.Unavailable
                return@launch
            }

            feeComparisonState.value = FeeComparisonUiState.Loading
            val alternatives = fundMetadataRepository.suggestCheaperAlternatives(isin, holdingValue)
            feeComparisonState.value = when {
                alternatives == null -> FeeComparisonUiState.Unavailable
                alternatives.isEmpty() -> FeeComparisonUiState.NoCheaperAlternative
                else -> FeeComparisonUiState.Found(alternatives.toImmutableList())
            }
        }
    }

    /** Sparar ett användarbekräftat/rättat ISIN och hämtar direkt historik sedan första köpet med det. */
    fun onIsinConfirmed(isin: String) {
        val trimmed = isin.trim().uppercase()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val fund = transactionRepository.observeFunds().first().firstOrNull { it.fundId == fundId } ?: return@launch
            transactionRepository.upsertFund(fund.copy(isin = trimmed))
            suggestedIsin.value = null
            earliestPurchase.first()?.let { since -> fundPriceRepository.refreshSince(fundId, trimmed, since) }
        }
    }

    /** Skapar ViewModeln för en given fond (assisted injection, `hiltViewModel` med `creationCallback`). */
    @AssistedFactory
    interface Factory {
        fun create(fundId: String): FondDetaljViewModel
    }
}
