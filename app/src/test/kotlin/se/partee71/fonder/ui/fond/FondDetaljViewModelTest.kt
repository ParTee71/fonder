package se.partee71.fonder.ui.fond

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class FondDetaljViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val fund = Fund(fundId = "SHB0000442", name = "Fond A")
    private val funds = MutableStateFlow(listOf(fund))
    private val transactionsForFund = MutableStateFlow<List<Transaction>>(emptyList())
    private val history = MutableStateFlow<List<FundPrice>>(emptyList())
    private var latestPriceValue: FundPrice? = null
    private var refreshCalledFor: String? = null
    private var refreshSinceCall: Triple<String, String, LocalDate>? = null
    private var suggestIsinCalledWith: String? = null
    private var suggestIsinReturn: String? = null
    private var upsertedFund: Fund? = null
    private var capturedFromEpochDay: Long? = null

    private fun transaction(epochDay: Long) =
        Transaction(fundId = fund.fundId, type = TransactionType.KOP, epochDay = epochDay, shares = 1.0, pricePerShare = 100.0)

    private val fakeTransactionRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = funds
        override fun observeTransactions(): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = transactionsForFund
        override suspend fun upsertFund(fund: Fund) {
            upsertedFund = fund
            funds.value = funds.value.map { if (it.fundId == fund.fundId) fund else it }
        }
        override suspend fun addTransaction(tx: Transaction): Long = 0
        override suspend fun deleteTransaction(id: Long) {}
    }

    private val fakePriceRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? = latestPriceValue
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> =
            MutableStateFlow(emptyMap())
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long) = history.value
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> {
            capturedFromEpochDay = fromEpochDay
            return history
        }
        override suspend fun refresh(fundId: String) {
            refreshCalledFor = fundId
        }
        override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate) {
            refreshSinceCall = Triple(fundId, isin, since)
        }
        override suspend fun suggestIsin(fundName: String): String? {
            suggestIsinCalledWith = fundName
            return suggestIsinReturn
        }
        override suspend fun findFundByIsin(isin: String): Fund? = null
        override suspend fun fetchFundCatalog(): FundCatalog = FundCatalog(emptyList(), emptyList())
    }

    private fun viewModel() =
        FondDetaljViewModel(SavedStateHandle(mapOf("fundId" to fund.fundId)), fakeTransactionRepo, fakePriceRepo)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tomt tillstand nar ingen kurshistorik finns`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.isEmpty)
            assertEquals("Fond A", state.fundName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `visar kurshistorik sorterad fallande pa datum`() = runTest(dispatcher) {
        latestPriceValue = FundPrice(fundId = fund.fundId, epochDay = 200, nav = 145.0, currency = "SEK")
        history.value = listOf(
            FundPrice(fundId = fund.fundId, epochDay = 100, nav = 140.0, currency = "SEK"),
            FundPrice(fundId = fund.fundId, epochDay = 200, nav = 145.0, currency = "SEK"),
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(listOf(200L, 100L), state.prices.map { it.epochDay })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `triggar engangsuppdatering nar ingen kurs ar cachad`() = runTest(dispatcher) {
        viewModel()
        advanceUntilIdle()
        assertEquals(fund.fundId, refreshCalledFor)
    }

    @Test
    fun `triggar inte uppdatering nar en kurs redan ar cachad`() = runTest(dispatcher) {
        latestPriceValue = FundPrice(fundId = fund.fundId, epochDay = 100, nav = 140.0, currency = "SEK")
        viewModel()
        advanceUntilIdle()
        assertEquals(null, refreshCalledFor)
    }

    @Test
    fun `hamtar historik sedan forsta kopet via ISIN nar fonden har ett`() = runTest(dispatcher) {
        val fundWithIsin = fund.copy(isin = "SE0004297927")
        funds.value = listOf(fundWithIsin)
        transactionsForFund.value = listOf(transaction(epochDay = 100), transaction(epochDay = 50))

        viewModel()
        advanceUntilIdle()

        assertEquals(Triple(fund.fundId, "SE0004297927", LocalDate.ofEpochDay(50)), refreshSinceCall)
        assertEquals(null, refreshCalledFor)
    }

    @Test
    fun `vidgar visad kurshistorik till forsta kopet, inte bara ett ar tillbaka`() = runTest(dispatcher) {
        transactionsForFund.value = listOf(transaction(epochDay = 50))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(50L, capturedFromEpochDay)
    }

    @Test
    fun `foreslar isin nar fonden saknar det`() = runTest(dispatcher) {
        suggestIsinReturn = "SE0004297927"

        val vm = viewModel()
        advanceUntilIdle()
        assertEquals("Fond A", suggestIsinCalledWith)

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals("SE0004297927", state.suggestedIsin)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `foreslar inget isin nar fonden redan har ett`() = runTest(dispatcher) {
        funds.value = listOf(fund.copy(isin = "SE0004297927"))
        viewModel()
        advanceUntilIdle()
        assertEquals(null, suggestIsinCalledWith)
    }

    @Test
    fun `onIsinConfirmed sparar isin normaliserat och hamtar historik direkt`() = runTest(dispatcher) {
        transactionsForFund.value = listOf(transaction(epochDay = 50))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onIsinConfirmed(" se0004297927 ")
        advanceUntilIdle()

        assertEquals("SE0004297927", upsertedFund?.isin)
        assertEquals(Triple(fund.fundId, "SE0004297927", LocalDate.ofEpochDay(50)), refreshSinceCall)
    }

    @Test
    fun `onIsinConfirmed med tomt varde gor ingenting`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onIsinConfirmed("   ")
        advanceUntilIdle()

        assertNull(upsertedFund)
    }
}
