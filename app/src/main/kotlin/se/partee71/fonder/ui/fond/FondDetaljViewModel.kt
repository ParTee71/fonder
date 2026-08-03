package se.partee71.fonder.ui.fond

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.data.repository.isPriceStale
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.PortfolioCalc
import java.time.LocalDate
import javax.inject.Inject

data class FondDetaljUiState(
    val loading: Boolean = true,
    val fundName: String? = null,
    val isin: String? = null,
    val suggestedIsin: String? = null,
    val prices: List<FundPrice> = emptyList(),
    /** Första köp-datum och kvarvarande (FIFO) inköpsvärde — null om fonden inte är ett kvarvarande innehav (POR-6, issue #18). */
    val firstPurchaseEpochDay: Long? = null,
    val netInvested: Double? = null,
    /** Köpdagar (epochDay) för den här fonden — markeras i diagrammet när de ingår i den
     *  valda perioden, se [se.partee71.fonder.ui.diagram.FundLineChart] (issue #55). */
    val purchaseEpochDays: List<Long> = emptyList(),
    /** Nyckeltal och säljsignaler (issue #16) — null om fonden inte är ett kvarvarande innehav. */
    val analysis: FundAnalysisCalc.Analysis? = null,
    /** Innehavets nuvarande värde (netShares × senaste NAV) — null om okänt/inte ett innehav. Underlag för [feeComparison]. */
    val holdingValue: Double? = null,
    /** Billigare-alternativ-jämförelse (ANA-9, issue #59). Null = fonden är inte ett kvarvarande innehav, inget kort visas. */
    val feeComparison: FeeComparisonUiState? = null,
) {
    val isEmpty: Boolean get() = !loading && prices.isEmpty()
}

/**
 * Resultatet av att jämföra innehavets avgift mot billigare, likvärdiga alternativ (ANA-9,
 * issue #59) — appens första rådgivande funktion (ANA-3:s tidigare "aldrig
 * rådgivning"-princip är struken, se KRAVLISTA). Beräknas en gång per skärmöppning
 * ("engångsuppdatering", samma princip som prisuppdateringen nedan), inte reaktivt vid varje
 * NAV-tick, eftersom den kan kosta flera nätverksanrop (budgeterad köpbarhetsverifiering).
 */
sealed interface FeeComparisonUiState {
    /** Jämförelsen pågår — flera nätverksanrop kan behövas. */
    data object Loading : FeeComparisonUiState

    /** Fonden saknar ISIN, finns inte i källans universum, eller saknar känd avgift (ANA-4-principen). */
    data object Unavailable : FeeComparisonUiState

    /** Inga alternativ med identisk exponering och lägre avgift hittades — fonden är redan bland de billigaste i sin kategori. */
    data object NoCheaperAlternative : FeeComparisonUiState

    data class Found(val alternatives: List<FeeComparisonCalc.Alternative>) : FeeComparisonUiState
}

private data class Snapshot(
    val funds: List<Fund>,
    val transactions: List<Transaction>,
    val since: LocalDate?,
    val suggestedIsin: String?,
    val purchaseEpochDays: List<Long>,
)

/** Hur långt tillbaka övriga innehavs kurshistorik hämtas ur cachen för momentum-signalen (S3, ANA-2) — tre månader plus en buffert för helger/röda dagar utan NAV. */
private const val OTHER_HOLDINGS_HISTORY_LOOKBACK_MONTHS = 4L

