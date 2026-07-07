package se.partee71.fonder.ui.portfolj

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

@OptIn(ExperimentalCoroutinesApi::class)
class PortfoljViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val funds = MutableStateFlow<List<Fund>>(emptyList())
    private val transactions = MutableStateFlow<List<Transaction>>(emptyList())

    private val fakeTransactionRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = funds
        override fun observeTransactions(): Flow<List<Transaction>> = transactions
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = transactions
        override suspend fun upsertFund(fund: Fund) {}
        override suspend fun addTransaction(tx: Transaction): Long = 0
        override suspend fun deleteTransaction(id: Long) {}
        override suspend fun clearAll() {}
    }

    private val latestPrices = MutableStateFlow<Map<String, FundPrice>>(emptyMap())

    private val fakeFundPriceRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? = latestPrices.value[fundId]
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = latestPrices
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice> = emptyList()
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())
        override suspend fun refresh(fundId: String) {}
        override suspend fun refreshSince(fundId: String, isin: String, since: java.time.LocalDate) {}
        override suspend fun suggestIsin(fundName: String): String? = null
        override suspend fun findFundByIsin(isin: String): Fund? = null
        override suspend fun fetchFundCatalog(): FundCatalog = FundCatalog(emptyList(), emptyList())
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tomt tillstand nar inga transaktioner finns`() = runTest(dispatcher) {
        val vm = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo)
        vm.uiState.test {
            // Initialt: laddar
            assertTrue(awaitItem().loading)
            // Efter combine: tomt och inte laddar
            val loaded = awaitItem()
            assertFalse(loaded.loading)
            assertTrue(loaded.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `holdings utan kand kurs visar netInvested och null gainLoss`() = runTest(dispatcher) {
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = 1, shares = 2.0, pricePerShare = 150.0),
        )

        val vm = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(1, state.holdings.size)
            assertEquals(300.0, state.totalInvested, 1e-9)
            assertEquals(0.0, state.totalValue, 1e-9)
            assertNull(state.totalGainLossFraction)
            assertNull(state.holdings.first().currentValue)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `helt avsald fond visas inte i portfoljen`() = runTest(dispatcher) {
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = 1, shares = 2.0, pricePerShare = 150.0),
            Transaction(fundId = fond.fundId, type = TransactionType.SALJ, epochDay = 2, shares = 2.0, pricePerShare = 160.0),
        )

        val vm = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `holdings uppdateras reaktivt nar en ny kurs blir kand`() = runTest(dispatcher) {
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = 1, shares = 2.0, pricePerShare = 150.0),
        )

        val vm = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertNull(state.holdings.first().currentValue)

            latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = 5, nav = 200.0))

            val updated = awaitItem()
            assertEquals(400.0, updated.totalValue, 1e-9)
            assertEquals(100.0, updated.totalGainLoss, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
