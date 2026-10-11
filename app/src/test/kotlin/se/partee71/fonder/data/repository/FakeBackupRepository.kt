package se.partee71.fonder.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Delad fake för [BackupRepository] — Inställningar (SET-6/SET-7), Hems påminnelse (HEM-12) och
 * [BackupStatusRepository] i JVM. [payload] är kontraktets innehåll "just nu"; varje ändring av
 * den signalerar [observeChanges], precis som en skrivning i Room eller DataStore gör.
 */
class FakeBackupRepository : BackupRepository {

    val payload = MutableStateFlow(BackupPayload(exportedAtEpochMillis = 0))

    var exportResult: Result<String> = Result.success("{}")
    var restoreResult: Result<RestoreSummary> = Result.success(RestoreSummary(0, 0, 0))
    var restoredJson: String? = null
        private set

    /** Sätts för att få [snapshot] att kasta, som en Room-läsning som går fel. */
    var snapshotFailure: Throwable? = null

    override suspend fun export(): Result<String> = exportResult

    override suspend fun restore(json: String): Result<RestoreSummary> {
        restoredJson = json
        return restoreResult
    }

    override suspend fun snapshot(): BackupPayload {
        snapshotFailure?.let { throw it }
        return payload.value
    }

    override fun observeChanges(): Flow<Unit> = payload.map { }
}
