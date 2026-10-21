package se.partee71.fonder.ui.hem

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.data.datastore.BenchmarkComponentRef
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.BackupPayload
import se.partee71.fonder.data.repository.BackupSerializer
import se.partee71.fonder.data.repository.BackupStatusRepository
import se.partee71.fonder.data.repository.FakeBackupRepository
import se.partee71.fonder.data.repository.FakeCachedPriceRepository
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeSuggestionDismissalRepository
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.data.repository.FakeTransactionRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.BackupTarget
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.BackupReminder
import se.partee71.fonder.domain.usecase.DismissedPlanSource
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.InProgressSwitches
import se.partee71.fonder.domain.usecase.PendingDecisionsSource
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import se.partee71.fonder.testing.FailingUntilRetry
import se.partee71.fonder.testing.awaitItemSkipping
import se.partee71.fonder.testing.awaitRecoveryAfterRetry
import se.partee71.fonder.testing.buy
import se.partee71.fonder.testing.decliningPriceHistory
import se.partee71.fonder.testing.holdFundAWorth1200
import se.partee71.fonder.testing.holdOnly
import se.partee71.fonder.testing.holdRebought
import se.partee71.fonder.testing.price
import se.partee71.fonder.testing.quoteOnly
import se.partee71.fonder.testing.testFundMetadata
import se.partee71.fonder.worker.BackgroundWork
import se.partee71.fonder.worker.FakeFundPriceRefreshScheduler

