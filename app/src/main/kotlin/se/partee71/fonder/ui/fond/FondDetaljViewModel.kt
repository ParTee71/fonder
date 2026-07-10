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
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.Transaction
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
    /** Nyckeltal och säljsignaler (issue #16) — null om fonden inte är ett kvarvarande innehav. */
    val analysis: FundAnalysisCalc.Analysis? = null,
) {
    val isEmpty: Boolean get() = !loading && prices.isEmpty()
}

private data class Snapshot(
    val funds: List<Fund>,
    val transactions: List<Transaction>,
    val since: LocalDate?,
    val suggestedIsin: String?,
)

/** Hur långt tillbaka övriga innehavs kurshistorik hämtas ur cachen för momentum-signalen (S3, ANA-2) — tre månader plus en buffert för helger/röda dagar utan NAV. */
private const val OTHER_HOLDINGS_HISTORY_LOOKBACK_MONTHS = 4L

/**
 * Fonddetalj — kurshistorik sedan första köpet (i diagram och tabell), inte bara senaste
 * året (issue #7-uppföljning: se KRAVLISTA TP-14). Har fonden ett känt ISIN (`Fund.isin`)
 * hämtas historiken från en ISIN-baserad källkedja (Avanza m.fl.) utöver Handelsbankens
 * fasta 5-årsfönster, eftersom äldre köp annars aldrig kan täckas fullt ut. Saknas ISIN
 * föreslås ett via namnsökning — användaren bekräftar/rättar innan det sparas (samma
 * "föreslå men kräv bekräftelse"-princip som importflödet, IMP-2).
 *
 * Bygger även [FundAnalysisCalc]-nyckeltal/säljsignaler (issue #16) — kräver, utöver den
 * redan reaktivt laddade kurshistoriken för den här fonden, portföljens totala värde (för
 * portföljandelen) och övriga innehavs tremånadershistorik (för momentum-signalen S3), som
 * hämtas ur den lokala cachen (ingen extra nätverksuppdatering).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FondDetaljViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
) : ViewModel() {

    private val fundId: String = checkNotNull(savedStateHandle["fundId"])
    private val suggestedIsin = MutableStateFlow<String?>(null)

    private val earliestPurchase: Flow<LocalDate?> =
        transactionRepository.observeTransactionsForFund(fundId)
            .map { transactions -> transactions.minOfOrNull { it.epochDay }?.let(LocalDate::ofEpochDay) }

    val uiState: StateFlow<FondDetaljUiState> = combine(
        transactionRepository.observeFunds(),
        transactionRepository.observeTransactions(),
        earliestPurchase,
        suggestedIsin,
    ) { funds, transactions, since, suggested -> Snapshot(funds, transactions, since, suggested) }
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
            FondDetaljUiState(
                loading = false,
                fundName = fund?.name,
                isin = fund?.isin,
                suggestedIsin = snapshot.suggestedIsin,
                prices = history.sortedByDescending { it.epochDay },
                firstPurchaseEpochDay = holding?.firstPurchaseEpochDay,
                netInvested = holding?.netInvested,
                analysis = buildAnalysis(holdings, holding, latestPrices, history),
            )
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
    // köpet; annars samma fallback som tidigare (5-årscachen, bara om helt tom).
    init {
        viewModelScope.launch {
            val fund = transactionRepository.observeFunds().first().firstOrNull { it.fundId == fundId }
            val since = earliestPurchase.first()

            if (fund?.isin != null && since != null) {
                fundPriceRepository.refreshSince(fundId, fund.isin, since)
            } else if (fundPriceRepository.latestPrice(fundId) == null) {
                fundPriceRepository.refresh(fundId)
            }

            if (fund != null && fund.isin == null) {
                suggestedIsin.value = fundPriceRepository.suggestIsin(fund.name)
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
