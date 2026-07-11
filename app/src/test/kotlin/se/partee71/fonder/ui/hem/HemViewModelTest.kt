package se.partee71.fonder.ui.hem

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
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class HemViewModelTest {

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
    private var priceHistoryByFundId: Map<String, List<FundPrice>> = emptyMap()

    private val fakeFundPriceRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? = latestPrices.value[fundId]
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = latestPrices
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice> =
            priceHistoryByFundId[fundId].orEmpty().filter { it.epochDay in fromEpochDay..toEpochDay }
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())
        override suspend fun refresh(fundId: String) = true
        override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate) = true
        override suspend fun suggestIsin(fundName: String): String? = null
        override suspend fun findFundByIsin(isin: String): Fund? = null
        override suspend fun fetchFundCatalog(): FundCatalog = FundCatalog(emptyList(), emptyList())
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tomt tillstand nar ingen portfolj finns`() = runTest(dispatcher) {
        val vm = HemViewModel(fakeTransactionRepo, fakeFundPriceRepo)
        vm.uiState.test {
            assertTrue(awaitItem().loading)
            val loaded = awaitItem()
            assertFalse(loaded.loading)
            assertTrue(loaded.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `visar total varde vinst och dag vecka manad`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        priceHistoryByFundId = mapOf(
            fond.fundId to listOf(
                FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0),
                FundPrice(fundId = fond.fundId, epochDay = today.minusDays(1).toEpochDay(), nav = 110.0),
                FundPrice(fundId = fond.fundId, epochDay = today.minusDays(7).toEpochDay(), nav = 100.0),
                FundPrice(fundId = fond.fundId, epochDay = today.minusDays(30).toEpochDay(), nav = 80.0),
            ),
        )

        val vm = HemViewModel(fakeTransactionRepo, fakeFundPriceRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertFalse(state.isEmpty) // innehavet finns, bara kursen saknas ännu (samma fallback som POR-3)
            // ingen kand kurs an -> ingen periodberakning möjlig för portföljen
            assertEquals(PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory, state.performance.day)

            latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))

            val updated = awaitItem()
            assertFalse(updated.isEmpty)
            assertEquals(1200.0, updated.totalValue, 1e-9)
            assertEquals(200.0, updated.totalGainLoss, 1e-9)
            val day = updated.performance.day as PortfolioPerformanceCalc.PortfolioPeriodResult.Available
            val week = updated.performance.week as PortfolioPerformanceCalc.PortfolioPeriodResult.Available
            val month = updated.performance.month as PortfolioPerformanceCalc.PortfolioPeriodResult.Available
            assertEquals(100.0, day.amount, 1e-9)
            assertEquals(200.0, week.amount, 1e-9)
            assertEquals(400.0, month.amount, 1e-9)
            assertFalse(day.partial)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `navEpochDay speglar prisets NAV-datum (POR-7)`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.minusDays(1).toEpochDay(), nav = 120.0))

        val vm = HemViewModel(fakeTransactionRepo, fakeFundPriceRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertEquals(today.minusDays(1).toEpochDay(), state.navEpochDay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `nyligen tillagd fond utan tillrackligt historik ger InsufficientHistory for vecka och manad`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusDays(2).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        priceHistoryByFundId = mapOf(
            fond.fundId to listOf(FundPrice(fundId = fond.fundId, epochDay = today.minusDays(2).toEpochDay(), nav = 100.0)),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 105.0))

        val vm = HemViewModel(fakeTransactionRepo, fakeFundPriceRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertEquals(PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory, state.performance.week)
            assertEquals(PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory, state.performance.month)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `analysis-summering ar tom nar inga fonder ar flaggade`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(2).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        // Flat kurshistorik — inga signaler triggade.
        priceHistoryByFundId = mapOf(
            fond.fundId to (0..730L step 14).map { daysAgo -> FundPrice(fundId = fond.fundId, epochDay = today.minusDays(daysAgo).toEpochDay(), nav = 100.0) },
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 100.0))

        val vm = HemViewModel(fakeTransactionRepo, fakeFundPriceRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(1, state.analysisSummary.gronCount)
            assertTrue(state.analysisSummary.flagged.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flaggar en fond som ligger under 200-dagars snitt`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(2).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        // NAV var högre förr — dagens kurs (100, vid daysAgo=0) hamnar under både
        // 52-veckorstoppen och 200-dagarssnittet (samma fixturprincip som FundAnalysisCalcTest).
        priceHistoryByFundId = mapOf(
            fond.fundId to (0..730L step 5).map { daysAgo ->
                FundPrice(fundId = fond.fundId, epochDay = today.minusDays(daysAgo).toEpochDay(), nav = 100.0 + daysAgo * 0.05)
            },
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 100.0))

        val vm = HemViewModel(fakeTransactionRepo, fakeFundPriceRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(1, state.analysisSummary.flagged.size)
            assertEquals(fond.fundId, state.analysisSummary.flagged.first().fund.fundId)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
