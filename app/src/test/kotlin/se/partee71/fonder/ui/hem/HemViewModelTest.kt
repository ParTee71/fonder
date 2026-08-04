package se.partee71.fonder.ui.hem

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class HemViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferencesRepository: PreferencesRepository

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
        override suspend fun refresh(fundId: String, since: LocalDate?) = true
        override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate) = true
        override suspend fun historyForIsin(isin: String, from: LocalDate, to: LocalDate): List<FundPrice> = emptyList()
        override suspend fun suggestIsin(fundName: String): String? = null
        override suspend fun findFundByIsin(isin: String): Fund? = null
        override suspend fun lookupIsin(fundId: String): String? = null
        override suspend fun fetchFundsForCompany(companyId: String): List<Fund>? = emptyList()
        override suspend fun fetchFundCatalog(): FundCatalog = FundCatalog(emptyList(), emptyList())
    }

    private var metadataByIsin: Map<String, FundMetadata> = emptyMap()
    private var metadataForCall: List<String>? = null

    private val fakeFundMetadataRepo = object : FundMetadataRepository {
        override suspend fun query(query: FundScreenQuery): List<FundMetadata> = emptyList()
        override suspend fun resolveHandelsbankenAvailability(isin: String): Boolean? = null
        override fun observeFilterVocabulary() = flowOf(FundFilterVocabulary())
        override suspend fun suggestCheaperAlternatives(isin: String, holdingValue: Double): List<FeeComparisonCalc.Alternative>? = null
        override suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata> {
            metadataForCall = isins
            return metadataByIsin.filterKeys { it in isins }
        }
        override suspend fun cachedRiskByFundName(): Map<String, Int> = emptyMap()
        override suspend fun cachedMetadataFor(isins: List<String>): Map<String, FundMetadata> = metadataByIsin.filterKeys { it in isins }
        override suspend fun knownRiskLevels(): List<Int> = emptyList()
        override suspend fun findSwitchCandidates(level: Int, excludeIsins: Set<String>): List<SwitchPlanCalc.Candidate> = emptyList()
    }

    private class FakeSuggestionRecordRepository : SuggestionRecordRepository {
        val records = MutableStateFlow<List<SuggestionRecord>>(emptyList())
        override fun observeLatestBatch(): Flow<List<SuggestionRecord>> = records.map { all ->
            // Speglar DAO:ns SQL: senaste dygnets senaste körning, sorterad på plats i planen.
            if (all.isEmpty()) return@map emptyList()
            val latestDay = all.maxOf { it.suggestedAtEpochDay }
            val sameDay = all.filter { it.suggestedAtEpochDay == latestDay }
            val latestBatch = sameDay.maxOf { it.batchEpochMillis }
            sameDay.filter { it.batchEpochMillis == latestBatch }.sortedBy { it.planIndex }
        }

        override fun observeHistory(): Flow<List<SuggestionRecord>> = records.map { all ->
            all.sortedWith(compareByDescending<SuggestionRecord> { it.suggestedAtEpochDay }.thenByDescending { it.batchEpochMillis }.thenBy { it.planIndex })
        }

        var prunedBefore: LocalDate? = null
        override suspend fun prune(today: LocalDate) { prunedBefore = today }
        override suspend fun hasRecordedToday(sellIsin: String, buyIsin: String, epochDay: Long): Boolean = false
        override suspend fun record(record: SuggestionRecord) { records.value = records.value + record }
        override suspend fun setFollowed(id: Long, followed: Boolean) {
            records.value = records.value.map { if (it.id == id) it.copy(followed = followed) else it }
        }
    }

    private val fakeSuggestionRecordRepo = FakeSuggestionRecordRepository()

    /** Räknar begärda omräkningar av bytesplanen (HEM-8, issue #88) och driver knappens släckta läge. */
    private var switchPlanScans = 0
    private val workRunning = MutableStateFlow(false)

    private val fakeScheduler = object : FundPriceRefreshScheduler {
        override fun scheduleOnLaunch() {}
        override fun scheduleBackstop() {}
        override fun triggerManualRefresh() {}
        override fun triggerSwitchPlanScan() {
            switchPlanScans++
        }
        override fun observeIsRunning(): Flow<Boolean> = workRunning
    }

    private fun viewModel() = HemViewModel(
        fakeTransactionRepo,
        fakeFundPriceRepo,
        fakeFundMetadataRepo,
        preferencesRepository,
        fakeSuggestionRecordRepo,
        fakeScheduler,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Samma dispatcher som testet (se SettingsViewModelTest) — undviker en flaky
        // Turbine-timeout om DataStores egen skrivning annars kör på en frikopplad klocka.
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            produceFile = { tempFolder.newFile("hem_test.preferences_pb") },
        )
        preferencesRepository = PreferencesRepository(dataStore)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tomt tillstand nar ingen portfolj finns`() = runTest(dispatcher) {
        val vm = viewModel()
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

        val vm = viewModel()
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

        val vm = viewModel()
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

        val vm = viewModel()
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

        val vm = viewModel()
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

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(1, state.analysisSummary.flagged.size)
            assertEquals(fond.fundId, state.analysisSummary.flagged.first().fund.fundId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Fondavgifter (HEM-5, issue #60) ---

    @Test
    fun `feeSummary anropar metadataFor med innehavens isin och rapporterar totalen`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE0001466368")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))
        metadataByIsin = mapOf(
            "SE0001466368" to FundMetadata(
                isin = "SE0001466368", name = "Fond A", orderbookId = "X", totalFee = 0.73, managementFee = 0.65,
                category = null, fundType = null, companyName = null, risk = null, indexFund = true,
                startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
            ),
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            // Metadata hämtas i ett eget flöde (issue #75) — vänta in dess emission.
            while (state.loading || state.feeSummary.comparableCount == 0) state = awaitItem()
            // 10 andelar × 120 kr = 1200 kr innehavsvärde; 0,73 % av 1200 = 8,76 kr/år.
            assertEquals(8.76, state.feeSummary.totalAnnualFeeKr, 0.01)
            assertEquals(0, state.feeSummary.unknownFeeCount)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("SE0001466368"), metadataForCall)
    }

    @Test
    fun `feeSummary raknar ett innehav utan isin som okand avgift, aldrig som noll`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A") // inget ISIN
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(0.0, state.feeSummary.totalAnnualFeeKr, 1e-9)
            assertEquals(1, state.feeSummary.unknownFeeCount)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(emptyList<String>(), metadataForCall) // inget ISIN att slå upp
    }

    @Test
    fun `feeSummary raknar ett innehav utan metadatatraff som okand avgift`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE_OKAND")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))
        metadataByIsin = emptyMap() // källan känner inte till ISIN:et

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(0.0, state.feeSummary.totalAnnualFeeKr, 1e-9)
            assertEquals(1, state.feeSummary.unknownFeeCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Samlad besparingspotential (HEM-6, issue #61) ---

    @Test
    fun `feeSummary visar besparing fran ett farskt persisterat jamforelseresultat`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE0001466368")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))
        metadataByIsin = mapOf(
            "SE0001466368" to FundMetadata(
                isin = "SE0001466368", name = "Fond A", orderbookId = "X", totalFee = 0.73, managementFee = 0.65,
                category = null, fundType = null, companyName = null, risk = null, indexFund = true,
                startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
                cheapestAlternativeIsin = "SE_ALT", cheapestAlternativeFee = 0.21,
                comparisonResolvedAtEpochDay = today.toEpochDay(),
            ),
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.feeSummary.comparedCount == 0) state = awaitItem()
            // 1200 kr innehavsvärde × (0,73−0,21)% = 6,24 kr/år.
            assertEquals(6.24, state.feeSummary.totalAnnualSavingsKr, 0.01)
            assertEquals(1, state.feeSummary.comparedCount)
            assertEquals(1, state.feeSummary.comparableCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Riskprofil (HEM-7, issue #68) ---

    @Test
    fun `riskProfile ar null och portfolioRisk saknar varde utan sparad profil`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE0001466368")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertNull(state.riskProfile)
            assertNull(state.portfolioRisk.weightedAverageRisk)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `riskProfile speglar sparad malniva och portfolioRisk raknar det vardeviktade snittet`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE0001466368")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))
        metadataByIsin = mapOf(
            "SE0001466368" to FundMetadata(
                isin = "SE0001466368", name = "Fond A", orderbookId = "X", totalFee = 0.73, managementFee = 0.65,
                category = null, fundType = null, companyName = null, risk = 4, indexFund = true,
                startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
            ),
        )
        preferencesRepository.setRiskProfile(RiskProfile(targetRiskLevel = 5))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.riskProfile == null || state.portfolioRisk.weightedAverageRisk == null) state = awaitItem()
            assertEquals(5, state.riskProfile?.targetRiskLevel)
            // Ett enda innehav -> det värdeviktade snittet är exakt fondens egen risknivå.
            assertEquals(4.0, state.portfolioRisk.weightedAverageRisk ?: -1.0, 1e-9)
            assertEquals(0, state.portfolioRisk.excludedCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `portfolioRisk exkluderar innehav utan kand risk och rapporterar det`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE_OKAND")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))
        metadataByIsin = emptyMap()
        preferencesRepository.setRiskProfile(RiskProfile(targetRiskLevel = 3))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.riskProfile == null) state = awaitItem()
            assertNull(state.portfolioRisk.weightedAverageRisk)
            assertEquals(1, state.portfolioRisk.excludedCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `riskLevelDeviations jamfor malfordelningen mot innehavens faktiska niva-fordelning`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE0001466368")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))
        metadataByIsin = mapOf(
            "SE0001466368" to FundMetadata(
                isin = "SE0001466368", name = "Fond A", orderbookId = "X", totalFee = 0.73, managementFee = 0.65,
                category = null, fundType = null, companyName = null, risk = 4, indexFund = true,
                startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
            ),
        )
        // Ett enda innehav på nivå 4 -> hela portföljvärdet är den faktiska fördelningen {4: 100 %}.
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 0.5, 4 to 0.5)))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            // Vänta tills metadatan landat — den faktiska fördelningen är tom innan dess.
            while (state.loading || state.riskLevelDeviations.none { it.actualFraction > 0.0 }) state = awaitItem()
            val byLevel = state.riskLevelDeviations.associateBy { it.level }
            assertEquals(0.5, byLevel.getValue(3).targetFraction, 1e-9)
            assertEquals(0.0, byLevel.getValue(3).actualFraction, 1e-9)
            assertEquals(0.5, byLevel.getValue(4).targetFraction, 1e-9)
            assertEquals(1.0, byLevel.getValue(4).actualFraction, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `riskLevelDeviations ar tom utan en sparad profil`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE0001466368")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.riskLevelDeviations.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- switchPlan: läser senaste inspelade facit-batch (HEM-8, issue #70) ---

    private fun setUpHoldingWithMetadataForSwitchPlan() {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE_HELD")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))
        metadataByIsin = mapOf(
            "SE_HELD" to FundMetadata(
                isin = "SE_HELD", name = "Innehav", orderbookId = "X", totalFee = 1.0, managementFee = 1.0,
                category = null, fundType = null, companyName = null, risk = 5, indexFund = false,
                startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
            ),
            "SE_CAND" to FundMetadata(
                isin = "SE_CAND", name = "Kandidat", orderbookId = "Y", totalFee = 0.3, managementFee = 0.3,
                category = null, fundType = null, companyName = null, risk = 3, indexFund = false,
                startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
            ),
        )
        fakeSuggestionRecordRepo.records.value = listOf(
            SuggestionRecord(
                id = 42,
                suggestedAtEpochDay = today.toEpochDay(), planIndex = 0,
                sellIsin = "SE_HELD", buyIsin = "SE_CAND",
                sellNavAtSuggestion = 120.0, buyNavAtSuggestion = 50.0,
                switchValueKr = 2_500.0,
            ),
        )
    }

    @Test
    fun `switchPlan bar med radens id och genomford-markering`() = runTest(dispatcher) {
        // SET-5 (issue #80): utan id:t finns ingen nyckel att skriva "Genomförd" mot, och utan
        // markeringen kan facit inte skilja ett följt råd från ett bara givet.
        setUpHoldingWithMetadataForSwitchPlan()
        fakeSuggestionRecordRepo.records.value = fakeSuggestionRecordRepo.records.value.map { it.copy(followed = true) }
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.switchPlan.isEmpty()) state = awaitItem()
            val switch = state.switchPlan.single()
            assertEquals(42L, switch.recordId)
            assertTrue(switch.followed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `omarkerat forslag ar inte genomfort - null betyder inte markerat`() = runTest(dispatcher) {
        setUpHoldingWithMetadataForSwitchPlan()
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.switchPlan.isEmpty()) state = awaitItem()
            assertFalse(state.switchPlan.single().followed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setSwitchFollowed skriver mot inspelningen och speglas i planen`() = runTest(dispatcher) {
        setUpHoldingWithMetadataForSwitchPlan()
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.switchPlan.isEmpty()) state = awaitItem()
            assertFalse(state.switchPlan.single().followed)

            vm.setSwitchFollowed(42, true)

            while (!state.switchPlan.single().followed) state = awaitItem()
            assertTrue(fakeSuggestionRecordRepo.records.value.single { it.id == 42L }.followed == true)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switchPlan visar senaste inspelade byte i ISK-KF`() = runTest(dispatcher) {
        setUpHoldingWithMetadataForSwitchPlan()
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.switchPlan.isEmpty()) state = awaitItem()
            val switch = state.switchPlan.single()
            assertEquals("Innehav", switch.sellFundName)
            assertEquals("Kandidat", switch.buyFundName)
            assertEquals(5, switch.fromLevel)
            assertEquals(3, switch.toLevel)
            assertEquals(0.3 - 1.0, switch.feeDeltaPercent, 1e-9)
            // Beloppet är en del av rådet (bytet storleksbestäms till gapet, issue #75) och
            // måste nå UI-lagret — annars är raden tvetydig.
            assertEquals(2_500.0, switch.switchValueKr ?: -1.0, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switchPlan reagerar nar kontotypen andras under prenumeration`() = runTest(dispatcher) {
        // Regression (issue #75): riskProfile/accountType lästes med first() inne i map-kroppen,
        // så uiState emitterade aldrig om på en inställningsändring. Bottennavigeringen sparar
        // ViewModel:ens tillstånd, så Hem kunde ligga kvar med det gamla valet i upp till 12
        // timmar — tills en kursuppdatering eller en ny transaktion råkade trigga flödet.
        setUpHoldingWithMetadataForSwitchPlan()

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.switchPlan.isEmpty())

            preferencesRepository.setAccountType(AccountType.ISK_KF)

            state = awaitItem()
            while (state.switchPlan.isEmpty()) state = awaitItem()
            assertEquals("Innehav", state.switchPlan.single().sellFundName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switchPlan visar bara den senaste korningens plan, inte hela dygnets`() = runTest(dispatcher) {
        // Regression (issue #75): backstopen kör var 12:e timme, så två körningar landar samma
        // dygn. "Senaste dygnet" slog ihop två planer — samma fond kunde säljas två gånger och
        // två rader bära planIndex 0. Batch-id:t håller ihop en körning.
        setUpHoldingWithMetadataForSwitchPlan()
        // Den äldre körningens köpkandidat har metadata, så raden *skulle* renderas om
        // batch-filtret inte fungerade — annars vore testet grönt av fel skäl.
        metadataByIsin = metadataByIsin + mapOf(
            "SE_ANNAN" to FundMetadata(
                isin = "SE_ANNAN", name = "Annan kandidat", orderbookId = "Z", totalFee = 0.4, managementFee = 0.4,
                category = null, fundType = null, companyName = null, risk = 3, indexFund = false,
                startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
            ),
        )
        val today = LocalDate.now().toEpochDay()
        fakeSuggestionRecordRepo.records.value = listOf(
            SuggestionRecord(
                suggestedAtEpochDay = today, planIndex = 0,
                sellIsin = "SE_HELD", buyIsin = "SE_ANNAN",
                sellNavAtSuggestion = 120.0, buyNavAtSuggestion = 70.0,
                switchValueKr = 1_000.0, batchEpochMillis = 1_000,
            ),
            SuggestionRecord(
                suggestedAtEpochDay = today, planIndex = 0,
                sellIsin = "SE_HELD", buyIsin = "SE_CAND",
                sellNavAtSuggestion = 120.0, buyNavAtSuggestion = 50.0,
                switchValueKr = 2_500.0, batchEpochMillis = 2_000,
            ),
        )
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.switchPlan.isEmpty()) state = awaitItem()

            // Bara den senare körningens rad — inte båda, och inte den äldre.
            val switch = state.switchPlan.single()
            assertEquals("Kandidat", switch.buyFundName)
            assertEquals(2_500.0, switch.switchValueKr ?: -1.0, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switchPlan visas inte alls nar det forsta bytet inte gar att sla upp`() = runTest(dispatcher) {
        // Planen är girig och sekventiell: byte 1 förutsätter att byte 0 genomförts. Faller
        // byte 0 bort får resten inte visas som om det vore fristående (issue #75).
        setUpHoldingWithMetadataForSwitchPlan()
        val today = LocalDate.now().toEpochDay()
        fakeSuggestionRecordRepo.records.value = listOf(
            SuggestionRecord(
                suggestedAtEpochDay = today, planIndex = 0,
                sellIsin = "SE_HELD", buyIsin = "SE_UTAN_METADATA",
                sellNavAtSuggestion = 120.0, buyNavAtSuggestion = 70.0,
                switchValueKr = 1_000.0, batchEpochMillis = 2_000,
            ),
            SuggestionRecord(
                suggestedAtEpochDay = today, planIndex = 1,
                sellIsin = "SE_HELD", buyIsin = "SE_CAND",
                sellNavAtSuggestion = 120.0, buyNavAtSuggestion = 50.0,
                switchValueKr = 2_500.0, batchEpochMillis = 2_000,
            ),
        )
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.switchPlan.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switchPlan doljs nar den inspelade planen blivit for gammal`() = runTest(dispatcher) {
        // Regression (issue #75, punkt 5): planen visades oavsett ålder. Slutar backstopen köra
        // (inget nät, batterioptimering, appen inte öppnad) låg ett gammalt "Sälj X → Köp Y"
        // kvar på Hem, prissatt mot en portfölj som sedan dess rört sig. HEM-6 gör uttryckligen
        // tvärtom för avgiftsjämförelsen; ett bytesförslag är samma sorts råd.
        setUpHoldingWithMetadataForSwitchPlan()
        val gammalt = LocalDate.now().minusDays(SwitchPlanCalc.PLAN_TTL_DAYS + 1).toEpochDay()
        fakeSuggestionRecordRepo.records.value = fakeSuggestionRecordRepo.records.value.map {
            it.copy(suggestedAtEpochDay = gammalt)
        }
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.switchPlan.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switchPlan visas fortfarande dagen innan farskhetsgransen loper ut`() = runTest(dispatcher) {
        setUpHoldingWithMetadataForSwitchPlan()
        val precisInomGransen = LocalDate.now().minusDays(SwitchPlanCalc.PLAN_TTL_DAYS).toEpochDay()
        fakeSuggestionRecordRepo.records.value = fakeSuggestionRecordRepo.records.value.map {
            it.copy(suggestedAtEpochDay = precisInomGransen)
        }
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.switchPlan.isEmpty()) state = awaitItem()
            assertEquals("Innehav", state.switchPlan.single().sellFundName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switchPlan ar tom utan vald kontotyp`() = runTest(dispatcher) {
        setUpHoldingWithMetadataForSwitchPlan()

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.switchPlan.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switchPlan ar tom i depa-AF`() = runTest(dispatcher) {
        setUpHoldingWithMetadataForSwitchPlan()
        preferencesRepository.setAccountType(AccountType.DEPA_AF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.switchPlan.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Omräkning av bytesplanen på begäran (HEM-8, issue #88) ---

    @Test
    fun `canRecomputeSwitchPlan kraver bade profil och ISK eller KF`() = runTest(dispatcher) {
        setUpHoldingWithMetadataForSwitchPlan()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || !state.canRecomputeSwitchPlan) state = awaitItem()
            assertTrue(state.canRecomputeSwitchPlan)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `canRecomputeSwitchPlan ar falskt i depa eller AF`() = runTest(dispatcher) {
        // SET-4-gaten ger ingen plan där — knappen ska då inte lova en omräkning.
        setUpHoldingWithMetadataForSwitchPlan()
        preferencesRepository.setAccountType(AccountType.DEPA_AF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.riskProfile == null) state = awaitItem()
            assertFalse(state.canRecomputeSwitchPlan)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `canRecomputeSwitchPlan ar falskt utan sparad riskprofil`() = runTest(dispatcher) {
        setUpHoldingWithMetadataForSwitchPlan()
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertFalse(state.canRecomputeSwitchPlan)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `backgroundWorkRunning speglar schemalaggarens korstatus`() = runTest(dispatcher) {
        setUpHoldingWithMetadataForSwitchPlan()
        workRunning.value = true

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || !state.backgroundWorkRunning) state = awaitItem()
            assertTrue(state.backgroundWorkRunning)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `recomputeSwitchPlan ber schemalaggaren om en skanning`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.recomputeSwitchPlan()

        assertEquals(1, switchPlanScans)
    }
}
