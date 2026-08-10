package se.partee71.fonder.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.RiskProfile
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
enum class ThemeMode { LIGHT, DARK, AUTO }

/**
 * Single source of truth för app-inställningar (DataStore Preferences).
 * Läser och skriver tema-läget; utökas i takt med att inställningar tillkommer.
 */
@Singleton
class PreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /**
     * Alla läsningar går via det här flödet i stället för `dataStore.data` direkt: en oläsbar
     * inställningsfil ska ge **standardvärden**, inte ett undantag som kastas in i varje
     * prenumerant. `ReplaceFileCorruptionHandler` i `AppModule` täcker en trasig fil på disk,
     * men ett I/O-fel vid läsningen (t.ex. under en Auto Backup-återställning) kan fortfarande
     * nå hit — och `MainViewModel.themeMode` samlas i `viewModelScope`, där ett undantag
     * annars låser appen på splash-skärmen.
     */
    private val preferences: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val lastPriceSyncKey = longPreferencesKey("last_price_sync_epoch_millis")
    private val fundFilterVocabularyKey = stringPreferencesKey("fund_filter_vocabulary")
    private val riskProfileKey = stringPreferencesKey("risk_profile")
    private val accountTypeKey = stringPreferencesKey("account_type")
    /** Issue #96:s ensamma ISIN — läses fortfarande, skrivs aldrig mer. Se [benchmark]. */
    private val benchmarkIsinKey = stringPreferencesKey("benchmark_isin")
    private val benchmarkKey = stringPreferencesKey("benchmark_components")

    val themeMode: Flow<ThemeMode> = preferences.map { prefs ->
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
    val lastPriceSyncEpochMillis: Flow<Long?> = preferences.map { prefs -> prefs[lastPriceSyncKey] }

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
    val fundFilterVocabulary: Flow<FundFilterVocabulary> = preferences.map { prefs ->
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
     * inte härledd ur någon källa — och ingår därför i backup-kontraktet (NFR-1), se
     * [se.partee71.fonder.data.repository.BackupPayload].
     */
    val riskProfile: Flow<RiskProfile?> = preferences.map { prefs ->
        prefs[riskProfileKey]?.let { runCatching { Json.decodeFromString(RiskProfile.serializer(), it) }.getOrNull() }
    }

    suspend fun setRiskProfile(profile: RiskProfile) {
        dataStore.edit { it[riskProfileKey] = Json.encodeToString(RiskProfile.serializer(), profile) }
    }

    /**
     * Kontotypen fondinnehaven ligger i (SET-4, issue #70), null om inget val gjorts —
     * bytesplanen (HEM-8) ges då aldrig, appen gissar aldrig kontotyp. Genuin användardata,
     * samma kategori som [riskProfile] — ingår i backup-kontraktet (NFR-1), se
     * [se.partee71.fonder.data.repository.BackupPayload].
     */
    val accountType: Flow<AccountType?> = preferences.map { prefs ->
        prefs[accountTypeKey]?.let { runCatching { AccountType.valueOf(it) }.getOrNull() }
    }

    suspend fun setAccountType(type: AccountType) {
        dataStore.edit { it[accountTypeKey] = type.name }
    }

    /**
     * Referensblandningen portföljens avkastning jämförs mot (HEM-10) — tom lista tills
     * bakgrundsjobbet hunnit välja. Valet görs av
     * [se.partee71.fonder.domain.usecase.IndexBenchmarkSelector] ur källans katalog och sparas
     * här av just det skälet: hade det härletts på nytt vid varje läsning kunde referensen
     * bytas så fort katalogen ändrades, och jämförelsekurvan hade ritats om utan att något
     * hänt i portföljen.
     *
     * **Härledd cache-metadata, inte ett användarval** — samma kategori som
     * [lastPriceSyncEpochMillis]/[fundFilterVocabulary] och därför medvetet utanför
     * backup-kontraktet (NFR-1): går den förlorad väljs samma fonder ut igen ur samma katalog.
     * Den dagen användaren själv får välja referens (issue #102) blir det ett annat, genuint
     * fält som ska in i kontraktet.
     */
    val benchmark: Flow<List<BenchmarkComponentRef>> = preferences.map { prefs ->
        val stored = prefs[benchmarkKey]
            ?.let { runCatching { Json.decodeFromString(ListSerializer(BenchmarkComponentRef.serializer()), it) }.getOrNull() }
        // Bakåtkompatibilitet: issue #96 sparade ett ensamt ISIN. Det läses som en
        // enkomponentsblandning i stället för att kastas — annars hade uppgraderingen tappat
        // referensfonden och tvingat fram en ny källfråga och en full backfill i onödan.
        stored ?: prefs[benchmarkIsinKey]?.let { listOf(BenchmarkComponentRef(isin = it, weight = 1.0)) } ?: emptyList()
    }

    suspend fun setBenchmark(components: List<BenchmarkComponentRef>) {
        dataStore.edit { prefs ->
            prefs[benchmarkKey] = Json.encodeToString(ListSerializer(BenchmarkComponentRef.serializer()), components)
            prefs.remove(benchmarkIsinKey)
        }
    }
}

/**
 * En sparad del av Hems referensblandning (HEM-10): fondens ISIN och dess vikt. Vikterna
 * summerar till 1,0 — se [se.partee71.fonder.domain.usecase.IndexBenchmarkSelector.Benchmark].
 */
@Serializable
data class BenchmarkComponentRef(val isin: String, val weight: Double)
