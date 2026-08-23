package se.partee71.fonder.ui.settings

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
import kotlinx.coroutines.flow.first
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
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.BackupFormatException
import se.partee71.fonder.data.repository.BackupRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.RestoreSummary
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private var clearAllCalled = false
    private var manualRefreshCalled = false
    private var switchPlanScans = 0
    private lateinit var dataStore: DataStore<Preferences>

    private val fakeTransactionRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = MutableStateFlow(emptyList())
        override fun observeTransactions(): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override suspend fun upsertFund(fund: Fund) {}
        override suspend fun addTransaction(tx: Transaction): Long = 0
        override suspend fun deleteTransaction(id: Long) {}
        override suspend fun clearAll() {
            clearAllCalled = true
        }
    }

    /** Räknar begärda referensfondsskanningar (HEM-10, issue #102). */
    private var benchmarkScans = 0

    private val fakeScheduler = object : FundPriceRefreshScheduler {
        override fun scheduleOnLaunch() {}
        override fun scheduleBackstop() {}
        override fun triggerManualRefresh() {
            manualRefreshCalled = true
        }
        override fun triggerSwitchPlanScan() {
            switchPlanScans++
        }
        override fun triggerBenchmarkScan() {
            benchmarkScans++
        }
        override fun observeIsRunning(): Flow<Boolean> = MutableStateFlow(false)
    }

    private var exportResult: Result<String> = Result.success("{}")
    private var restoreResult: Result<RestoreSummary> = Result.success(RestoreSummary(0, 0, 0))
    private var restoredJson: String? = null

    private val fakeBackupRepo = object : BackupRepository {
        override suspend fun export(): Result<String> = exportResult
        override suspend fun restore(json: String): Result<RestoreSummary> {
            restoredJson = json
            return restoreResult
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // DataStore skriver annars på sin egen Dispatchers.IO-bundna scope, frikopplad från
        // testets StandardTestDispatcher — awaitItem() väntar då på en riktig (klock-)tid som
        // ibland hann överstiga Turbines timeout under CI-belastning (flaky, se
        // "lastPriceSyncEpochMillis speglar preferences efter en uppdatering"). Ger DataStore
        // samma dispatcher som testet så skrivningen blir en del av samma virtuella tid.
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            produceFile = { tempFolder.newFile("settings_test.preferences_pb") },
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()


    /** Slår upp ISIN för en katalogfond utan ett — null = fonden går inte att jämföra mot (issue #102). */
    private var isinByFundId: Map<String, String> = emptyMap()

    private val fakeFundPriceRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? = null
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = flowOf(emptyMap())
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice> = emptyList()
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())
        override suspend fun refresh(fundId: String, since: LocalDate?) = true
        override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate) = true
        override suspend fun historyForIsin(isin: String, from: LocalDate, to: LocalDate): List<FundPrice> = emptyList()
        override suspend fun suggestIsin(fundName: String): String? = null
        override suspend fun findFundByIsin(isin: String): Fund? = null
        override suspend fun lookupIsin(fundId: String): String? = isinByFundId[fundId]
        override suspend fun fetchFundsForCompany(companyId: String): List<Fund>? = emptyList()
        override suspend fun fetchFundCatalog(): FundCatalog = FundCatalog(emptyList(), emptyList())
    }

    private var metadataByIsin: Map<String, FundMetadata> = emptyMap()

    private val fakeFundMetadataRepo = object : FundMetadataRepository {
        override suspend fun query(query: FundScreenQuery): List<FundMetadata> = emptyList()
        override suspend fun resolveHandelsbankenAvailability(isin: String): Boolean? = null
        override fun observeFilterVocabulary() = flowOf(FundFilterVocabulary())
        override suspend fun suggestCheaperAlternatives(isin: String, holdingValue: Double): List<FeeComparisonCalc.Alternative>? = null
        override suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata> = metadataByIsin.filterKeys { it in isins }
        override suspend fun cachedRiskByFundName(): Map<String, Int> = emptyMap()
        override suspend fun cachedMetadataFor(isins: List<String>): Map<String, FundMetadata> = metadataByIsin.filterKeys { it in isins }
        override suspend fun knownRiskLevels(): List<Int> = emptyList()
        override suspend fun findSwitchCandidates(level: Int, excludeIsins: Set<String>): List<SwitchPlanCalc.Candidate> = emptyList()
    }

    private fun viewModel() = SettingsViewModel(PreferencesRepository(dataStore), fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo)

    @Test
    fun `clearDatabase anropar repository och satter databaseCleared i uiState`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.uiState.test {
            assertFalse(awaitItem().databaseCleared)

            vm.clearDatabase()
            var state = awaitItem()
            while (!state.databaseCleared) state = awaitItem()

            assertTrue(clearAllCalled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onClearedMessageDismissed kvitterar handelsen sa den inte spelas upp igen`() = runTest(dispatcher) {
        // Regression (issue #78): flaggan nollställdes aldrig, så meddelandet kom tillbaka vid
        // varje rotation och — eftersom uiState är WhileSubscribed — vid varje återbesök i
        // Inställningar. En engångshändelse får inte ligga kvar som bestående tillstånd.
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.clearDatabase()
            var state = awaitItem()
            while (!state.databaseCleared) state = awaitItem()

            vm.onClearedMessageDismissed()
            state = awaitItem()
            while (state.databaseCleared) state = awaitItem()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastPriceSyncEpochMillis ar null innan nagon uppdatering skett (SET-2)`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.uiState.test {
            assertEquals(null, awaitItem().lastPriceSyncEpochMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lastPriceSyncEpochMillis speglar preferences efter en uppdatering (SET-2)`() = runTest(dispatcher) {
        val preferences = PreferencesRepository(dataStore)
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo)

        vm.uiState.test {
            awaitItem()
            preferences.setLastPriceSyncEpochMillis(1_700_000_000_000L)
            var state = awaitItem()
            while (state.lastPriceSyncEpochMillis == null) state = awaitItem()

            assertEquals(1_700_000_000_000L, state.lastPriceSyncEpochMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshPricesNow forcerar en manuell uppdatering via schedulern (SET-2)`() {
        val vm = viewModel()

        vm.refreshPricesNow()

        assertTrue(manualRefreshCalled)
    }

    // --- Kontotyp (SET-4, issue #70) ---

    @Test
    fun `accountType ar null innan nagot val gjorts`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.uiState.test {
            assertEquals(null, awaitItem().accountType)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setAccountType sparar valet och speglas i uiState`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.setAccountType(AccountType.ISK_KF)
            var state = awaitItem()
            while (state.accountType == null) state = awaitItem()

            assertEquals(AccountType.ISK_KF, state.accountType)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Säkerhetskopiering (SET-6, issue #82) ---

    @Test
    fun `export lamnar serialiserad json till skrivaren och kvitterar`() = runTest(dispatcher) {
        exportResult = Result.success("""{"formatVersion":1}""")
        var written: String? = null
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.export { written = it }
            var state = awaitItem()
            while (state.backupMessage == null) state = awaitItem()

            assertEquals("""{"formatVersion":1}""", written)
            assertEquals(BackupMessage.Exported, state.backupMessage)
            assertFalse(state.backupInProgress)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett skrivfel raknas som misslyckad export, inte som tyst lyckad`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.export { throw java.io.IOException("ingen skrivrätt") }
            var state = awaitItem()
            while (state.backupMessage == null) state = awaitItem()

            assertEquals(BackupMessage.ExportFailed, state.backupMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restore skickar filens innehall till repositoryt och redovisar vad som skrevs`() = runTest(dispatcher) {
        restoreResult = Result.success(RestoreSummary(funds = 2, transactions = 5, suggestionRecords = 3))
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.restore { """{"formatVersion":1}""" }
            var state = awaitItem()
            while (state.backupMessage == null) state = awaitItem()

            assertEquals("""{"formatVersion":1}""", restoredJson)
            assertEquals(BackupMessage.Restored(RestoreSummary(2, 5, 3)), state.backupMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en olasbar fil nar aldrig repositoryt och redovisas som trasig`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.restore { null }
            var state = awaitItem()
            while (state.backupMessage == null) state = awaitItem()

            assertEquals(null, restoredJson)
            assertEquals(BackupMessage.RestoreFailed(BackupFormatException.Reason.UNREADABLE), state.backupMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en fil fran en nyare app redovisas som versionsfel, inte som trasig`() = runTest(dispatcher) {
        // Skillnaden är hela poängen: "trasig fil" och "nyare app" leder till olika åtgärd för
        // användaren, så felet får inte plattas till på vägen upp till UI:t.
        restoreResult = Result.failure(BackupFormatException(BackupFormatException.Reason.UNSUPPORTED_VERSION, fileVersion = 99))
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.restore { """{"formatVersion":99}""" }
            var state = awaitItem()
            while (state.backupMessage == null) state = awaitItem()

            assertEquals(BackupMessage.RestoreFailed(BackupFormatException.Reason.UNSUPPORTED_VERSION), state.backupMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onBackupMessageDismissed kvitterar handelsen sa den inte spelas upp igen`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.export {}
            var state = awaitItem()
            while (state.backupMessage == null) state = awaitItem()

            vm.onBackupMessageDismissed()
            state = awaitItem()
            while (state.backupMessage != null) state = awaitItem()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dubbeltryck startar inte en andra aterstallning`() = runTest(dispatcher) {
        // Samma spärr som importflödena (issue #75): återställningen ger ingen synlig
        // återkoppling förrän den är klar, så ett andra tryck skulle skriva samma rader igen.
        var reads = 0
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.restore { reads++; """{"formatVersion":1}""" }
            vm.restore { reads++; """{"formatVersion":1}""" }
            var state = awaitItem()
            while (state.backupMessage == null) state = awaitItem()

            assertEquals(1, reads)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Omräkning av bytesplanen vid kontotypsbyte (HEM-8, issue #88) ---

    @Test
    fun `byte till ISK eller KF ber om en omrakning av bytesplanen`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.setAccountType(AccountType.ISK_KF)
        advanceUntilIdle()

        assertEquals(1, switchPlanScans)
    }

    @Test
    fun `samma kontotyp igen kostar ingen ny skanning`() = runTest(dispatcher) {
        // Skanningen kostar en källfråga plus budgeterad köpbarhetsverifiering per underviktad
        // nivå — den ska följa faktiska byten, inte antalet tryck.
        val vm = viewModel()

        vm.setAccountType(AccountType.ISK_KF)
        advanceUntilIdle()
        vm.setAccountType(AccountType.ISK_KF)
        advanceUntilIdle()

        assertEquals(1, switchPlanScans)
    }

    @Test
    fun `byte till depa eller AF ber inte om nagon omrakning`() = runTest(dispatcher) {
        // SET-4-gaten ger ingen plan alls i depå/AF — det finns inget att räkna om.
        val vm = viewModel()

        vm.setAccountType(AccountType.DEPA_AF)
        advanceUntilIdle()

        assertEquals(0, switchPlanScans)
    }

    // --- Val av jämförelsefond (HEM-10, issue #102) ---

    @Test
    fun `valt referens-ISIN sparas och startar en skanning`() = runTest(dispatcher) {
        val preferences = PreferencesRepository(dataStore)
        metadataByIsin = mapOf("SE0011527613" to benchmarkMetadata())
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo)

        vm.chooseBenchmark(Fund(fundId = "SHB1", name = "Global Index", isin = "SE0011527613"))
        advanceUntilIdle()

        assertEquals("SE0011527613", preferences.chosenBenchmarkIsin.first())
        // Utan skanningen hade den valda fondens historik dröjt till nästa backstop (issue #88).
        assertEquals(1, benchmarkScans)
    }

    @Test
    fun `en katalogfond utan ISIN slas upp innan den sparas`() = runTest(dispatcher) {
        // Katalogens träffar saknar ISIN — hela jämförelsekedjan är ISIN-nycklad.
        val preferences = PreferencesRepository(dataStore)
        isinByFundId = mapOf("SHB1" to "SE0011527613")
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo)

        vm.chooseBenchmark(Fund(fundId = "SHB1", name = "Global Index", isin = null))
        advanceUntilIdle()

        assertEquals("SE0011527613", preferences.chosenBenchmarkIsin.first())
    }

    @Test
    fun `en fond vars ISIN inte gar att sla upp sparas inte alls`() = runTest(dispatcher) {
        // Ett sparat fond-id som inget annat lager kan använda hade gett ett val som såg gjort
        // ut men aldrig gav en kurva.
        val preferences = PreferencesRepository(dataStore)
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo)

        vm.uiState.test {
            awaitItem()
            vm.chooseBenchmark(Fund(fundId = "SHB1", name = "Fond utan ISIN", isin = null))
            advanceUntilIdle()
            var state = awaitItem()
            while (!state.benchmarkPickFailed) state = awaitItem()

            assertTrue(state.benchmarkPickFailed)
            assertNull(preferences.chosenBenchmarkIsin.first())
            assertEquals(0, benchmarkScans)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `rensat val faller tillbaka pa appens referens`() = runTest(dispatcher) {
        val preferences = PreferencesRepository(dataStore)
        preferences.setChosenBenchmarkIsin("SE0011527613")
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo)

        vm.clearBenchmark()
        advanceUntilIdle()

        assertNull(preferences.chosenBenchmarkIsin.first())
    }

    @Test
    fun `det egna valet visas med fondens namn ur cachen`() = runTest(dispatcher) {
        val preferences = PreferencesRepository(dataStore)
        metadataByIsin = mapOf("SE0011527613" to benchmarkMetadata())
        preferences.setChosenBenchmarkIsin("SE0011527613")
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo)

        vm.uiState.test {
            var state = awaitItem()
            while (state.chosenBenchmarkName == null) state = awaitItem()

            assertEquals("Global Index", state.chosenBenchmarkName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett val vars namn inte finns i cachen visas som ISIN, aldrig tomt`() = runTest(dispatcher) {
        val preferences = PreferencesRepository(dataStore)
        preferences.setChosenBenchmarkIsin("SE0011527613")
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo)

        vm.uiState.test {
            var state = awaitItem()
            while (state.chosenBenchmarkName == null) state = awaitItem()

            assertEquals("SE0011527613", state.chosenBenchmarkName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun benchmarkMetadata() = FundMetadata(
        isin = "SE0011527613", name = "Global Index", orderbookId = "1", totalFee = 0.2, managementFee = 0.2,
        category = null, fundType = "Aktiefond", companyName = null, risk = 5, indexFund = true,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
    )
}
