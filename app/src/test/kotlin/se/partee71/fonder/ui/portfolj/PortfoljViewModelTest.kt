package se.partee71.fonder.ui.portfolj

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

@OptIn(ExperimentalCoroutinesApi::class)
class PortfoljViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val funds = MutableStateFlow<List<Fund>>(emptyList())
    private val transactions = MutableStateFlow<List<Transaction>>(emptyList())

    private val fakeRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = funds
        override fun observeTransactions(): Flow<List<Transaction>> = transactions
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = transactions
        override suspend fun upsertFund(fund: Fund) {}
        override suspend fun addTransaction(tx: Transaction): Long = 0
        override suspend fun deleteTransaction(id: Long) {}
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tomt tillstand nar inga transaktioner finns`() = runTest(dispatcher) {
        val vm = PortfoljViewModel(fakeRepo)
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
    fun `holdings och total berakas fran transaktioner`() = runTest(dispatcher) {
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = 1, shares = 2.0, pricePerShare = 150.0),
        )

        val vm = PortfoljViewModel(fakeRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(1, state.holdings.size)
            assertEquals(300.0, state.totalInvested, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
