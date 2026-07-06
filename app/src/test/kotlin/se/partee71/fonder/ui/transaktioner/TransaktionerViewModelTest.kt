package se.partee71.fonder.ui.transaktioner

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
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

@OptIn(ExperimentalCoroutinesApi::class)
class TransaktionerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val fund = Fund(fundId = "SHB0000442", name = "Handelsbanken Amerika Småbolag Tema")
    private val funds = MutableStateFlow(listOf(fund))
    private val transactions = MutableStateFlow<List<Transaction>>(emptyList())
    private val deletedIds = mutableListOf<Long>()

    private val fakeRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = funds
        override fun observeTransactions(): Flow<List<Transaction>> = transactions
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = transactions
        override suspend fun upsertFund(fund: Fund) {}
        override suspend fun addTransaction(tx: Transaction): Long = 0
        override suspend fun deleteTransaction(id: Long) {
            deletedIds.add(id)
        }
        override suspend fun clearAll() {}
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `rader far fondnamn uppslaget fran fundId`() = runTest(dispatcher) {
        transactions.value = listOf(
            Transaction(id = 1, fundId = fund.fundId, type = TransactionType.KOP, epochDay = 100, shares = 2.0, pricePerShare = 150.0),
        )
        val vm = TransaktionerViewModel(fakeRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(1, state.rows.size)
            assertEquals(fund.name, state.rows.first().fundName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `okand fond faller tillbaka pa fundId som namn`() = runTest(dispatcher) {
        transactions.value = listOf(
            Transaction(id = 1, fundId = "OKAND", type = TransactionType.KOP, epochDay = 100, shares = 1.0, pricePerShare = 10.0),
        )
        val vm = TransaktionerViewModel(fakeRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals("OKAND", state.rows.first().fundName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleteTransaction anropar repository`() = runTest(dispatcher) {
        val vm = TransaktionerViewModel(fakeRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.deleteTransaction(42)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(42L in deletedIds)
    }
}
