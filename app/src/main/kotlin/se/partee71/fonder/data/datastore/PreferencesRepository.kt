package se.partee71.fonder.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.RiskProfile
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { LIGHT, DARK, AUTO }

/**
 * Single source of truth för app-inställningar (DataStore Preferences).
 * Läser och skriver tema-läget; utökas i takt med att inställningar tillkommer.
 */
@Singleton
class PreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val lastPriceSyncKey = longPreferencesKey("last_price_sync_epoch_millis")
    private val fundFilterVocabularyKey = stringPreferencesKey("fund_filter_vocabulary")
    private val riskProfileKey = stringPreferencesKey("risk_profile")
    private val accountTypeKey = stringPreferencesKey("account_type")

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[themeModeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.AUTO
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[themeModeKey] = mode.name }
    }

    /**
     * Tidpunkten (epoch-millisekunder) för senaste lyckade kursuppdatering, null om ingen
     * uppdatering skett än — visas som "Senast uppdaterad" i Inställningar (SET-2, issue #27).
     * Ren cache-metadata, ingår medvetet inte i backup-kontraktet (se issue #27).
     */
    val lastPriceSyncEpochMillis: Flow<Long?> = dataStore.data.map { prefs -> prefs[lastPriceSyncKey] }

    suspend fun setLastPriceSyncEpochMillis(epochMillis: Long) {
        dataStore.edit { it[lastPriceSyncKey] = epochMillis }
    }

    /**
     * Senast kända filtervokabulär för fondmetadata-frågor (KRAVLISTA TP-21) — vilka
     * fondtyper/regioner/branscher/fondbolag/risknivåer källan faktiskt känner till just nu.
     * Byggs enbart ur källans eget svar ([se.partee71.fonder.data.network.AvanzaFundListParser]),
     * aldrig hårdkodad. Tom vokabulär om ingen fråga körts än. Ren cache-metadata, ingår
     * medvetet inte i backup-kontraktet (samma princip som [lastPriceSyncEpochMillis]).
     */
    val fundFilterVocabulary: Flow<FundFilterVocabulary> = dataStore.data.map { prefs ->
        prefs[fundFilterVocabularyKey]
            ?.let { runCatching { Json.decodeFromString(FundFilterVocabulary.serializer(), it) }.getOrNull() }
            ?: FundFilterVocabulary()
    }

    suspend fun setFundFilterVocabulary(vocabulary: FundFilterVocabulary) {
        dataStore.edit { it[fundFilterVocabularyKey] = Json.encodeToString(FundFilterVocabulary.serializer(), vocabulary) }
    }

    /**
     * Användarens riskprofil (SET-3, issue #68), null om ingen är satt. Till skillnad från
     * [lastPriceSyncEpochMillis]/[fundFilterVocabulary] är det här **genuin användardata** —
     * inte härledd ur någon källa — och ska ingå i backup-kontraktet när Drive-backup (TP-7)
     * byggs, se [se.partee71.fonder.data.repository.StubBackupRepository].
     */
    val riskProfile: Flow<RiskProfile?> = dataStore.data.map { prefs ->
        prefs[riskProfileKey]?.let { runCatching { Json.decodeFromString(RiskProfile.serializer(), it) }.getOrNull() }
    }

    suspend fun setRiskProfile(profile: RiskProfile) {
        dataStore.edit { it[riskProfileKey] = Json.encodeToString(RiskProfile.serializer(), profile) }
    }

    /**
     * Kontotypen fondinnehaven ligger i (SET-4, issue #70), null om inget val gjorts —
     * bytesplanen (HEM-8) ges då aldrig, appen gissar aldrig kontotyp. Genuin användardata,
     * samma kategori som [riskProfile] — ska ingå i backup-kontraktet (NFR-1), se
     * [se.partee71.fonder.data.repository.StubBackupRepository].
     */
    val accountType: Flow<AccountType?> = dataStore.data.map { prefs ->
        prefs[accountTypeKey]?.let { runCatching { AccountType.valueOf(it) }.getOrNull() }
    }

    suspend fun setAccountType(type: AccountType) {
        dataStore.edit { it[accountTypeKey] = type.name }
    }
}
