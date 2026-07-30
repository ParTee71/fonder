package se.partee71.fonder.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import se.partee71.fonder.domain.model.FundFilterVocabulary
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
            ?.let { runCatching { Json.decodeFromString<FundFilterVocabulary>(it) }.getOrNull() }
            ?: FundFilterVocabulary()
    }

    suspend fun setFundFilterVocabulary(vocabulary: FundFilterVocabulary) {
        dataStore.edit { it[fundFilterVocabularyKey] = Json.encodeToString(vocabulary) }
    }
}
