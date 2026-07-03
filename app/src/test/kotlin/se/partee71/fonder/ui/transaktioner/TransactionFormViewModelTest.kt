package se.partee71.fonder.ui.transaktioner

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Transaction

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionFormViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val fund = Fund(fundId = "SHB0000442", name = "Handelsbanken Amerika Småbolag Tema")
    private val funds = MutableStateFlow(listOf(fund))
    private val addedTransactions = mutableListOf<Transaction>()
    private var latestPriceValue: FundPrice? = null

    private val fakeTransactionRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = funds
        override fun observeTransactions(): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override suspend fun upsertFund(fund: Fund) {}
        override suspend fun addTransaction(tx: Transaction): Long {
            addedTransactions.add(tx)
            return 1
        }
        override suspend fun deleteTransaction(id: Long) {}
    }

    private val fakePriceRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? = latestPriceValue
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = flowOf(emptyMap())
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long) = emptyList<FundPrice>()
        override suspend fun refresh(fundId: String) {}
        override suspend fun fetchFundCatalog(): FundCatalog = FundCatalog(emptyList(), emptyList())
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `formularet ar ogiltigt tills fond antal och kurs fyllts i`() = runTest(dispatcher) {
        val vm = TransactionFormViewModel(fakeTransactionRepo, fakePriceRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.funds.isEmpty()) state = awaitItem()
            assertFalse(state.valid)

            vm.onFundSelected(fund)
            state = awaitItem()
            assertFalse(state.valid)

            vm.onSharesTextChange("2")
            state = awaitItem()
            vm.onPriceTextChange("150")
            state = awaitItem()
            assertTrue(state.valid)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onFundSelected forifyller kurs fran senaste kanda pris`() = runTest(dispatcher) {
        latestPriceValue = FundPrice(fundId = fund.fundId, epochDay = 100, nav = 175.5, currency = "SEK")
        val vm = TransactionFormViewModel(fakeTransactionRepo, fakePriceRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.funds.isEmpty()) state = awaitItem()

            vm.onFundSelected(fund)
            state = awaitItem()
            while (state.priceText.isEmpty()) state = awaitItem()
            assertEquals("175.5", state.priceText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save sparar transaktion och markerar formularet som sparat`() = runTest(dispatcher) {
        val vm = TransactionFormViewModel(fakeTransactionRepo, fakePriceRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.funds.isEmpty()) state = awaitItem()

            vm.onFundSelected(fund)
            state = awaitItem()
            vm.onSharesTextChange("2")
            state = awaitItem()
            vm.onPriceTextChange("150")
            state = awaitItem()
            assertTrue(state.valid)

            vm.save()
            state = awaitItem()
            while (!state.saved) state = awaitItem()

            assertEquals(1, addedTransactions.size)
            assertEquals(fund.fundId, addedTransactions.first().fundId)
            assertEquals(2.0, addedTransactions.first().shares, 1e-9)
            assertEquals(150.0, addedTransactions.first().pricePerShare, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
