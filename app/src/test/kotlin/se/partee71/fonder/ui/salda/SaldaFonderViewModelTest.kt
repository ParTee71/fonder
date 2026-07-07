package se.partee71.fonder.ui.salda

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

@OptIn(ExperimentalCoroutinesApi::class)
class SaldaFonderViewModelTest {

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

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tomt tillstand utan salj`() = runTest(dispatcher) {
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = 1, shares = 2.0, pricePerShare = 100.0),
        )

        val vm = SaldaFonderViewModel(fakeTransactionRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fond med salj visar ackumulerat realiserat resultat`() = runTest(dispatcher) {
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = 1, shares = 10.0, pricePerShare = 100.0),
            Transaction(fundId = fond.fundId, type = TransactionType.SALJ, epochDay = 2, shares = 4.0, pricePerShare = 120.0),
        )

        val vm = SaldaFonderViewModel(fakeTransactionRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(1, state.results.size)
            val result = state.results.first()
            assertEquals(4.0, result.sharesSold, 1e-9)
            assertEquals(80.0, result.realizedGainLoss ?: -1.0, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `salj utan tillrackliga kop ger okant resultat`() = runTest(dispatcher) {
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.SALJ, epochDay = 1, shares = 4.0, pricePerShare = 120.0),
        )

        val vm = SaldaFonderViewModel(fakeTransactionRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertNull(state.results.first().realizedGainLoss)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
