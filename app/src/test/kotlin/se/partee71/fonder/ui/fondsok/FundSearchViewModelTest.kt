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

@OptIn(ExperimentalCoroutinesApi::class)
class FundSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val handelsbanken = FundCompany(id = FundCompany.HANDELSBANKEN_ID, name = "Handelsbanken")
    private val aberdeen = FundCompany(id = "1101", name = "Aberdeen Global Services S.A.")

    private val handelsbankenFond = Fund(fundId = "SHB0000442", name = "Handelsbanken Amerika Småbolag Tema")
    private val handelsbankenFond2 = Fund(fundId = "SHB0000627", name = "Handelsbanken Aktiv 50 (A14 NOK)")
    private val extern = Fund(fundId = "0P000083RV", name = "Aberdeen Global – Asia Pacific Equity Fund")

    private val catalog = FundCatalog(
        companies = listOf(handelsbanken, aberdeen),
        funds = listOf(handelsbankenFond, handelsbankenFond2, extern),
    )

    private val addedFunds = mutableListOf<Fund>()

    private val fakePriceRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? = null
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = flowOf(emptyMap())
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long) = emptyList<FundPrice>()
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())
        override suspend fun refresh(fundId: String) {}
        override suspend fun refreshSince(fundId: String, isin: String, since: java.time.LocalDate) {}
        override suspend fun suggestIsin(fundName: String): String? = null
        override suspend fun findFundByIsin(isin: String): Fund? = null
        override suspend fun fetchFundCatalog(): FundCatalog = catalog
    }

    private val fakeTransactionRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = MutableStateFlow(emptyList())
        override fun observeTransactions(): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override suspend fun upsertFund(fund: Fund) { addedFunds.add(fund) }
        override suspend fun addTransaction(tx: Transaction): Long = 0
        override suspend fun deleteTransaction(id: Long) {}
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `Handelsbanken ar forvalt bolag och filtrerar bort externa fonder`() = runTest(dispatcher) {
        val vm = FundSearchViewModel(fakePriceRepo, fakeTransactionRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertEquals(handelsbanken, state.selectedCompany)
            assertEquals(2, state.results.size)
            assertTrue(state.results.none { it.fundId == extern.fundId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `byte av fondbolag filtrerar om resultaten`() = runTest(dispatcher) {
        val vm = FundSearchViewModel(fakePriceRepo, fakeTransactionRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            vm.onCompanySelected(aberdeen)
            state = awaitItem()
            assertEquals(1, state.results.size)
            assertEquals(extern.fundId, state.results.first().fundId)

            vm.onCompanySelected(null)
            state = awaitItem()
            assertEquals(3, state.results.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sok filtrerar inom valt fondbolag pa namn`() = runTest(dispatcher) {
        val vm = FundSearchViewModel(fakePriceRepo, fakeTransactionRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            vm.onQueryChange("Amerika")
            state = awaitItem()
            assertEquals(1, state.results.size)
            assertEquals(handelsbankenFond.fundId, state.results.first().fundId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `addFund lagger till fonden och markerar den som tillagd`() = runTest(dispatcher) {
        val vm = FundSearchViewModel(fakePriceRepo, fakeTransactionRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            vm.addFund(handelsbankenFond)
            state = awaitItem()
            assertTrue(handelsbankenFond.fundId in state.addedFundIds)
            assertEquals(listOf(handelsbankenFond), addedFunds)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
