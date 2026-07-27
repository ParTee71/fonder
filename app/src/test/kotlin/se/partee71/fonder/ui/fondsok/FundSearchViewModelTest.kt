package se.partee71.fonder.ui.fondsok

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundCompany
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Transaction
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class FundSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val handelsbanken = FundCompany(id = FundCompany.HANDELSBANKEN_ID, name = "Handelsbanken")
    private val aberdeen = FundCompany(id = "1101", name = "Aberdeen Global Services S.A.")

    private val handelsbankenFond = Fund(fundId = "SHB0000442", name = "Handelsbanken Amerika Småbolag Tema")
    private val handelsbankenFond2 = Fund(fundId = "SHB0000627", name = "Handelsbanken Aktiv 50 (A14 NOK)")

    // Fondnamnet börjar varken med "Aberdeen" eller med SHB-prefix — den gamla
    // FundCompanyMatcher-heuristiken hade missat den helt. Källans company-filter vet bättre.
    private val extern = Fund(fundId = "0P000083RV", name = "Global Asia Pacific Equity Fund")

    private val catalog = FundCatalog(
        companies = listOf(handelsbanken, aberdeen),
        funds = listOf(handelsbankenFond, handelsbankenFond2, extern),
    )

    private val fundsByCompany = mapOf(
        FundCompany.HANDELSBANKEN_ID to listOf(handelsbankenFond, handelsbankenFond2),
        aberdeen.id to listOf(extern),
    )

    private val addedFunds = mutableListOf<Fund>()

    private open inner class FakePriceRepo : FundPriceRepository {
        val companyCalls = mutableListOf<String>()
        override suspend fun latestPrice(fundId: String): FundPrice? = null
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = flowOf(emptyMap())
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long) = emptyList<FundPrice>()
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())
        override suspend fun refresh(fundId: String, since: LocalDate?) = true
        override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate) = true
        override suspend fun suggestIsin(fundName: String): String? = null
        override suspend fun findFundByIsin(isin: String): Fund? = null
        override suspend fun lookupIsin(fundId: String): String? = "SE000$fundId"
        override suspend fun fetchFundCatalog(): FundCatalog = catalog
        override suspend fun fetchFundsForCompany(companyId: String): List<Fund>? {
            companyCalls.add(companyId)
            return fundsByCompany[companyId]
        }
    }

    private val fakePriceRepo = FakePriceRepo()

    private val fakeTransactionRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = MutableStateFlow(emptyList())
        override fun observeTransactions(): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override suspend fun upsertFund(fund: Fund) { addedFunds.add(fund) }
        override suspend fun addTransaction(tx: Transaction): Long = 0
        override suspend fun deleteTransaction(id: Long) {}
        override suspend fun clearAll() {}
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `Handelsbanken ar forvalt bolag och listan kommer fran kallans bolagsfilter`() = runTest(dispatcher) {
        val vm = FundSearchViewModel(fakePriceRepo, fakeTransactionRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.selectedCompany == null) state = awaitItem()

            assertEquals(handelsbanken, state.selectedCompany)
            assertEquals(listOf(FundCompany.HANDELSBANKEN_ID), fakePriceRepo.companyCalls)
            assertEquals(2, state.results.size)
            assertTrue(state.results.none { it.fundId == extern.fundId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `byte av fondbolag hamtar bolagets fonder fran kallan`() = runTest(dispatcher) {
        val vm = FundSearchViewModel(fakePriceRepo, fakeTransactionRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.selectedCompany == null) state = awaitItem()

            vm.onCompanySelected(aberdeen)
            state = awaitItem()
            while (state.loading || state.selectedCompany != aberdeen) state = awaitItem()
            assertTrue(aberdeen.id in fakePriceRepo.companyCalls)
            assertEquals(listOf(extern.fundId), state.results.map { it.fundId })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Alla fondbolag visar hela katalogen utan nytt anrop`() = runTest(dispatcher) {
        val vm = FundSearchViewModel(fakePriceRepo, fakeTransactionRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.selectedCompany == null) state = awaitItem()
            val callsBefore = fakePriceRepo.companyCalls.size

            vm.onCompanySelected(null)
            state = awaitItem()
            while (state.selectedCompany != null) state = awaitItem()

            assertEquals(3, state.results.size)
            assertNull(state.selectedCompany)
            assertEquals(callsBefore, fakePriceRepo.companyCalls.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `misslyckad bolagshamtning behaller foregaende lista`() = runTest(dispatcher) {
        val failing = object : FakePriceRepo() {
            override suspend fun fetchFundsForCompany(companyId: String): List<Fund>? =
                if (companyId == FundCompany.HANDELSBANKEN_ID) super.fetchFundsForCompany(companyId) else null
        }
        val vm = FundSearchViewModel(failing, fakeTransactionRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.selectedCompany == null) state = awaitItem()
            assertEquals(2, state.results.size)

            vm.onCompanySelected(aberdeen)
            state = awaitItem()
            while (state.loading || state.selectedCompany != aberdeen) state = awaitItem()

            // Tom vy vore fel — vi vet inte att bolaget saknar fonder, bara att hämtningen sprack.
            assertEquals(2, state.results.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sok filtrerar inom valt fondbolag pa namn`() = runTest(dispatcher) {
        val vm = FundSearchViewModel(fakePriceRepo, fakeTransactionRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.selectedCompany == null) state = awaitItem()

            vm.onQueryChange("Amerika")
            state = awaitItem()
            while (state.query != "Amerika") state = awaitItem()
            assertEquals(1, state.results.size)
            assertEquals(handelsbankenFond.fundId, state.results.first().fundId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `addFund lagger till fonden med isin fran kallan och markerar den som tillagd`() = runTest(dispatcher) {
        val vm = FundSearchViewModel(fakePriceRepo, fakeTransactionRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.selectedCompany == null) state = awaitItem()

            vm.addFund(handelsbankenFond)
            state = awaitItem()
            while (handelsbankenFond.fundId !in state.addedFundIds) state = awaitItem()

            // ISIN hämtas från fondsidan direkt vid tillägg (TP-18) — inget namnbaserat
            // förslag behöver bekräftas i Fonddetalj först.
            assertEquals(listOf(handelsbankenFond.copy(isin = "SE000SHB0000442")), addedFunds)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
