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
class SoldFundsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val fund = Fund(fundId = "SHB0000442", name = "Handelsbanken Sverige")
    private val funds = MutableStateFlow(listOf(fund))
    private val transactions = MutableStateFlow<List<Transaction>>(emptyList())

    private val fakeRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = funds
        override fun observeTransactions(): Flow<List<Transaction>> = transactions
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = transactions
        override suspend fun upsertFund(fund: Fund) {}
        override suspend fun addTransaction(tx: Transaction): Long = 0
        override suspend fun deleteTransaction(id: Long) {}
        override suspend fun clearAll() {}
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tom transaktionshistorik ger tomt tillstand`() = runTest(dispatcher) {
        val vm = SoldFundsViewModel(fakeRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `salj-transaktion ger en rad med fondnamn och realiserat resultat`() = runTest(dispatcher) {
        transactions.value = listOf(
            Transaction(id = 1, fundId = "SHB0000442", type = TransactionType.KOP, epochDay = 100, shares = 10.0, pricePerShare = 100.0),
            Transaction(id = 2, fundId = "SHB0000442", type = TransactionType.SALJ, epochDay = 200, shares = 10.0, pricePerShare = 150.0),
        )
        val vm = SoldFundsViewModel(fakeRepo)

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertEquals(1, state.rows.size)
            val row = state.rows.first()
            assertEquals("Handelsbanken Sverige", row.fundName)
            assertEquals(500.0, row.sale.realizedGain, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `okand fond faller tillbaka pa fundId som namn`() = runTest(dispatcher) {
        funds.value = emptyList()
        transactions.value = listOf(
            Transaction(id = 1, fundId = "SE0003653302", type = TransactionType.KOP, epochDay = 100, shares = 5.0, pricePerShare = 200.0),
            Transaction(id = 2, fundId = "SE0003653302", type = TransactionType.SALJ, epochDay = 200, shares = 5.0, pricePerShare = 210.0),
        )
        val vm = SoldFundsViewModel(fakeRepo)

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals("SE0003653302", state.rows.first().fundName)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
