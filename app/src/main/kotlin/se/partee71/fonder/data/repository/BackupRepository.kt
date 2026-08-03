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
    // Måste inkludera se.partee71.fonder.domain.model.RiskProfile — targetAllocation (Map<Int,
    // Double>, sedan issue #71) plus det kvarvarande legacy-fältet targetRiskLevel
    // (se.partee71.fonder.data.datastore.PreferencesRepository.riskProfile, SET-3/issue #68) —
    // genuin användardata, till skillnad från samma repositorys lastPriceSyncEpochMillis/
    // fundFilterVocabulary som medvetet är cache-metadata utanför kontraktet.
    override suspend fun backup(): Result<Unit> = Result.success(Unit)
    override suspend fun restore(): Result<Unit> = Result.success(Unit)
}
