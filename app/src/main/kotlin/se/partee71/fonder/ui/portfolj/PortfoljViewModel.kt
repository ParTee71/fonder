package se.partee71.fonder.ui.portfolj

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.usecase.PortfolioCalc
import javax.inject.Inject

data class PortfoljUiState(
    val loading: Boolean = true,
    val holdings: List<Holding> = emptyList(),
    val totalInvested: Double = 0.0,
) {
    val isEmpty: Boolean get() = !loading && holdings.isEmpty()
}

@HiltViewModel
class PortfoljViewModel @Inject constructor(
    repository: TransactionRepository,
) : ViewModel() {

    val uiState: StateFlow<PortfoljUiState> =
        combine(repository.observeFunds(), repository.observeTransactions()) { funds, transactions ->
            val holdings = PortfolioCalc.computeHoldings(funds, transactions)
            PortfoljUiState(
                loading = false,
                holdings = holdings,
                totalInvested = PortfolioCalc.totalInvested(holdings),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PortfoljUiState(),
        )
}
