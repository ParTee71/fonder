package se.partee71.fonder.ui.salda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.SoldFundResult
import se.partee71.fonder.domain.usecase.FifoResultCalc
import javax.inject.Inject

data class SaldaFonderUiState(
    val loading: Boolean = true,
    val results: List<SoldFundResult> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && results.isEmpty()
}

/**
 * Sålda fonder: en rad per fond som haft minst en säljtransaktion, med ackumulerat
 * realiserat resultat beräknat med FIFO ([FifoResultCalc]) — issue #10.
 */
@HiltViewModel
class SaldaFonderViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    val uiState: StateFlow<SaldaFonderUiState> =
        combine(transactionRepository.observeFunds(), transactionRepository.observeTransactions()) { funds, transactions ->
            SaldaFonderUiState(loading = false, results = FifoResultCalc.computeSoldFundResults(funds, transactions))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SaldaFonderUiState(),
        )
}
