package se.partee71.fonder.ui.fondsok

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Transaction

@OptIn(ExperimentalCoroutinesApi::class)
class FundSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val catalog = listOf(
        Fund(fundId = "SHB0000442", name = "Handelsbanken Amerika Småbolag Tema"),
        Fund(fundId = "SHB0000627", name = "Handelsbanken Aktiv 50 (A14 NOK)"),
    )

    private val addedFunds = mutableListOf<Fund>()

    private val fakePriceRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? = null
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long) = emptyList<FundPrice>()
        override suspend fun refresh(fundId: String) {}
        override suspend fun fetchFundCatalog(): List<Fund> = catalog
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
    fun `katalogen laddas och kan filtreras pa namn`() = runTest(dispatcher) {
        val vm = FundSearchViewModel(fakePriceRepo, fakeTransactionRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(2, state.results.size)

            vm.onQueryChange("Amerika")
            state = awaitItem()
            assertEquals(1, state.results.size)
            assertEquals("SHB0000442", state.results.first().fundId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `addFund lagger till fonden och markerar den som tillagd`() = runTest(dispatcher) {
        val vm = FundSearchViewModel(fakePriceRepo, fakeTransactionRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            vm.addFund(catalog.first())
            state = awaitItem()
            assertTrue("SHB0000442" in state.addedFundIds)
            assertEquals(listOf(catalog.first()), addedFunds)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
