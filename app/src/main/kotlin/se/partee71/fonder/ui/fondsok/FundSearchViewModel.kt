package se.partee71.fonder.ui.fondsok

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCompany
import se.partee71.fonder.domain.usecase.FundNameKey
import javax.inject.Inject

data class FundSearchUiState(
    val loading: Boolean = true,
    val query: String = "",
    val companies: List<FundCompany> = emptyList(),
    val selectedCompany: FundCompany? = null,
    val results: List<Fund> = emptyList(),
    /**
     * Fonder som redan bevakas — både de som lagts till i den här sessionen och de som redan
     * fanns i portföljen. Tidigare bara det förstnämnda, så en fond man redan ägde visades utan
     * bock och med "Lägg till" kvar (issue #78).
     */
    val addedFundIds: Set<String> = emptySet(),
    /**
     * Sant om fondkatalogen inte gick att hämta. Skilt från "sökningen gav inga träffar" —
     * annars ser ett nätverksfel ut som ett tomt sökresultat (issue #78).
     */
    val loadFailed: Boolean = false,
    /**
     * Sant medan en **användarstyrd** omhämtning av katalogen pågår (UI-11). Skild från
     * [loading]: den första laddningen har inget innehåll att visa och får sin egen
     * förloppsindikator, medan en dragning sker ovanpå en lista som redan står där.
     */
    val refreshing: Boolean = false,
    /**
     * Risknivå (1–7, TP-21) per fond i [results] (UI-10, issue #85). Nyckel: `Fund.fundId`.
     * Katalogens träffar saknar ISIN (`HandelsbankenHtmlParser.parseFundCatalog`), så nivån slås
     * upp på normaliserat fondnamn mot **cachad** metadata
     * ([FundMetadataRepository.cachedRiskByFundName]) — en fond som inte hunnit hamna i cachen
     * saknas i kartan och visas som okänd risk, aldrig gissad.
     */
    val riskLevels: Map<String, Int> = emptyMap(),
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
    private val fundMetadataRepository: FundMetadataRepository,
) : ViewModel() {

    private val allFunds = MutableStateFlow<List<Fund>>(emptyList())
    private val visibleFunds = MutableStateFlow<List<Fund>>(emptyList())
    private val companies = MutableStateFlow<List<FundCompany>>(emptyList())
    private val selectedCompany = MutableStateFlow<FundCompany?>(null)
    private val query = MutableStateFlow("")
    /**
     * Katalogens hämtningstillstånd i **ett** värde, inte tre flöden: de tre flaggorna beskriver
     * samma hämtning, och delade de på var sitt `MutableStateFlow` gick det att observera ett
     * mellanläge som aldrig funnits — "klar, utan fel, utan träffar" mellan att felflaggan och
     * `loading` skrevs, eftersom `combine` cachar varje gren för sig och emitterar så fort
     * någon av dem ändras. Ett sådant mellanläge är exakt vad UI:t inte får rita.
     */
    private data class CatalogState(
        val loading: Boolean = true,
        val loadFailed: Boolean = false,
        /** Bara den användarstyrda omhämtningen (UI-11), inte första laddningen. */
        val refreshing: Boolean = false,
    )

    private val catalogState = MutableStateFlow(CatalogState())

    /** Pågående katalogshämtning — se [refresh]. */
    private var catalogJob: Job? = null

    /** Fonder tillagda i den här sessionen — slås ihop med de redan bevakade, se [uiState]. */
    private val addedThisSession = MutableStateFlow<Set<String>>(emptySet())

    /** Redan bevakade fonder, reaktivt ur Room — så en fond man äger aldrig erbjuds som ny. */
    private val trackedFundIds: Flow<Set<String>> =
        transactionRepository.observeFunds().map { funds -> funds.mapTo(mutableSetOf()) { it.fundId } }

    /** Ett bolagsbyte i taget — ett snabbt byte ska inte kunna skriva över resultatet av ett senare. */
    private var companyLoadJob: Job? = null

    /**
     * Risknivå per normaliserat fondnamn ur metadatacachen (UI-10) — läses **en gång** per
     * ViewModel-livstid, inte per sökning: kartan är en ren cache-läsning som inte ändras av att
     * användaren skriver i sökfältet, och att läsa om den vid varje tangenttryck vore en
     * databasfråga per tecken.
     */
    private val riskByNameKey = MutableStateFlow<Map<String, Int>>(emptyMap())

    val uiState: StateFlow<FundSearchUiState> =
        combine(visibleFunds, companies, selectedCompany, query, catalogState) { funds, companies, selected, query, catalog ->
            val filtered = if (query.isBlank()) {
                funds
            } else {
                funds.filter { it.name.contains(query, ignoreCase = true) }
            }
            FundSearchUiState(
                loading = catalog.loading,
                loadFailed = catalog.loadFailed,
                refreshing = catalog.refreshing,
                query = query,
                companies = companies,
                selectedCompany = selected,
                results = filtered,
            )
        }.combine(riskByNameKey) { state, riskByName ->
            state.copy(
                riskLevels = state.results.mapNotNull { fund ->
                    val risk = riskByName[FundNameKey.of(fund.name)] ?: return@mapNotNull null
                    fund.fundId to risk
                }.toMap(),
            )
        }.combine(combine(addedThisSession, trackedFundIds) { added, tracked -> added + tracked }) { state, added ->
            state.copy(addedFundIds = added)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FundSearchUiState(),
        )

    /**
     * Hämtar fondkatalogen. Körs en gång när skärmen öppnas och sedan **på begäran** när
     * användaren drar ned (UI-11) — samma kodväg, så en dragning ger exakt det skärmöppningen
     * ger. Ett nätverksfel är den vanligaste anledningen att vilja dra: [FundSearchUiState.loadFailed] visas som
     * ett eget tillstånd (issue #78) och dragningen är vägen ur det.
     *
     * En pågående hämtning återanvänds i stället för att avbrytas — katalogen är ~125 kB, och
     * två parallella hämtningar vore två svar på samma fråga.
     */
    fun refresh() {
        if (catalogJob?.isActive == true) return
        catalogJob = viewModelScope.launch {
            catalogState.update { it.copy(refreshing = true) }
            try {
                // Null = hämtningen misslyckades; vyn visar tomt sökresultat i stället för att
                // krascha (samma degraderingsprincip som onCompanySelected nedan).
                val catalog = fundPriceRepository.fetchFundCatalog()
                allFunds.value = catalog?.funds.orEmpty()
                visibleFunds.value = catalog?.funds.orEmpty()
                companies.value = catalog?.companies.orEmpty()
                // Ett enda skriv: "klar" och "gick det bra" hör ihop och får aldrig observeras
                // var för sig, se CatalogState.
                catalogState.update { it.copy(loading = false, loadFailed = catalog == null) }
                catalog?.companies?.firstOrNull { it.id == FundCompany.HANDELSBANKEN_ID }
                    ?.let(::onCompanySelected)
            } finally {
                catalogState.update { it.copy(refreshing = false) }
            }
        }
    }

    init {
        viewModelScope.launch { riskByNameKey.value = fundMetadataRepository.cachedRiskByFundName() }
        refresh()
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
            catalogState.update { it.copy(loading = false) }
            return
        }
        // Sätts synkront, före coroutinen: annars finns ett observerbart mellanläge med det
        // *nya* bolaget men det *gamla* bolagets fondlista — i UI:t en blink av fel lista.
        catalogState.update { it.copy(loading = true) }
        companyLoadJob = viewModelScope.launch {
            // Null = hämtningen misslyckades; behåll den lista som redan visas i stället för
            // att tömma vyn (samma degraderingsprincip som kurscachen, POR-3).
            fundPriceRepository.fetchFundsForCompany(company.id)?.let { visibleFunds.value = it }
            catalogState.update { it.copy(loading = false) }
        }
    }

    fun addFund(fund: Fund) {
        viewModelScope.launch {
            // Källan bär fondens ISIN på dess egen sida (TP-18) — hämta det direkt i stället
            // för att låta fonden vara utan tills ett namnbaserat förslag bekräftats i
            // Fonddetalj (TP-14/NAV-2). Bästa-försök: misslyckas uppslaget läggs fonden till ändå.
            val isin = fund.isin ?: fundPriceRepository.lookupIsin(fund.fundId)
            transactionRepository.upsertFund(fund.copy(isin = isin))
            addedThisSession.value = addedThisSession.value + fund.fundId
        }
    }
}
