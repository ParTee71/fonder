package se.partee71.fonder.ui.fondsok

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import javax.inject.Inject

data class FundSearchUiState(
    val loading: Boolean = true,
    val query: String = "",
    val results: List<Fund> = emptyList(),
    val addedFundIds: Set<String> = emptySet(),
)

/**
 * Sök bland Handelsbankens fonder och lägg till dem i bevakningen. Katalogen hämtas en
 * gång (ett nätverksanrop) och filtreras sedan lokalt per sökterm.
 */
@HiltViewModel
class FundSearchViewModel @Inject constructor(
    private val fundPriceRepository: FundPriceRepository,
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    private val catalog = MutableStateFlow<List<Fund>>(emptyList())
    private val query = MutableStateFlow("")
    private val loading = MutableStateFlow(true)
    private val addedFundIds = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<FundSearchUiState> =
        combine(catalog, query, loading, addedFundIds) { catalog, query, loading, added ->
            val filtered = if (query.isBlank()) {
                catalog
            } else {
                catalog.filter { it.name.contains(query, ignoreCase = true) }
            }
            FundSearchUiState(loading = loading, query = query, results = filtered, addedFundIds = added)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FundSearchUiState(),
        )

    init {
        viewModelScope.launch {
            catalog.value = fundPriceRepository.fetchFundCatalog()
            loading.value = false
        }
    }

    fun onQueryChange(newQuery: String) {
        query.value = newQuery
    }

    fun addFund(fund: Fund) {
        viewModelScope.launch {
            transactionRepository.upsertFund(fund)
            addedFundIds.value = addedFundIds.value + fund.fundId
        }
    }
}