@OptIn(ExperimentalCoroutinesApi::class)
class HemViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferencesRepository: PreferencesRepository

    private val funds = MutableStateFlow<List<Fund>>(emptyList())
    private val transactions = MutableStateFlow<List<Transaction>>(emptyList())

    private val fakeTransactionRepo = FakeTransactionRepository(funds, transactions)

    private val fakeFundPriceRepo = FakeCachedPriceRepository()
    private val latestPrices = fakeFundPriceRepo.latestPrices
    private var priceHistoryByFundId: Map<String, List<FundPrice>> by fakeFundPriceRepo::priceHistoryByFundId

    /** Varje `priceHistory`-anrop, i ordning — driver testet att hämtningen sker en gång per fond och emission. */
    private val priceHistoryCalls = fakeFundPriceRepo.priceHistoryCalls

    private var metadataForCall: List<String>? = null

    private val fakeFundMetadataRepo = object : FakeFundMetadataRepository() {
        override suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata> {
            metadataForCall = isins
            return super.metadataFor(isins)
        }
    }
    private var metadataByIsin: Map<String, FundMetadata> by fakeFundMetadataRepo::metadataByIsin

    private val fakeSuggestionRecordRepo = FakeSuggestionRecordRepository()

    /**
     * Delad fake (se [FakeFundPriceRefreshScheduler]) — räknar begärda omräkningar av
     * bytesplanen (HEM-8, issue #88) och referensfondsskanningar (HEM-10), och driver kortens
     * väntesnurror (NAV-6) via `runningWork`.
     */
    private val fakeScheduler = FakeFundPriceRefreshScheduler()

    private val fakeSwitchWatchRepo = FakeSwitchWatchRepository()

    private fun viewModel(transactionRepo: TransactionRepository = fakeTransactionRepo) = HemViewModel(
        transactionRepo,
        fakeFundPriceRepo,
        fakeFundMetadataRepo,
        preferencesRepository,
        FakeSuggestionDismissalRepository().let { dismissals ->
            PendingDecisionsSource(
                preferencesRepository,
                fakeSuggestionRecordRepo,
                fakeSwitchWatchRepo,
                fakeFundPriceRepo,
                fakeFundMetadataRepo,
                DismissedPlanSource(fakeSuggestionRecordRepo, dismissals, preferencesRepository, fakeFundMetadataRepo),
                InProgressSwitches(fakeSwitchWatchRepo, fakeSuggestionRecordRepo)
            )
        },
        fakeScheduler,
        backupStatus
    )

    /** Backup-kontraktets innehåll (HEM-12) — tomt som standard, så påminnelsen syns bara i sina egna tester. */
    private val fakeBackupRepo = FakeBackupRepository()
    private val backupStatus by lazy { BackupStatusRepository(fakeBackupRepo, preferencesRepository) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Samma dispatcher som testet (se SettingsViewModelTest) — undviker en flaky
        // Turbine-timeout om DataStores egen skrivning annars kör på en frikopplad klocka.
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            produceFile = { tempFolder.newFile("hem_test.preferences_pb") }
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
    fun `ett lasfel i databasen visas som fel i stallet for nollor (NFR-6)`() = runTest(dispatcher) {
        val failingRepo = object : TransactionRepository by fakeTransactionRepo {
            override fun observeTransactions(): Flow<List<Transaction>> = flow { throw DataError.Database }
        }
        val vm = viewModel(failingRepo)
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }
            assertEquals(DataError.Database, state.error?.cause)
            assertFalse(state.isEmpty)
            assertFalse(state.content.hasHoldings)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Forsok igen laser om portfoljen efter ett lasfel (NFR-6)`() = runTest(dispatcher) {
        val source = FailingUntilRetry()
        val flakyRepo = object : TransactionRepository by fakeTransactionRepo {
            override fun observeTransactions(): Flow<List<Transaction>> = source.guard { fakeTransactionRepo.observeTransactions() }
        }
        val vm = viewModel(flakyRepo)
        vm.uiState.test {
            val state = awaitRecoveryAfterRetry(source, { vm.onEvent(HemEvent.Retry) }, { it.loading }, { it.error })
            assertTrue(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `manadens kronor raknas pa de andelar som agts, med fondens transaktioner (issue 193)`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        // 10 andelar sedan ett år, 10 till köpta för 3 dagar sedan à 100.
        fakeTransactionRepo.funds.value = listOf(fond)
        fakeTransactionRepo.transactions.value = listOf(buy(fond, today.minusYears(1), 10.0, 80.0), buy(fond, today.minusDays(3), 10.0, 100.0))
        priceHistoryByFundId = mapOf(
            fond.fundId to listOf(price(fond, today.minusDays(30), 80.0), price(fond, today.minusDays(3), 100.0), price(fond, today, 120.0))
        )
        fakeFundPriceRepo.quoteOnly(fond, today, 120.0)

        val vm = viewModel()
        vm.uiState.test {
            val state = awaitItemSkipping { it.loading || it.content.performance.month !is PortfolioPerformanceCalc.PortfolioPeriodResult.Available }
            val month = state.content.performance.month as PortfolioPerformanceCalc.PortfolioPeriodResult.Available
            // 10 × (100 − 80) + 20 × (120 − 100) = 600 kr — inte dagens 20 andelar × (120 − 80) = 800 kr.
            assertEquals(600.0, month.amount, 1e-9)
            assertEquals(0.5, month.fraction!!, 1e-9) // fondens kursutveckling, oberoende av köpet
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `visar total varde vinst och dag vecka manad`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        fakeTransactionRepo.holdOnly(fond, today.minusYears(1))
        priceHistoryByFundId = mapOf(
            fond.fundId to listOf(
                price(fond, today, 120.0),
                price(fond, today.minusDays(1), 110.0),
                price(fond, today.minusDays(7), 100.0),
                price(fond, today.minusDays(30), 80.0)
            )
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }
            assertFalse(state.isEmpty) // innehavet finns, bara kursen saknas ännu (samma fallback som POR-3)
            // ingen kand kurs an -> ingen periodberakning möjlig för portföljen
            assertEquals(PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory, state.content.performance.day)

            fakeFundPriceRepo.quoteOnly(fond, today, 120.0)

            val updated = awaitItem()
            assertFalse(updated.isEmpty)
            assertEquals(1200.0, updated.content.totalValue, 1e-9)
            assertEquals(200.0, updated.content.totalGainLoss, 1e-9)
            assertEquals(200.0, updated.content.total.totalGainKr!!, 1e-9) // HEM-1, issue #191: bara köp → samma som orealiserat
            assertEquals(today.toEpochDay() - today.minusYears(1).toEpochDay(), updated.content.total.ageDays)
            val day = updated.content.performance.day as PortfolioPerformanceCalc.PortfolioPeriodResult.Available
            val week = updated.content.performance.week as PortfolioPerformanceCalc.PortfolioPeriodResult.Available
            val month = updated.content.performance.month as PortfolioPerformanceCalc.PortfolioPeriodResult.Available
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
        fakeTransactionRepo.holdOnly(fond, today.minusYears(1))
        fakeFundPriceRepo.quoteOnly(fond, today.minusDays(1), 120.0)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }

            assertEquals(today.minusDays(1).toEpochDay(), state.content.navEpochDay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `nyligen tillagd fond utan tillrackligt historik ger InsufficientHistory for vecka och manad`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        fakeTransactionRepo.holdOnly(fond, today.minusDays(2))
        priceHistoryByFundId = mapOf(
            fond.fundId to listOf(price(fond, today.minusDays(2), 100.0))
        )
        fakeFundPriceRepo.quoteOnly(fond, today, 105.0)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }

            assertEquals(PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory, state.content.performance.week)
            assertEquals(PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory, state.content.performance.month)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `analysis-summering ar tom nar inga fonder ar flaggade`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        fakeTransactionRepo.holdOnly(fond, today.minusYears(2))
        // Flat kurshistorik — inga signaler triggade.
        priceHistoryByFundId = mapOf(
            fond.fundId to (0..730L step 14).map { daysAgo -> price(fond, today.minusDays(daysAgo), 100.0) }
        )
        fakeFundPriceRepo.quoteOnly(fond, today, 100.0)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }
            assertEquals(1, state.content.analysisSummary.gronCount)
            assertTrue(state.content.analysisSummary.flagged.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flaggad fond analyseras fran positionens start, samma som Portfolj och Fonddetalj (issue 194)`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        fakeTransactionRepo.holdRebought(fond, today)
        // Fallande kurs → under 200-dagarssnittet → flaggad, så analysen syns i summeringen.
        priceHistoryByFundId = mapOf(fond.fundId to decliningPriceHistory(fond, today))
        fakeFundPriceRepo.quoteOnly(fond, today, 100.0)

        val vm = viewModel()
        vm.uiState.test {
            val state = awaitItemSkipping { it.loading }

            val analysis = state.content.analysisSummary.flagged.single().analysis
            val sedanKop = analysis.keyFigures.periodReturns.first { it.period == FundAnalysisCalc.Period.SEDAN_KOP }
            assertEquals(100.0 / 105.0 - 1.0, sedanKop.fraction!!, 1e-9)
            assertNull(analysis.keyFigures.cagr)
            assertEquals(FundAnalysisCalc.SignalLevel.ROD, analysis.status) // under både 52-veckorstoppen och 200-dagarssnittet
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flaggar en fond som ligger under 200-dagars snitt`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        fakeTransactionRepo.holdOnly(fond, today.minusYears(2))
        // NAV var högre förr — dagens kurs (100, vid daysAgo=0) hamnar under både
        // 52-veckorstoppen och 200-dagarssnittet (samma fixturprincip som FundAnalysisCalcTest).
        priceHistoryByFundId = mapOf(fond.fundId to decliningPriceHistory(fond, today))
        fakeFundPriceRepo.quoteOnly(fond, today, 100.0)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }
            assertEquals(1, state.content.analysisSummary.flagged.size)
            assertEquals(fond.fundId, state.content.analysisSummary.flagged.first().fund.fundId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Fondavgifter (HEM-5, issue #60) ---

    @Test
    fun `feeSummary anropar metadataFor med innehavens isin och rapporterar totalen`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = holdFundAWorth1200(fakeTransactionRepo, fakeFundPriceRepo, today)
        metadataByIsin = mapOf(
            "SE0001466368" to testFundMetadata(isin = "SE0001466368", name = "Fond A", orderbookId = "X", totalFee = 0.73, managementFee = 0.65, indexFund = true)
        )

        val vm = viewModel()
        vm.uiState.test {
            // Metadata hämtas i ett eget flöde (issue #75) — vänta in dess emission.
            var state = awaitItemSkipping { it.loading || it.content.feeSummary.comparableCount == 0 }
            // 10 andelar × 120 kr = 1200 kr innehavsvärde; 0,73 % av 1200 = 8,76 kr/år.
            assertEquals(8.76, state.content.feeSummary.totalAnnualFeeKr, 0.01)
            assertEquals(0, state.content.feeSummary.unknownFeeCount)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("SE0001466368"), metadataForCall)
    }

    @Test
    fun `feeSummary raknar ett innehav utan isin som okand avgift, aldrig som noll`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A") // inget ISIN
        fakeTransactionRepo.holdOnly(fond, today.minusYears(1))
        fakeFundPriceRepo.quoteOnly(fond, today, 120.0)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }
            assertEquals(0.0, state.content.feeSummary.totalAnnualFeeKr, 1e-9)
            assertEquals(1, state.content.feeSummary.unknownFeeCount)
            cancelAndIgnoreRemainingEvents()
        }
        // Inget ISIN att slå upp — uppslaget görs inte **alls** (null), inte med en tom lista.
        // Ett anrop med tom lista tände dessutom avgifts- och riskkortets väntesnurra utan att
        // något var på väg (NAV-6), vilket är precis vad snurran inte ska betyda.
        assertNull(metadataForCall)
    }

    @Test
    fun `feeSummary raknar ett innehav utan metadatatraff som okand avgift`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = holdFundAWorth1200(fakeTransactionRepo, fakeFundPriceRepo, today, isin = "SE_OKAND")
        metadataByIsin = emptyMap() // källan känner inte till ISIN:et

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }
            assertEquals(0.0, state.content.feeSummary.totalAnnualFeeKr, 1e-9)
            assertEquals(1, state.content.feeSummary.unknownFeeCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Samlad besparingspotential (HEM-6, issue #61) ---

    @Test
    fun `feeSummary visar besparing fran ett farskt persisterat jamforelseresultat`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = holdFundAWorth1200(fakeTransactionRepo, fakeFundPriceRepo, today)
        metadataByIsin = mapOf(
            "SE0001466368" to testFundMetadata(isin = "SE0001466368", name = "Fond A", orderbookId = "X", totalFee = 0.73, managementFee = 0.65, indexFund = true, cheapestAlternativeIsin = "SE_ALT", cheapestAlternativeFee = 0.21, comparisonResolvedAtEpochDay = today.toEpochDay())
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.content.feeSummary.comparedCount == 0 }
            // 1200 kr innehavsvärde × (0,73−0,21)% = 6,24 kr/år.
            assertEquals(6.24, state.content.feeSummary.totalAnnualSavingsKr, 0.01)
            assertEquals(1, state.content.feeSummary.comparedCount)
            assertEquals(1, state.content.feeSummary.comparableCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Riskprofil (HEM-7, issue #68) ---

    @Test
    fun `riskProfile ar null och portfolioRisk saknar varde utan sparad profil`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = holdFundAWorth1200(fakeTransactionRepo, fakeFundPriceRepo, today)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }
            assertNull(state.content.riskProfile)
            assertNull(state.content.portfolioRisk.weightedAverageRisk)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `riskProfile speglar sparad malniva och portfolioRisk raknar det vardeviktade snittet`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = holdFundAWorth1200(fakeTransactionRepo, fakeFundPriceRepo, today)
        metadataByIsin = mapOf(
            "SE0001466368" to testFundMetadata(isin = "SE0001466368", name = "Fond A", orderbookId = "X", totalFee = 0.73, managementFee = 0.65, risk = 4, indexFund = true)
        )
        preferencesRepository.setRiskProfile(RiskProfile(targetRiskLevel = 5))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.content.riskProfile == null || it.content.portfolioRisk.weightedAverageRisk == null }
            assertEquals(5, state.content.riskProfile?.targetRiskLevel)
            // Ett enda innehav -> det värdeviktade snittet är exakt fondens egen risknivå.
            assertEquals(4.0, state.content.portfolioRisk.weightedAverageRisk ?: -1.0, 1e-9)
            assertEquals(0, state.content.portfolioRisk.excludedCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `portfolioRisk exkluderar innehav utan kand risk och rapporterar det`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = holdFundAWorth1200(fakeTransactionRepo, fakeFundPriceRepo, today, isin = "SE_OKAND")
        metadataByIsin = emptyMap()
        preferencesRepository.setRiskProfile(RiskProfile(targetRiskLevel = 3))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.content.riskProfile == null }
            assertNull(state.content.portfolioRisk.weightedAverageRisk)
            assertEquals(1, state.content.portfolioRisk.excludedCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `riskLevelDeviations jamfor malfordelningen mot innehavens faktiska niva-fordelning`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fond = holdFundAWorth1200(fakeTransactionRepo, fakeFundPriceRepo, today)
        metadataByIsin = mapOf(
            "SE0001466368" to testFundMetadata(isin = "SE0001466368", name = "Fond A", orderbookId = "X", totalFee = 0.73, managementFee = 0.65, risk = 4, indexFund = true)
        )
        // Ett enda innehav på nivå 4 -> hela portföljvärdet är den faktiska fördelningen {4: 100 %}.
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 0.5, 4 to 0.5)))

        val vm = viewModel()
        vm.uiState.test {
            // Vänta tills metadatan landat — den faktiska fördelningen är tom innan dess.
            var state = awaitItemSkipping { it.loading || it.content.riskLevelDeviations.none { it.actualFraction > 0.0 } }
            val byLevel = state.content.riskLevelDeviations.associateBy { it.level }
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
        val fond = holdFundAWorth1200(fakeTransactionRepo, fakeFundPriceRepo, today)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }
            assertTrue(state.content.riskLevelDeviations.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Att besluta (HEM-13): bytesplan och pågående byten ur den delade källan ---

    private fun setUpHoldingWithMetadataForSwitchPlan() {
        val today = LocalDate.now()
        val fond = holdFundAWorth1200(fakeTransactionRepo, fakeFundPriceRepo, today, isin = "SE_HELD")
        metadataByIsin = mapOf(
            "SE_HELD" to testFundMetadata(isin = "SE_HELD", name = "Innehav", orderbookId = "X", totalFee = 1.0, managementFee = 1.0, risk = 5),
            "SE_CAND" to testFundMetadata(isin = "SE_CAND", name = "Kandidat", orderbookId = "Y", totalFee = 0.3, managementFee = 0.3, risk = 3)
        )
        fakeSuggestionRecordRepo.records.value = listOf(
            SuggestionRecord(
                id = 42,
                suggestedAtEpochDay = today.toEpochDay(),
                planIndex = 0,
                sellIsin = "SE_HELD",
                buyIsin = "SE_CAND",
                sellNavAtSuggestion = 120.0,
                buyNavAtSuggestion = 50.0,
                switchValueKr = 2_500.0
            )
        )
    }

    @Test
    fun `en kursuppdatering snurrar kurskorten men inte riskkortet`() = runTest(dispatcher) {
        setUpHoldingWithMetadataForSwitchPlan()
        fakeScheduler.runningWork.value = setOf(BackgroundWork.PRICE_REFRESH)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || !it.pricesWorking }
            assertTrue(state.totalWorking)
            assertTrue(state.performanceWorking)
            assertTrue(state.returnChartWorking)
            assertTrue(state.analysisWorking)
            // Riskkortet vilar på metadatan, inte på kurserna — det ska stå stilla medan bara
            // kurserna hämtas, annars säger snurran ingenting.
            while (state.metadataWorking) state = awaitItem()
            assertFalse(state.riskWorking)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en referensfondsskanning snurrar bara avkastningskortet`() = runTest(dispatcher) {
        setUpHoldingWithMetadataForSwitchPlan()
        fakeScheduler.runningWork.value = setOf(BackgroundWork.BENCHMARK_SCAN)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || !it.benchmarkWorking }
            assertTrue(state.returnChartWorking)
            assertFalse(state.pricesWorking)
            while (state.metadataWorking) state = awaitItem()
            assertFalse(state.riskWorking)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en bytesplansskanning snurrar inte riskkortet - planen visas pa Beslut`() = runTest(dispatcher) {
        // Sedan 0.61.0 ligger bytesplanen på Beslut (NAV-9); riskkortet visar bara fördelningen
        // och vilar på metadatan. En snurra här hade sagt att fördelningen är osäker när den inte är det.
        setUpHoldingWithMetadataForSwitchPlan()
        fakeScheduler.runningWork.value = setOf(BackgroundWork.SWITCH_PLAN_SCAN)

        viewModel().uiState.test {
            var state = awaitItemSkipping { it.loading || it.metadataWorking }
            assertFalse(state.riskWorking)
            assertFalse(state.pricesWorking)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `att besluta raknar oppna forslag och pagaende byten ur den delade kallan (HEM-13)`() = runTest(dispatcher) {
        setUpHoldingWithMetadataForSwitchPlan()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        fakeSwitchWatchRepo.start(
            SwitchWatch(sellIsin = "SE_ANNAN_SALJ", sellFundName = "Fond B", soldAtEpochDay = LocalDate.now().toEpochDay())
        )

        viewModel().uiState.test {
            var state = awaitItemSkipping { it.loading || it.decisions.count < 2 }
            assertEquals(1, state.decisions.openSwitchCount)
            assertEquals(1, state.decisions.soldCount)
            assertEquals(2, state.decisions.count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `att besluta raknar billigare alternativ for sig ur den delade kallan (HEM-13, ANA-9)`() = runTest(dispatcher) {
        setUpHoldingWithMetadataForSwitchPlan()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        // Innehavets senaste jämförelse visar kandidaten, och bakgrundsskanningen har spelat in avgiftsbytet.
        metadataByIsin = metadataByIsin + ("SE_HELD" to metadataByIsin.getValue("SE_HELD").copy(shownAlternativeIsins = listOf("SE_CAND")))
        fakeSuggestionRecordRepo.records.value += fakeSuggestionRecordRepo.records.value.single().copy(id = 43, kind = SuggestionKind.FEE)

        viewModel().uiState.test {
            var state = awaitItemSkipping { it.loading || it.decisions.count < 2 }
            assertEquals(1, state.decisions.openSwitchCount)
            assertEquals(1, state.decisions.openCheaperAlternativeCount)
            assertEquals(2, state.decisions.count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh ber schemalaggaren om en forcerad kursuppdatering (UI-11)`() = runTest(dispatcher) {
        // Dragningen startar exakt den körning Inställningars borttagna knapp startade (SET-2),
        // och gör det via WorkManager — inte med en egen hämtning vid sidan av.
        val vm = viewModel()

        vm.onEvent(HemEvent.Refresh)

        assertEquals(1, fakeScheduler.manualRefreshes)
    }

    // --- Avkastningskurvan och indexjämförelsen (HEM-9/HEM-10, issue #96) ---

    /** Ett innehav köpt för 30 dagar sedan med en kurshistorik som räcker till en kurva. */
    private fun setUpHoldingForReturnSeries(fond: Fund = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE0000000001")) {
        val today = LocalDate.now()
        fakeTransactionRepo.holdOnly(fond, today.minusDays(30))
        priceHistoryByFundId = mapOf(
            fond.fundId to listOf(
                price(fond, today.minusDays(30), 100.0),
                price(fond, today.minusDays(15), 110.0),
                price(fond, today, 120.0)
            )
        )
        fakeFundPriceRepo.quoteOnly(fond, today, 120.0)
    }

    /** Referensfondens kurshistorik: [start] för 30 dagar sedan och [end] idag (samma dagar som innehavet). */
    private fun monthOfPrices(isin: String, start: Double, end: Double): Pair<String, List<FundPrice>> {
        val today = LocalDate.now()
        return isin to listOf(
            FundPrice(fundId = isin, epochDay = today.minusDays(30).toEpochDay(), nav = start),
            FundPrice(fundId = isin, epochDay = today.toEpochDay(), nav = end)
        )
    }

    @Test
    fun `avkastningskurvan ar tidsviktad`() = runTest(dispatcher) {
        // Kurvan mäter fondernas utveckling (HEM-9, issue #116) — kedjade dagsavkastningar, inte
        // en ögonblicksbild av avkastningen på insatt kapital som totalkortet visar.
        setUpHoldingForReturnSeries()

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.content.returnSeries.isEmpty }

            assertEquals(3, state.content.returnSeries.points.size)
            assertEquals(0.0, state.content.returnSeries.points.first().second, 1e-9)
            // 100 → 110 → 120: 1,10 × (120/110) − 1 = +20 %.
            assertEquals(0.10, state.content.returnSeries.points[1].second, 1e-9)
            assertEquals(0.20, state.content.returnSeries.points.last().second, 1e-9)
            assertFalse(state.content.returnSeries.partial)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `kopdagarna bars med sa insattningar kan markeras i diagrammet`() = runTest(dispatcher) {
        val today = LocalDate.now()
        setUpHoldingForReturnSeries()

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.content.purchaseEpochDays.isEmpty() }

            assertEquals(listOf(today.minusDays(30).toEpochDay()), state.content.purchaseEpochDays)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `kurvan markeras delvis osaker nar ett innehav saknar kurshistorik`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val fondA = Fund(fundId = "SHB0000442", name = "Fond A")
        val fondB = Fund(fundId = "SHB0000443", name = "Fond B")
        funds.value = listOf(fondA, fondB)
        transactions.value = listOf(
            buy(fondA, today.minusDays(30), 10.0, 100.0),
            buy(fondB, today.minusDays(30), 10.0, 100.0)
        )
        // Fond B saknar historik helt — den ska utelämnas ur kurvan, inte räknas som en förlust.
        priceHistoryByFundId = mapOf(
            fondA.fundId to listOf(
                price(fondA, today.minusDays(30), 100.0),
                price(fondA, today, 120.0)
            )
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.content.returnSeries.isEmpty }

            assertTrue(state.content.returnSeries.partial)
            assertEquals(0.20, state.content.returnSeries.points.last().second, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `indexjamforelsen byggs ur cachen nar referensfonden ar vald`() = runTest(dispatcher) {
        val today = LocalDate.now()
        setUpHoldingForReturnSeries()
        // Referensfonden: samma dagar, men +10 % i stället för innehavets +20 %.
        priceHistoryByFundId = priceHistoryByFundId + monthOfPrices(BENCHMARK_ISIN, 200.0, 220.0)
        metadataByIsin = mapOf(BENCHMARK_ISIN to indexFundMetadata())
        preferencesRepository.setBenchmark(listOf(BenchmarkComponentRef(BENCHMARK_ISIN, weight = 1.0)))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.content.benchmarkSeries == null }

            val benchmark = state.content.benchmarkSeries!!
            assertEquals("Global Index", benchmark.fundName)
            assertEquals(0.0, benchmark.points.first().second, 1e-9)
            assertEquals(0.10, benchmark.points.last().second, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ingen indexjamforelse innan bakgrundsjobbet valt en referensfond`() = runTest(dispatcher) {
        setUpHoldingForReturnSeries()

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.content.returnSeries.isEmpty }

            assertNull(state.content.benchmarkSeries)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ingen indexjamforelse nar referensfondens historik inte nar tillbaka till forsta kopet`() = runTest(dispatcher) {
        val today = LocalDate.now()
        setUpHoldingForReturnSeries()
        // Historiken börjar efter köpet — skuggportföljen kan då inte spegla insättningen, och
        // en halv jämförelse är sämre än ingen (HEM-10).
        priceHistoryByFundId = priceHistoryByFundId + (
            BENCHMARK_ISIN to listOf(FundPrice(fundId = BENCHMARK_ISIN, epochDay = today.toEpochDay(), nav = 220.0))
            )
        metadataByIsin = mapOf(BENCHMARK_ISIN to indexFundMetadata())
        preferencesRepository.setBenchmark(listOf(BenchmarkComponentRef(BENCHMARK_ISIN, weight = 1.0)))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.content.returnSeries.isEmpty }

            assertNull(state.content.benchmarkSeries)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `kurshistoriken hamtas en gang per fond och emission`() = runTest(dispatcher) {
        // Innan issue #96 hämtade Hem samma fonds historik två gånger per emission (en gång för
        // dag-/vecka-/månadsraden, en gång för analysen). Kurvan behöver den en tredje gång —
        // hämtningarna slogs därför ihop till en, med det vidaste fönstret.
        val fond = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE0000000001")
        setUpHoldingForReturnSeries(fond)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.content.returnSeries.isEmpty }

            priceHistoryCalls.clear()
            latestPrices.value = mapOf(
                fond.fundId to price(fond, LocalDate.now(), 130.0)
            )
            awaitItem()

            assertEquals(1, priceHistoryCalls.count { it == fond.fundId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `referensfonden begars nar ingen ar vald, en gang och inte per emission`() = runTest(dispatcher) {
        // Utan den här vägen valdes referensfonden bara av backstopen var 12:e timme — en ny
        // installation visade "Ingen indexjämförelse än" i upp till ett halvt dygn.
        val fond = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE0000000001")
        setUpHoldingForReturnSeries(fond)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.content.returnSeries.isEmpty }
            assertEquals(1, fakeScheduler.benchmarkScans)

            // Ny emission (kursändring) ska inte ge en till skanning.
            latestPrices.value = mapOf(
                fond.fundId to price(fond, LocalDate.now(), 130.0)
            )
            awaitItem()

            assertEquals(1, fakeScheduler.benchmarkScans)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ingen skanning begars nar en referensfond redan ar vald`() = runTest(dispatcher) {
        setUpHoldingForReturnSeries()
        preferencesRepository.setBenchmark(listOf(BenchmarkComponentRef(BENCHMARK_ISIN, weight = 1.0)))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.content.returnSeries.isEmpty }

            assertEquals(0, fakeScheduler.benchmarkScans)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ingen skanning begars utan innehav`() = runTest(dispatcher) {
        // Ingen portfölj att jämföra — skanningen kostar en källfråga och ska inte gå på tomgång.
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }

            assertEquals(0, fakeScheduler.benchmarkScans)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en viktad referens namnges med sina andelar`() = runTest(dispatcher) {
        val today = LocalDate.now()
        setUpHoldingForReturnSeries()
        priceHistoryByFundId = priceHistoryByFundId +
            monthOfPrices(BENCHMARK_ISIN, 200.0, 220.0) +
            monthOfPrices(BOND_ISIN, 100.0, 100.0)
        metadataByIsin = mapOf(
            BENCHMARK_ISIN to indexFundMetadata(),
            BOND_ISIN to indexFundMetadata(isin = BOND_ISIN, name = "Räntefond X")
        )
        preferencesRepository.setBenchmark(
            listOf(
                BenchmarkComponentRef(BENCHMARK_ISIN, weight = 0.7),
                BenchmarkComponentRef(BOND_ISIN, weight = 0.3)
            )
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.content.benchmarkSeries == null }

            val benchmark = state.content.benchmarkSeries!!
            assertTrue(benchmark.fundName.contains("Global Index"))
            assertTrue(benchmark.fundName.contains("Räntefond X"))
            // 70 % av insättningen i en fond som steg 10 %, 30 % i en som stod still.
            assertEquals(0.07, benchmark.points.last().second, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en referens sparad i det gamla formatet lases som en enkomponentsblandning`() = runTest(dispatcher) {
        // Issue #96 sparade ett ensamt ISIN. En uppgradering får inte tappa referensen och
        // tvinga fram en ny källfråga plus full backfill i onödan.
        val today = LocalDate.now()
        setUpHoldingForReturnSeries()
        priceHistoryByFundId = priceHistoryByFundId + monthOfPrices(BENCHMARK_ISIN, 200.0, 220.0)
        metadataByIsin = mapOf(BENCHMARK_ISIN to indexFundMetadata())
        dataStore.edit { it[stringPreferencesKey("benchmark_isin")] = BENCHMARK_ISIN }

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.content.benchmarkSeries == null }

            assertEquals("Global Index", state.content.benchmarkSeries!!.fundName)
            assertEquals(0, fakeScheduler.benchmarkScans)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `eget val av referens vinner over appens automatiska`() = runTest(dispatcher) {
        val today = LocalDate.now()
        setUpHoldingForReturnSeries()
        priceHistoryByFundId = priceHistoryByFundId +
            monthOfPrices(BENCHMARK_ISIN, 200.0, 220.0) +
            monthOfPrices(BOND_ISIN, 100.0, 130.0)
        metadataByIsin = mapOf(
            BENCHMARK_ISIN to indexFundMetadata(),
            BOND_ISIN to indexFundMetadata(isin = BOND_ISIN, name = "Mitt val")
        )
        // Appens val: den automatiska blandningen. Användarens val: en helt annan fond.
        preferencesRepository.setBenchmark(listOf(BenchmarkComponentRef(BENCHMARK_ISIN, weight = 1.0)))
        preferencesRepository.setChosenBenchmarkIsin(BOND_ISIN)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.content.benchmarkSeries == null }

            val benchmark = state.content.benchmarkSeries!!
            assertEquals("Mitt val", benchmark.fundName)
            assertEquals(0.30, benchmark.points.last().second, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Påminnelse om säkerhetskopia (HEM-12) ---

    private fun backupPayload(transactionCount: Int) = BackupPayload(
        exportedAtEpochMillis = 0,
        funds = listOf(Fund(fundId = "SHB1", name = "Fond A")),
        transactions = (1..transactionCount).map { i ->
            Transaction(id = i.toLong(), fundId = "SHB1", type = TransactionType.KOP, epochDay = 19_000L + i, shares = 1.0, pricePerShare = 100.0)
        }
    )

    @Test
    fun `ingen paminnelse utan data`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.loading)
            assertNull(vm.uiState.value.backupReminder)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `paminner nar data finns men ingen sakerhetskopia gjorts`() = runTest(dispatcher) {
        fakeBackupRepo.payload.value = backupPayload(transactionCount = 2)

        viewModel().uiState.test {
            var state = awaitItemSkipping { it.backupReminder == null }
            assertEquals(BackupReminder(newTransactions = 0, lastBackupEpochMillis = null), state.backupReminder)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `paminnelsen forsvinner efter en sakerhetskopia och kommer tillbaka med antal nya transaktioner`() = runTest(dispatcher) {
        fakeBackupRepo.payload.value = backupPayload(transactionCount = 2)
        val vm = viewModel()

        vm.uiState.test {
            var state = awaitItemSkipping { it.backupReminder == null }

            backupStatus.recordBackup(BackupSerializer.encode(fakeBackupRepo.payload.value), BackupTarget.DRIVE, nowEpochMillis = 1_700_000_000_000L)
            while (state.backupReminder != null) state = awaitItem()

            fakeBackupRepo.payload.value = backupPayload(transactionCount = 5)
            while (state.backupReminder == null) state = awaitItem()
            assertEquals(BackupReminder(newTransactions = 3, lastBackupEpochMillis = 1_700_000_000_000L), state.backupReminder)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en andring som tas tillbaka ger ingen paminnelse`() = runTest(dispatcher) {
        fakeBackupRepo.payload.value = backupPayload(transactionCount = 2)
        backupStatus.recordBackup(BackupSerializer.encode(fakeBackupRepo.payload.value), BackupTarget.FILE, nowEpochMillis = 1_700_000_000_000L)
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            fakeBackupRepo.payload.value = backupPayload(transactionCount = 3)
            fakeBackupRepo.payload.value = backupPayload(transactionCount = 2)
            advanceUntilIdle()
            assertNull(vm.uiState.value.backupReminder)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun indexFundMetadata(isin: String = BENCHMARK_ISIN, name: String = "Global Index") = testFundMetadata(isin = isin, name = name, totalFee = 0.2, managementFee = 0.2, fundType = "Aktiefond", risk = 5, indexFund = true)

    private companion object {
        const val BENCHMARK_ISIN = "SE0009999999"
        const val BOND_ISIN = "SE0008888888"
    }
}
