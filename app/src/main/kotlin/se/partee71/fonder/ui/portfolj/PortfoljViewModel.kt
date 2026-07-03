package se.partee71.fonder.ui.portfolj

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.usecase.PortfolioCalc
import javax.inject.Inject

data class PortfoljUiState(
    val loading: Boolean = true,
    val holdings: List<Holding> = emptyList(),
    val totalInvested: Double = 0.0,
    val totalValue: Double = 0.0,
    val totalGainLoss: Double = 0.0,
    val totalGainLossFraction: Double? = null,
) {
    val isEmpty: Boolean get() = !loading && holdings.isEmpty()
}

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
                PortfoljUiState(
                    loading = false,
                    holdings = enriched,
                    totalInvested = PortfolioCalc.totalInvested(enriched),
                    totalValue = PortfolioCalc.totalValue(enriched),
                    totalGainLoss = PortfolioCalc.totalGainLoss(enriched),
                    totalGainLossFraction = PortfolioCalc.totalGainLossFraction(enriched),
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PortfoljUiState(),
        )

    // Engångsuppdatering per fond utan cachad kurs (t.ex. nyss tillagd, ingen daglig
    // WorkManager-körning har hunnit ännu). Håll enkel: ett refresh-anrop per fund och
    // ViewModel-livstid, inte en ny bakgrundsjobb-mekanism (se issue #6).
    private val refreshedFundIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            transactionRepository.observeFunds().collect { funds ->
                funds.forEach { fund ->
                    if (refreshedFundIds.add(fund.fundId) && fundPriceRepository.latestPrice(fund.fundId) == null) {
                        fundPriceRepository.refresh(fund.fundId)
                    }
                }
            }
        }
    }
}
