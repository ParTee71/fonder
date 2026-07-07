package se.partee71.fonder.ui.transaktioner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.usecase.RealizedGainCalculator
import se.partee71.fonder.domain.usecase.RealizedSale
import javax.inject.Inject

/** En rad i sålda fonder-vyn — realiserat resultat med fondnamnet redan uppslaget. */
data class SoldFundRad(
    val sale: RealizedSale,
    val fundName: String,
)

data class SoldFundsUiState(
    val loading: Boolean = true,
    val rows: List<SoldFundRad> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && rows.isEmpty()
}

/**
 * Realiserat resultat per säljtransaktion (FIFO, se [RealizedGainCalculator], issue #10) —
 * en egen vy separat från Transaktioner, som bara visar orealiserad utveckling (POR-3).
 */
@HiltViewModel
class SoldFundsViewModel @Inject constructor(
    private val repository: TransactionRepository,
) : ViewModel() {

    val uiState: StateFlow<SoldFundsUiState> =
        combine(repository.observeTransactions(), repository.observeFunds()) { transactions, funds ->
            val namesByFundId = funds.associateBy({ it.fundId }, { it.name })
            val rows = RealizedGainCalculator.compute(transactions).map { sale ->
                SoldFundRad(sale = sale, fundName = namesByFundId[sale.fundId] ?: sale.fundId)
            }
            SoldFundsUiState(loading = false, rows = rows)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SoldFundsUiState(),
        )
}
