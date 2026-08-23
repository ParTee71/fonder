package se.partee71.fonder.ui.settings

import android.app.PendingIntent
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import io.mockk.mockk
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
import se.partee71.fonder.data.auth.AuthRepository
import se.partee71.fonder.data.auth.AuthUser
import se.partee71.fonder.data.auth.SignInException
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.BackupFormatException
import se.partee71.fonder.data.repository.BackupRepository
import se.partee71.fonder.data.repository.DriveBackupRepository
import se.partee71.fonder.data.repository.DriveResult
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
import se.partee71.fonder.domain.usecase.DriveBackupFile
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
        override fun scheduleDriveBackup() {}
        override fun triggerDriveBackupNow() {}
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

    /**
     * Inloggat läge som testet styr. Fake i stället för mock av samma skäl som resten av
     * datalagret här: `currentUser` är ett hett flöde som ska kunna emittera mitt i ett test —
     * det är just den vägen den riktiga [se.partee71.fonder.data.auth.FirebaseAuthRepository]
     * rapporterar en session som gått ut.
     */
    private val authUser = MutableStateFlow<AuthUser?>(null)
    private var signInResult: Result<AuthUser> = Result.success(AuthUser("uid-1", "Test Testsson", "test@example.com"))
    private var signOutCalled = false

    private val fakeAuthRepo = object : AuthRepository {
        override val currentUser: Flow<AuthUser?> = authUser
        override suspend fun signInWithGoogle(activityContext: Context): Result<AuthUser> =
            signInResult.onSuccess { authUser.value = it }
        override suspend fun signOut() {
            signOutCalled = true
            authUser.value = null
        }
    }

    /** Drive-transporten som testet styr (SET-7). Fake, inte mock — samma princip som övriga datalager. */
    private var driveUploadResult: DriveResult<String> = DriveResult.Success("file-id")
    private var driveDownloadResult: DriveResult<String> = DriveResult.Success("{}")
    private var uploadedJson: String? = null

    private val fakeDriveRepo = object : DriveBackupRepository {
        override suspend fun upload(json: String): DriveResult<String> {
            uploadedJson = json
            return driveUploadResult
        }
        override suspend fun downloadLatest(): DriveResult<String> = driveDownloadResult
        override suspend fun list(): DriveResult<List<DriveBackupFile>> = DriveResult.Success(emptyList())
    }

    /** Uppsamlade auktoriseringsintent — UI:t startar dem, testet räknar dem. */
    private val authorizationRequests = mutableListOf<PendingIntent>()

    private fun viewModel() = SettingsViewModel(PreferencesRepository(dataStore), fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo, fakeAuthRepo, fakeDriveRepo)

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
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo, fakeAuthRepo, fakeDriveRepo)

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
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo, fakeAuthRepo, fakeDriveRepo)

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
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo, fakeAuthRepo, fakeDriveRepo)

        vm.chooseBenchmark(Fund(fundId = "SHB1", name = "Global Index", isin = null))
        advanceUntilIdle()

        assertEquals("SE0011527613", preferences.chosenBenchmarkIsin.first())
    }

    @Test
    fun `en fond vars ISIN inte gar att sla upp sparas inte alls`() = runTest(dispatcher) {
        // Ett sparat fond-id som inget annat lager kan använda hade gett ett val som såg gjort
        // ut men aldrig gav en kurva.
        val preferences = PreferencesRepository(dataStore)
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo, fakeAuthRepo, fakeDriveRepo)

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
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo, fakeAuthRepo, fakeDriveRepo)

        vm.clearBenchmark()
        advanceUntilIdle()

        assertNull(preferences.chosenBenchmarkIsin.first())
    }

    @Test
    fun `det egna valet visas med fondens namn ur cachen`() = runTest(dispatcher) {
        val preferences = PreferencesRepository(dataStore)
        metadataByIsin = mapOf("SE0011527613" to benchmarkMetadata())
        preferences.setChosenBenchmarkIsin("SE0011527613")
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo, fakeAuthRepo, fakeDriveRepo)

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
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo, fakeAuthRepo, fakeDriveRepo)

        vm.uiState.test {
            var state = awaitItem()
            while (state.chosenBenchmarkName == null) state = awaitItem()

            assertEquals("SE0011527613", state.chosenBenchmarkName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Google-inloggning (TP-6) ---

    /**
     * Credential Manager kräver ett aktivitets-context, men fakear det aldrig — en relaxad mock
     * räcker, eftersom fakerepot ignorerar värdet. Poängen är att ViewModel:en ska gå att testa
     * utan Firebase på klassvägen, vilket är hela skälet till att AuthRepository lämnar ut
     * [AuthUser] och inte FirebaseUser.
     */
    private val activityContext: Context = mockk(relaxed = true)

    @Test
    fun `en lyckad inloggning speglas i uiState via currentUser`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.uiState.test {
            assertNull(awaitItem().googleUser)

            vm.signIn(activityContext)
            var state = awaitItem()
            while (state.googleUser == null) state = awaitItem()

            assertEquals("test@example.com", state.googleUser?.email)
            assertNull(state.signInError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett avbrutet kontoval ger inget felmeddelande`() = runTest(dispatcher) {
        // Att stänga kontoväljaren är ett val, inte ett fel. Visades det som ett fick användaren
        // en röd rad för något hen själv just gjorde med flit.
        signInResult = Result.failure(SignInException(SignInException.Reason.CANCELLED))
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.signIn(activityContext)
            advanceUntilIdle()

            // Läser StateFlow:ens värde i stället för att vänta på en emission: utebliven
            // emission är själva poängen här, och ett awaitItem/expectMostRecentItem hade
            // gjort testet beroende av om combine råkar konflatera signInInProgress-svängen
            // eller inte. Prenumerationen finns (vi står i test-blocket), så .value är aktuellt.
            assertNull(vm.uiState.value.signInError)
            assertFalse(vm.uiState.value.signInInProgress)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett riktigt fel visas med sin orsak`() = runTest(dispatcher) {
        signInResult = Result.failure(SignInException(SignInException.Reason.NO_CREDENTIAL))
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.signIn(activityContext)
            var state = awaitItem()
            while (state.signInError == null) state = awaitItem()

            assertEquals(SignInException.Reason.NO_CREDENTIAL, state.signInError)
            assertNull(state.googleUser)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett fel utan orsak faller tillbaka pa FAILED i stallet for att forsvinna`() = runTest(dispatcher) {
        // Ett undantag som inte är en SignInException (kastat under vägen, inte mappat i
        // repositoryt) får inte ge ett tyst misslyckande där knappen bara slutar snurra.
        signInResult = Result.failure(IllegalStateException("oväntat"))
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.signIn(activityContext)
            var state = awaitItem()
            while (state.signInError == null) state = awaitItem()

            assertEquals(SignInException.Reason.FAILED, state.signInError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onSignInErrorDismissed kvitterar felet sa det inte spelas upp igen`() = runTest(dispatcher) {
        // Samma engångshändelse-princip som issue #78: uiState är WhileSubscribed, så ett fel
        // som ligger kvar som tillstånd kommer tillbaka vid varje återbesök i Inställningar.
        signInResult = Result.failure(SignInException(SignInException.Reason.FAILED))
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.signIn(activityContext)
            var state = awaitItem()
            while (state.signInError == null) state = awaitItem()

            vm.onSignInErrorDismissed()
            state = awaitItem()
            while (state.signInError != null) state = awaitItem()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `signOut loggar ut och tommer anvandaren i uiState`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.signIn(activityContext)
            var state = awaitItem()
            while (state.googleUser == null) state = awaitItem()

            vm.signOut()
            state = awaitItem()
            while (state.googleUser != null) state = awaitItem()

            assertTrue(signOutCalled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dubbeltryck pa logga in startar inte tva samtidiga inloggningar`() = runTest(dispatcher) {
        // Samma spärr som export/restore (issue #75): kontoväljaren ger ingen synlig
        // återkoppling förrän den öppnats, och två parallella anrop ger två väljare.
        var calls = 0
        val countingAuthRepo = object : AuthRepository {
            override val currentUser: Flow<AuthUser?> = authUser
            override suspend fun signInWithGoogle(activityContext: Context): Result<AuthUser> {
                calls++
                return signInResult.onSuccess { authUser.value = it }
            }
            override suspend fun signOut() = Unit
        }
        val vm = SettingsViewModel(
            PreferencesRepository(dataStore), fakeTransactionRepo, fakeScheduler,
            fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo, countingAuthRepo, fakeDriveRepo,
        )

        vm.uiState.test {
            awaitItem()
            vm.signIn(activityContext)
            vm.signIn(activityContext)
            advanceUntilIdle()

            assertEquals(1, calls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Molnbackup till Drive (SET-7) ---

    private fun collectAuth(): (PendingIntent) -> Unit = { authorizationRequests += it }

    @Test
    fun `backupToDrive laddar upp den exporterade strangen`() = runTest(dispatcher) {
        exportResult = Result.success("""{"formatVersion":1}""")
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.backupToDrive(collectAuth())
            var state = awaitItem()
            while (state.backupMessage == null) state = awaitItem()

            assertEquals("""{"formatVersion":1}""", uploadedJson)
            assertEquals(BackupMessage.DriveSaved, state.backupMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en misslyckad export laddar aldrig upp nagot`() = runTest(dispatcher) {
        // Går kontraktet inte att serialisera är felet i formatet, inte i transporten — och då
        // ska ingenting skrivas till Drive.
        exportResult = Result.failure(IllegalStateException("trasigt"))
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.backupToDrive(collectAuth())
            var state = awaitItem()
            while (state.backupMessage == null) state = awaitItem()

            assertNull(uploadedJson)
            assertEquals(BackupMessage.ExportFailed, state.backupMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `utloggad ger ett eget meddelande, inte ett allmant fel`() = runTest(dispatcher) {
        driveUploadResult = DriveResult.NoAccount
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.backupToDrive(collectAuth())
            var state = awaitItem()
            while (state.backupMessage == null) state = awaitItem()

            assertEquals(BackupMessage.DriveFailed(signedOut = true), state.backupMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saknad auktorisering startar Googles ruta och visar inget felmeddelande`() = runTest(dispatcher) {
        // Användaren möts av Googles egen dialog — en röd rad bakom den vore förvirrande.
        driveUploadResult = DriveResult.NeedsAuthorization(mockk(relaxed = true))
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.backupToDrive(collectAuth())
            advanceUntilIdle()

            assertEquals(1, authorizationRequests.size)
            assertNull(vm.uiState.value.backupMessage)
            assertTrue(vm.uiState.value.driveBackupNeedsAuth)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onDriveAuthorizationResolved rensar flaggan`() = runTest(dispatcher) {
        driveUploadResult = DriveResult.NeedsAuthorization(mockk(relaxed = true))
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.backupToDrive(collectAuth())
            var state = awaitItem()
            while (!state.driveBackupNeedsAuth) state = awaitItem()

            vm.onDriveAuthorizationResolved()
            state = awaitItem()
            while (state.driveBackupNeedsAuth) state = awaitItem()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restoreFromDrive lamnar den hamtade strangen till backup-kontraktet`() = runTest(dispatcher) {
        driveDownloadResult = DriveResult.Success("""{"formatVersion":1,"funds":[]}""")
        restoreResult = Result.success(RestoreSummary(2, 3, 4))
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.restoreFromDrive(collectAuth())
            var state = awaitItem()
            while (state.backupMessage == null) state = awaitItem()

            assertEquals("""{"formatVersion":1,"funds":[]}""", restoredJson)
            assertEquals(BackupMessage.Restored(RestoreSummary(2, 3, 4)), state.backupMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en tom Drive-mapp ger ett eget meddelande och ror inte databasen`() = runTest(dispatcher) {
        driveDownloadResult = DriveResult.NoBackupFound
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.restoreFromDrive(collectAuth())
            var state = awaitItem()
            while (state.backupMessage == null) state = awaitItem()

            assertNull(restoredJson)
            assertEquals(BackupMessage.DriveEmpty, state.backupMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en trasig kopia i Drive ger samma fel som en trasig fil`() = runTest(dispatcher) {
        driveDownloadResult = DriveResult.Success("inte json")
        restoreResult = Result.failure(BackupFormatException(BackupFormatException.Reason.UNREADABLE))
        val vm = viewModel()

        vm.uiState.test {
            awaitItem()
            vm.restoreFromDrive(collectAuth())
            var state = awaitItem()
            while (state.backupMessage == null) state = awaitItem()

            assertEquals(
                BackupMessage.RestoreFailed(BackupFormatException.Reason.UNREADABLE),
                state.backupMessage,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dubbeltryck startar inte tva samtidiga Drive-korningar`() = runTest(dispatcher) {
        // Samma spärr som export/restore (issue #75): ingen synlig återkoppling förrän den är
        // klar, och två parallella skulle skriva två kopior.
        var uploads = 0
        val countingDrive = object : DriveBackupRepository {
            override suspend fun upload(json: String): DriveResult<String> {
                uploads++
                return DriveResult.Success("id")
            }
            override suspend fun downloadLatest(): DriveResult<String> = DriveResult.NoBackupFound
            override suspend fun list(): DriveResult<List<DriveBackupFile>> = DriveResult.Success(emptyList())
        }
        val vm = SettingsViewModel(
            PreferencesRepository(dataStore), fakeTransactionRepo, fakeScheduler, fakeBackupRepo,
            fakeFundPriceRepo, fakeFundMetadataRepo, fakeAuthRepo, countingDrive,
        )

        vm.uiState.test {
            awaitItem()
            vm.backupToDrive(collectAuth())
            vm.backupToDrive(collectAuth())
            advanceUntilIdle()

            assertEquals(1, uploads)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `senaste molnbackup speglar preferences`() = runTest(dispatcher) {
        val preferences = PreferencesRepository(dataStore)
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler, fakeBackupRepo, fakeFundPriceRepo, fakeFundMetadataRepo, fakeAuthRepo, fakeDriveRepo)

        vm.uiState.test {
            assertNull(awaitItem().lastDriveBackupEpochMillis)
            preferences.setLastDriveBackupEpochMillis(1_700_000_000_000L)
            var state = awaitItem()
            while (state.lastDriveBackupEpochMillis == null) state = awaitItem()

            assertEquals(1_700_000_000_000L, state.lastDriveBackupEpochMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun benchmarkMetadata() = FundMetadata(
        isin = "SE0011527613", name = "Global Index", orderbookId = "1", totalFee = 0.2, managementFee = 0.2,
        category = null, fundType = "Aktiefond", companyName = null, risk = 5, indexFund = true,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
    )
}
