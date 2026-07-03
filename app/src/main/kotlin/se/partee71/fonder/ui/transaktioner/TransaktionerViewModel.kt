package se.partee71.fonder.ui.transaktioner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Transaction
import javax.inject.Inject

/** En transaktionsrad med fondnamnet redan uppslaget för visning. */
data class TransaktionRad(
    val transaction: Transaction,
    val fundName: String,
)

data class TransaktionerUiState(
    val loading: Boolean = true,
    val rows: List<TransaktionRad> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && rows.isEmpty()
}

@HiltViewModel
class TransaktionerViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val uiState: StateFlow<TransaktionerUiState> =
        combine(repository.observeTransactions(), repository.observeFunds()) { transactions, funds ->
            val namesByFundId = funds.associateBy({ it.fundId }, { it.name })
            val rows = transactions.map { tx ->
                TransaktionRad(transaction = tx, fundName = namesByFundId[tx.fundId] ?: tx.fundId)
            }
            TransaktionerUiState(loading = false, rows = rows)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TransaktionerUiState(),
        )

    fun deleteTransaction(id: Long) {
        viewModelScope.launch { repository.deleteTransaction(id) }
    }
}
