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

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private var clearAllCalled = false
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

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("settings_test.preferences_pb") },
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SettingsViewModel(PreferencesRepository(dataStore), fakeTransactionRepo)

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
}
