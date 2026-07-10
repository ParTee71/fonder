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
import se.partee71.fonder.domain.model.Holding
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
) {
    val isEmpty: Boolean get() = !loading && holdings.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortfoljViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
) : ViewModel() {

    private val baseHoldings: Flow<List<Holding>> =
        combine(transactionRepository.observeFunds(), transactionRepository.observeTransactions()) { funds, transactions ->
            PortfolioCalc.computeHoldings(funds, transactions)
        }

    val uiState: StateFlow<PortfoljUiState> =
        baseHoldings.flatMapLatest { holdings ->
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
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PortfoljUiState(),
        )

    // Engångsuppdatering per fond utan cachad kurs, eller vars cachade kurs är äldre än idag
    // (issue #18 — annars visar dag/vecka en falsk "0" i stället för att bli färsk så snart
    // källan har ett nyare pris; ingen daglig WorkManager-körning har nödvändigtvis hunnit
    // ännu). Håll enkel: ett refresh-anrop per fund och ViewModel-livstid, inte en ny
    // bakgrundsjobb-mekanism (se issue #6).
    private val refreshedFundIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            transactionRepository.observeFunds().collect { funds ->
                funds.forEach { fund ->
                    val latest = fundPriceRepository.latestPrice(fund.fundId)
                    val isStale = latest == null || latest.epochDay < LocalDate.now().toEpochDay()
                    if (refreshedFundIds.add(fund.fundId) && isStale) {
                        // Fonder med känt ISIN (t.ex. matchade via findFundByIsin, TP-14)
                        // saknar Handelsbanken-FundId — refresh() hittar dem aldrig eftersom
                        // den nycklas på FundId. Samma gren som FondDetaljViewModel/
                        // ImportHoldingsViewModel använder.
                        val isin = fund.isin
                        if (isin != null) {
                            val since = transactionRepository.observeTransactionsForFund(fund.fundId).first()
                                .minOfOrNull { it.epochDay }
                                ?.let(LocalDate::ofEpochDay)
                                ?: LocalDate.now().minusYears(5)
                            fundPriceRepository.refreshSince(fund.fundId, isin, since)
                        } else {
                            fundPriceRepository.refresh(fund.fundId)
                        }
                    }
                }
            }
        }
    }
}
