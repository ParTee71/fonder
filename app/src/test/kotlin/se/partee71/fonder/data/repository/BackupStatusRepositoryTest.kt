package se.partee71.fonder.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.domain.model.BackupTarget
import se.partee71.fonder.domain.model.Fund

/** HEM-12/SET-7: anteckningen av en kopia och påminnelsen som räknas ur den. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupStatusRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val backup = FakeBackupRepository()

    private fun preferences() = PreferencesRepository(
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            produceFile = { tempFolder.newFile("backup_status.preferences_pb") }
        )
    )

    private val withData = BackupPayload(exportedAtEpochMillis = 0, funds = listOf(Fund(fundId = "SHB1", name = "Fond A")))

    @Test
    fun `recordBackup sparar datum, mal, fingeravtryck och antal`() = runTest(dispatcher) {
        val preferences = preferences()
        backup.payload.value = withData
        val repo = BackupStatusRepository(backup, preferences)

        repo.recordBackup(BackupSerializer.encode(withData), BackupTarget.FILE, nowEpochMillis = 1_700_000_000_000L)

        val record = repo.lastBackup.first()!!
        assertEquals(1_700_000_000_000L, record.epochMillis)
        assertEquals(BackupTarget.FILE, record.target)
        assertEquals(BackupFingerprint.of(withData), record.fingerprint)
        assertEquals(0, record.transactionCount)
    }

    @Test
    fun `fingeravtrycket tas ur den exporterade strangen, inte ur en ny lasning`() = runTest(dispatcher) {
        // En ändring mellan exporten och anteckningen finns inte i kopian — den får inte
        // markeras som säkerhetskopierad.
        val exported = BackupSerializer.encode(withData)
        backup.payload.value = withData.copy(funds = withData.funds + Fund(fundId = "SHB2", name = "Fond B"))
        val repo = BackupStatusRepository(backup, preferences())

        repo.recordBackup(exported, BackupTarget.DRIVE)

        assertEquals(BackupFingerprint.of(withData), repo.lastBackup.first()!!.fingerprint)
        repo.reminder.test {
            var reminder = awaitItem()
            while (reminder == null) reminder = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en olasbar strang antecknas inte`() = runTest(dispatcher) {
        val repo = BackupStatusRepository(backup, preferences())

        repo.recordBackup("inte json", BackupTarget.FILE)

        assertNull(repo.lastBackup.first())
    }

    @Test
    fun `en lasning som gar fel ger ingen paminnelse hellre an en felaktig`() = runTest(dispatcher) {
        backup.payload.value = withData
        backup.snapshotFailure = IllegalStateException("databasen svarar inte")
        val repo = BackupStatusRepository(backup, preferences())

        repo.reminder.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
