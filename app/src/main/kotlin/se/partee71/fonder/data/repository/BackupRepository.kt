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
    // Sedan issue #70 även: PreferencesRepository.accountType (SET-4) och samtliga rader i
    // Room-tabellen suggestion_records (se.partee71.fonder.domain.model.SuggestionRecord,
    // HEM-8) — förslagstidpunkten och NAV-utgångsläget kan inte återskapas ur NAV-historiken
    // i efterhand, till skillnad från fund_metadata som medvetet är cache utanför kontraktet.
    // Sedan issue #75 bär samma rader även switchValueKr (beloppet förslaget avsåg) — lika
    // oåterskapbart som NAV-utgångsläget, eftersom planen storleksbestäms mot portföljen så
    // som den såg ut just den dagen — samt batchEpochMillis, som håller ihop raderna från
    // *en* körning till en plan. Utan det fältet i en återställning smälter två körningar
    // samma dygn ihop till en plan som aldrig räknats fram.
    // Sedan issue #80 fylls samma raders followed i av användaren (SET-5, "Genomförd") — den
    // enda kolumnen i tabellen som är ett *val* snarare än en mätning, och därmed den som är
    // helt omöjlig att härleda: tappas den kan facit aldrig skilja ett följt råd från ett bara
    // givet igen. Rundturstestet ska täcka den explicit, inte bara "en rad överlever".
    override suspend fun backup(): Result<Unit> = Result.success(Unit)
    override suspend fun restore(): Result<Unit> = Result.success(Unit)
}
