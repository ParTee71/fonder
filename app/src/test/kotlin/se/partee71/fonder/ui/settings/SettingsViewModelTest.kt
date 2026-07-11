package se.partee71.fonder.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.TransactionRepository
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

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("settings_test.preferences_pb") },
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SettingsViewModel(PreferencesRepository(dataStore), fakeTransactionRepo, fakeScheduler)

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
        val vm = SettingsViewModel(preferences, fakeTransactionRepo, fakeScheduler)

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
}
