package se.partee71.fonder.ui.transaktioner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Transaction
import javax.inject.Inject

data class TransaktionerUiState(
    val loading: Boolean = true,
    val transactions: List<Transaction> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && transactions.isEmpty()
}

@HiltViewModel
class TransaktionerViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val uiState: StateFlow<TransaktionerUiState> =
        repository.observeTransactions()
            .map { TransaktionerUiState(loading = false, transactions = it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = TransaktionerUiState(),
            )
}
