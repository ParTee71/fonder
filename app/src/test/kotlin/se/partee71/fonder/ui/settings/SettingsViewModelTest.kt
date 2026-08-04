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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.BackupFormatException
import se.partee71.fonder.data.repository.BackupRepository
import se.partee71.fonder.data.repository.RestoreSummary
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.worker.FundPriceRefreshScheduler

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private var clearAllCalled = false
    private var manualRefreshCalled = false
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

    private val fakeScheduler = object : FundPriceRefreshScheduler {
        override fun scheduleOnLaunch() {}
        override fun scheduleBackstop() {}
        override fun triggerManualRefresh() {
            manualRefreshCalled = true
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

    private fun viewModel() = SettingsViewModel(PreferencesRepository(dataStore), fakeTransactionRepo, fakeScheduler, fakeBackupRepo)

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
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo)

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
}
