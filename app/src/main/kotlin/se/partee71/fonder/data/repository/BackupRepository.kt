package se.partee71.fonder.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kontrakt för backup/restore av all användardata (regel 1 — datasäkerhet).
 *
 * Implementeras i backup-issuet (Google Drive appDataFolder). Stubben gör ingenting
 * ännu men befäster att backup-kedjan finns med i arkitekturen från start.
 */
interface BackupRepository {
    suspend fun backup(): Result<Unit>
    suspend fun restore(): Result<Unit>
}

@Singleton
class StubBackupRepository @Inject constructor() : BackupRepository {
    // TODO(backup-issue): implementera JSON-serialisering + Drive-rundtur med rundturstest.
    override suspend fun backup(): Result<Unit> = Result.success(Unit)
    override suspend fun restore(): Result<Unit> = Result.success(Unit)
}