/**
 * Fonddetalj — kurshistorik sedan första köpet (i diagram och tabell), inte bara senaste
 * året (issue #7-uppföljning: se KRAVLISTA TP-14/TP-18). Har fonden ett känt ISIN
 * (`Fund.isin`) provas fondlista-källan först och en ISIN-baserad källkedja (Avanza m.fl.)
 * som reserv. Saknas ISIN föreslås ett via namnsökning — användaren bekräftar/rättar innan
 * det sparas (samma "föreslå men kräv bekräftelse"-princip som importflödet, IMP-2).
 *
 * Bygger även [FundAnalysisCalc]-nyckeltal/säljsignaler (issue #16) — kräver, utöver den
 * redan reaktivt laddade kurshistoriken för den här fonden, portföljens totala värde (för
 * portföljandelen) och övriga innehavs tremånadershistorik (för momentum-signalen S3), som
 * hämtas ur den lokala cachen (ingen extra nätverksuppdatering).
 *
 * Föreslår dessutom billigare, likvärdiga alternativ (ANA-9, issue #59) för ett kvarvarande
 * innehav — se [FeeComparisonUiState].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FondDetaljViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val fundMetadataRepository: FundMetadataRepository,
) : ViewModel() {

    private val fundId: String = checkNotNull(savedStateHandle["fundId"])
    private val suggestedIsin = MutableStateFlow<String?>(null)
    private val feeComparisonState = MutableStateFlow<FeeComparisonUiState?>(null)

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
        purchaseEpochDays,
    ) { funds, transactions, since, suggested, purchases -> Snapshot(funds, transactions, since, suggested, purchases) }
        .flatMapLatest { snapshot ->
            val fundIds = snapshot.funds.map { it.fundId }
            val priceHistoryFlow = fundPriceRepository.observePriceHistory(
                fundId = fundId,
                fromEpochDay = (snapshot.since ?: LocalDate.now().minusYears(1)).toEpochDay(),
                toEpochDay = LocalDate.now().toEpochDay(),
            )
            combine(fundPriceRepository.observeLatestPrices(fundIds), priceHistoryFlow) { latestPrices, history ->
                Triple(snapshot, latestPrices, history)
            }
        }
        .map { (snapshot, latestPrices, history) ->
            val fund = snapshot.funds.firstOrNull { it.fundId == fundId }
            val holdings = PortfolioCalc.computeHoldings(snapshot.funds, snapshot.transactions)
            val holding = holdings.firstOrNull { it.fund.fundId == fundId }
            val holdingValue = holding?.let { PortfolioCalc.withCurrentValue(listOf(it), latestPrices).first().currentValue }
            FondDetaljUiState(
                loading = false,
                fundName = fund?.name,
                isin = fund?.isin,
                suggestedIsin = snapshot.suggestedIsin,
                prices = history.sortedByDescending { it.epochDay },
                firstPurchaseEpochDay = holding?.firstPurchaseEpochDay,
                netInvested = holding?.netInvested,
                purchaseEpochDays = snapshot.purchaseEpochDays,
                analysis = buildAnalysis(holdings, holding, latestPrices, history),
                holdingValue = holdingValue,
            )
        }

    val uiState: StateFlow<FondDetaljUiState> = combine(baseUiState, feeComparisonState) { state, feeComparison ->
        state.copy(feeComparison = feeComparison)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FondDetaljUiState(),
    )

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
        thisFundHistory: List<FundPrice>,
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
            otherHoldingsAverageThreeMonthReturn = otherAverage,
        )
    }

    // Engångsuppdatering per öppning av skärmen — samma "inte en ny bakgrundsjobb"-princip
    // som PortfoljViewModel (issue #6): har fonden ISIN, hämta hela historiken sedan första
    // köpet; annars fondlista-källan med samma horisont. Repositoryt avgör själv om det räcker
    // med ett kort färskt fönster eller om historiken behöver backfillas (TP-18), så den
    // tidigare gaten "bara om cachen är helt tom" är utbytt mot den delade staleness-regeln
    // (isPriceStale, TP-17, regel 4) — en fond med inaktuell men befintlig kurs uppdateras nu
    // när man öppnar den, i stället för att stå kvar på gammal data till nästa worker-körning.
    init {
        viewModelScope.launch {
            val fund = transactionRepository.observeFunds().first().firstOrNull { it.fundId == fundId }
            val since = earliestPurchase.first()

            if (fund?.isin != null && since != null) {
                fundPriceRepository.refreshSince(fundId, fund.isin, since)
            } else if (fundPriceRepository.isPriceStale(fundId)) {
                fundPriceRepository.refresh(fundId, since)
            }

            if (fund != null && fund.isin == null) {
                suggestedIsin.value = fundPriceRepository.suggestIsin(fund.name)
            }
        }

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
            val loaded = uiState.first { !it.loading && (it.analysis != null || it.netInvested == null) }
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
                else -> FeeComparisonUiState.Found(alternatives)
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
}
