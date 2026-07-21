package se.partee71.fonder.ui.portfolj

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import se.partee71.fonder.data.repository.isPriceStale
import se.partee71.fonder.data.repository.refreshFund
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.PortfolioCalc
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import java.time.LocalDate
import javax.inject.Inject

data class PortfoljUiState(
    val loading: Boolean = true,
    val holdings: List<Holding> = emptyList(),
    val totalInvested: Double = 0.0,
    val totalValue: Double = 0.0,
    val totalGainLoss: Double = 0.0,
    val totalGainLossFraction: Double? = null,
    /** Dag/vecka/månads-förändring per fond, se issue #14 (POR-5). Nyckel: `Fund.fundId`. */
    val performance: Map<String, PortfolioPerformanceCalc.HoldingPerformance> = emptyMap(),
    /** Äldsta NAV-datumet bland innehav med känt värde, för "per <datum>" bredvid totalen (POR-7, issue #27). */
    val navEpochDay: Long? = null,
    /** Säljsignal-status och ev. vinstsignal per innehav (ANA-3/ANA-8, POR-8, issue #26). Nyckel: `Fund.fundId`. */
    val analysis: Map<String, FundAnalysisCalc.Analysis> = emptyMap(),
) {
    val isEmpty: Boolean get() = !loading && holdings.isEmpty()
}

/** Hur långt tillbaka ett innehavs kurshistorik hämtas för analysen (issue #26) om inget köp finns (bör inte hända för ett verkligt innehav). Samma princip som `HemViewModel`. */
private const val ANALYSIS_FALLBACK_LOOKBACK_YEARS = 1L

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortfoljViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
) : ViewModel() {

    private val baseHoldings: Flow<Pair<List<Holding>, List<Transaction>>> =
        combine(transactionRepository.observeFunds(), transactionRepository.observeTransactions()) { funds, transactions ->
            PortfolioCalc.computeHoldings(funds, transactions) to transactions
        }

    val uiState: StateFlow<PortfoljUiState> =
        baseHoldings.flatMapLatest { (holdings, transactions) ->
            val fundIds = holdings.map { it.fund.fundId }
            fundPriceRepository.observeLatestPrices(fundIds).map { prices ->
                val enriched = PortfolioCalc.withCurrentValue(holdings, prices)
                val today = LocalDate.now()
                val performance = enriched.associate { holding ->
                    val history = fundPriceRepository.priceHistory(
                        fundId = holding.fund.fundId,
                        fromEpochDay = today.minusDays(PortfolioPerformanceCalc.HISTORY_LOOKBACK_DAYS).toEpochDay(),
                        toEpochDay = today.toEpochDay(),
                    )
                    holding.fund.fundId to PortfolioPerformanceCalc.holdingPerformance(holding, today, history)
                }
                PortfoljUiState(
                    loading = false,
                    holdings = enriched,
                    totalInvested = PortfolioCalc.totalInvested(enriched),
                    totalValue = PortfolioCalc.totalValue(enriched),
                    totalGainLoss = PortfolioCalc.totalGainLoss(enriched),
                    totalGainLossFraction = PortfolioCalc.totalGainLossFraction(enriched),
                    performance = performance,
                    navEpochDay = PortfolioCalc.oldestKnownNavEpochDay(enriched),
                    analysis = buildAnalysis(enriched, transactions, today),
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PortfoljUiState(),
        )

    /**
     * Analyserar varje innehav (ANA-3/ANA-8, POR-8, issue #26) — samma princip som
     * [se.partee71.fonder.ui.hem.HemViewModel.buildAnalysisSummary], men returnerar hela
     * analysen per innehav i stället för en summering, eftersom Portfölj visar signalen på
     * varje enskild rad. Övrig kurshistorik hämtas ur den lokala cachen (Room), ingen ny
     * nätverksuppdatering.
     */
    private suspend fun buildAnalysis(
        enriched: List<Holding>,
        transactions: List<Transaction>,
        today: LocalDate,
    ): Map<String, FundAnalysisCalc.Analysis> {
        if (enriched.isEmpty()) return emptyMap()

        val firstPurchaseByFund = transactions
            .groupBy { it.fundId }
            .mapValues { (_, txs) -> LocalDate.ofEpochDay(txs.minOf { it.epochDay }) }
        val portfolioTotalValue = PortfolioCalc.totalValue(enriched)

        val historyByFundId = enriched.associate { holding ->
            val since = firstPurchaseByFund[holding.fund.fundId] ?: today.minusYears(ANALYSIS_FALLBACK_LOOKBACK_YEARS)
            holding.fund.fundId to fundPriceRepository.priceHistory(holding.fund.fundId, since.toEpochDay(), today.toEpochDay())
        }

        return enriched.mapNotNull { holding ->
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
            holding.fund.fundId to analysis
        }.toMap()
    }

    // Engångsuppdatering per fond utan cachad kurs, eller vars cachade kurs är inaktuell
    // (TP-17 — så dag/vecka/månad räknas på så färsk NAV som källan har, utan att vänta på
    // nästa dagliga WorkManager-körning). Håll enkel: ett refresh-anrop per fund och
    // ViewModel-livstid, inte en ny bakgrundsjobb-mekanism (se issue #6).
    private val refreshedFundIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            transactionRepository.observeFunds().collect { funds ->
                funds.forEach { fund ->
                    if (refreshedFundIds.add(fund.fundId) && fundPriceRepository.isPriceStale(fund.fundId)) {
                        val since = transactionRepository.observeTransactionsForFund(fund.fundId).first()
                            .minOfOrNull { it.epochDay }
                            ?.let(LocalDate::ofEpochDay)
                            ?: LocalDate.now().minusYears(5)
                        fundPriceRepository.refreshFund(fund, since)
                    }
                }
            }
        }
    }
}
