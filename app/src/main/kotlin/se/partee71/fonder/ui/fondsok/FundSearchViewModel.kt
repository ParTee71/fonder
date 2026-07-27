package se.partee71.fonder.ui.fondsok

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
 * till dem i bevakningen.
 *
 * Både bolagslistan och fondlistan kommer från källan (KRAVLISTA TP-18, issue #37): utan
 * `company` levererar den **hela plattformens** katalog (1523 fonder i dagens data, mot 469
 * när anropet låstes till Handelsbanken), med `company` exakt det bolagets fonder. Tidigare
 * hämtades bara Handelsbankens lista en gång och filtrerades lokalt med en namn-/prefixgissning
 * (`FundCompanyMatcher.matches`, borttagen) — den gissningen behövs inte längre.
 */
@HiltViewModel
class FundSearchViewModel @Inject constructor(
    private val fundPriceRepository: FundPriceRepository,
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    private val allFunds = MutableStateFlow<List<Fund>>(emptyList())
    private val visibleFunds = MutableStateFlow<List<Fund>>(emptyList())
    private val companies = MutableStateFlow<List<FundCompany>>(emptyList())
    private val selectedCompany = MutableStateFlow<FundCompany?>(null)
    private val query = MutableStateFlow("")
    private val loading = MutableStateFlow(true)
    private val addedFundIds = MutableStateFlow<Set<String>>(emptySet())

    /** Ett bolagsbyte i taget — ett snabbt byte ska inte kunna skriva över resultatet av ett senare. */
    private var companyLoadJob: Job? = null

    val uiState: StateFlow<FundSearchUiState> =
        combine(visibleFunds, companies, selectedCompany, query, loading) { funds, companies, selected, query, loading ->
            val filtered = if (query.isBlank()) {
                funds
            } else {
                funds.filter { it.name.contains(query, ignoreCase = true) }
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
            visibleFunds.value = catalog.funds
            companies.value = catalog.companies
            loading.value = false
            catalog.companies.firstOrNull { it.id == FundCompany.HANDELSBANKEN_ID }
                ?.let(::onCompanySelected)
        }
    }

    fun onQueryChange(newQuery: String) {
        query.value = newQuery
    }

    /** [company] = null betyder "Alla fondbolag" — då visas hela katalogen igen, utan nytt anrop. */
    fun onCompanySelected(company: FundCompany?) {
        companyLoadJob?.cancel()
        selectedCompany.value = company
        if (company == null) {
            visibleFunds.value = allFunds.value
            loading.value = false
            return
        }
        // Sätts synkront, före coroutinen: annars finns ett observerbart mellanläge med det
        // *nya* bolaget men det *gamla* bolagets fondlista — i UI:t en blink av fel lista.
        loading.value = true
        companyLoadJob = viewModelScope.launch {
            // Null = hämtningen misslyckades; behåll den lista som redan visas i stället för
            // att tömma vyn (samma degraderingsprincip som kurscachen, POR-3).
            fundPriceRepository.fetchFundsForCompany(company.id)?.let { visibleFunds.value = it }
            loading.value = false
        }
    }

    fun addFund(fund: Fund) {
        viewModelScope.launch {
            // Källan bär fondens ISIN på dess egen sida (TP-18) — hämta det direkt i stället
            // för att låta fonden vara utan tills ett namnbaserat förslag bekräftats i
            // Fonddetalj (TP-14/NAV-2). Bästa-försök: misslyckas uppslaget läggs fonden till ändå.
            val isin = fund.isin ?: fundPriceRepository.lookupIsin(fund.fundId)
            transactionRepository.upsertFund(fund.copy(isin = isin))
            addedFundIds.value = addedFundIds.value + fund.fundId
        }
    }
}
