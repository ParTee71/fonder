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
import se.partee71.fonder.domain.model.FundCompany
import se.partee71.fonder.domain.usecase.FundCompanyMatcher
import javax.inject.Inject

data class FundSearchUiState(
    val loading: Boolean = true,
    val query: String = "",
    val companies: List<FundCompany> = emptyList(),
    val selectedCompany: FundCompany? = null,
    val results: List<Fund> = emptyList(),
    val addedFundIds: Set<String> = emptySet(),
)

/**
 * Sök bland fonder — filtrerat per fondbolag (dropdown, se issue #3-uppföljning) — och lägg
 * till dem i bevakningen. Hela katalogen (fondbolag + fonder) hämtas en gång (ett
 * nätverksanrop) och filtreras sedan lokalt: sidans eget "Fondbolag"-filter visade sig inte
 * filtrera fondlistan i praktiken, så matchningen görs av [FundCompanyMatcher] i appen.
 */
@HiltViewModel
class FundSearchViewModel @Inject constructor(
    private val fundPriceRepository: FundPriceRepository,
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    private val allFunds = MutableStateFlow<List<Fund>>(emptyList())
    private val companies = MutableStateFlow<List<FundCompany>>(emptyList())
    private val selectedCompany = MutableStateFlow<FundCompany?>(null)
    private val query = MutableStateFlow("")
    private val loading = MutableStateFlow(true)
    private val addedFundIds = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<FundSearchUiState> =
        combine(allFunds, companies, selectedCompany, query, loading) { funds, companies, selected, query, loading ->
            val byCompany = if (selected == null) {
                funds
            } else {
                funds.filter { FundCompanyMatcher.matches(it, selected) }
            }
            val filtered = if (query.isBlank()) {
                byCompany
            } else {
                byCompany.filter { it.name.contains(query, ignoreCase = true) }
            }
            FundSearchUiState(
                loading = loading,
                query = query,
                companies = companies,
                selectedCompany = selected,
                results = filtered,
            )
        }.combine(addedFundIds) { state, added -> state.copy(addedFundIds = added) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = FundSearchUiState(),
            )

    init {
        viewModelScope.launch {
            val catalog = fundPriceRepository.fetchFundCatalog()
            allFunds.value = catalog.funds
            companies.value = catalog.companies
            selectedCompany.value = catalog.companies.firstOrNull { it.id == FundCompany.HANDELSBANKEN_ID }
            loading.value = false
        }
    }

    fun onQueryChange(newQuery: String) {
        query.value = newQuery
    }

    fun onCompanySelected(company: FundCompany?) {
        selectedCompany.value = company
    }

    fun addFund(fund: Fund) {
        viewModelScope.launch {
            transactionRepository.upsertFund(fund)
            addedFundIds.value = addedFundIds.value + fund.fundId
        }
    }
}
