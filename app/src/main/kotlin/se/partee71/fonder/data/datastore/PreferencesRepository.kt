package se.partee71.fonder.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.BackupRecord
import se.partee71.fonder.domain.model.BackupTarget
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.RiskProfile

@Serializable
enum class ThemeMode { LIGHT, DARK, AUTO }

/**
 * Single source of truth för app-inställningar (DataStore Preferences).
 * Läser och skriver tema-läget; utökas i takt med att inställningar tillkommer.
 */
@Singleton
class PreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    /**
     * Alla läsningar går via det här flödet i stället för `dataStore.data` direkt: en oläsbar
     * inställningsfil ska ge **standardvärden**, inte ett undantag som kastas in i varje
     * prenumerant. `ReplaceFileCorruptionHandler` i `StorageModule` täcker en trasig fil på disk,
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
    private val switchPlanEmptiedAtKey = longPreferencesKey("switch_plan_emptied_at_epoch_millis")
    private val riskProfileKey = stringPreferencesKey("risk_profile")
    private val accountTypeKey = stringPreferencesKey("account_type")

    /** Issue #96:s ensamma ISIN — läses fortfarande, skrivs aldrig mer. Se [benchmark]. */
    private val benchmarkIsinKey = stringPreferencesKey("benchmark_isin")
    private val benchmarkKey = stringPreferencesKey("benchmark_components")
    private val chosenBenchmarkKey = stringPreferencesKey("chosen_benchmark_isin")

    /** Före HEM-12: bara tidsstämpeln för den dygnsvisa molnbackupen. Läses som reserv, skrivs aldrig mer. Se [lastBackup]. */
    private val legacyLastDriveBackupKey = longPreferencesKey("last_drive_backup_epoch_millis")
    private val lastBackupEpochKey = longPreferencesKey("last_backup_epoch_millis")
    private val lastBackupTargetKey = stringPreferencesKey("last_backup_target")
    private val lastBackupFingerprintKey = stringPreferencesKey("last_backup_fingerprint")
    private val lastBackupTransactionCountKey = intPreferencesKey("last_backup_transaction_count")
    private val driveBackupNeedsAuthKey = booleanPreferencesKey("drive_backup_needs_auth")

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
     * Senaste lyckade säkerhetskopia eller återställning på den här enheten (SET-7, HEM-12), null
     * om ingen gjorts. Hem jämför fingeravtrycket med dagens innehåll och påminner vid skillnad.
     *
     * **Enhetsmetadata, inte användardata** — ingår medvetet inte i backup-kontraktet (NFR-1),
     * av samma skäl som [lastPriceSyncEpochMillis]: att återställa "senast säkerhetskopierad" från
     * en annan telefon vore missvisande.
     *
     * Bakåtkompatibilitet: före HEM-12 sparades bara den dygnsvisa molnbackupens tidsstämpel. Den
     * läses som en Drive-kopia utan fingeravtryck — datumet visas, och Hem påminner tills en ny
     * kopia tagits (vilket är rätt: den automatiska kopian finns inte längre).
     */
    val lastBackup: Flow<BackupRecord?> = preferences.map { prefs ->
        prefs[lastBackupEpochKey]?.let { epoch ->
            BackupRecord(
                epochMillis = epoch,
                target = prefs[lastBackupTargetKey]?.let { runCatching { BackupTarget.valueOf(it) }.getOrNull() },
                fingerprint = prefs[lastBackupFingerprintKey],
                transactionCount = prefs[lastBackupTransactionCountKey]
            )
        } ?: prefs[legacyLastDriveBackupKey]?.let { epoch ->
            BackupRecord(epochMillis = epoch, target = BackupTarget.DRIVE, fingerprint = null, transactionCount = null)
        }
    }

    /** Sparar [record] i **en** skrivning, så Hem aldrig ser ett nytt datum med ett gammalt fingeravtryck. */
    suspend fun setLastBackup(record: BackupRecord) {
        dataStore.edit { prefs ->
            prefs[lastBackupEpochKey] = record.epochMillis
            record.target?.let { prefs[lastBackupTargetKey] = it.name } ?: prefs.remove(lastBackupTargetKey)
            record.fingerprint?.let { prefs[lastBackupFingerprintKey] = it } ?: prefs.remove(lastBackupFingerprintKey)
            record.transactionCount?.let { prefs[lastBackupTransactionCountKey] = it }
                ?: prefs.remove(lastBackupTransactionCountKey)
            prefs.remove(legacyLastDriveBackupKey)
        }
    }

    /**
     * Sant när Drive-scopet (`drive.appdata`) inte är beviljat, så Inställningar kan erbjuda
     * knappen som löser det. Sätts när en säkerhetskopiering eller återställning mot Drive
     * stoppats av just det — bara användaren kan lösa saken, i Googles egen ruta.
     * Cache-metadata, utanför backup-kontraktet (samma skäl som ovan).
     */
    val driveBackupNeedsAuth: Flow<Boolean> =
        preferences.map { prefs -> prefs[driveBackupNeedsAuthKey] ?: false }

    suspend fun setDriveBackupNeedsAuth(needsAuth: Boolean) {
        dataStore.edit { it[driveBackupNeedsAuthKey] = needsAuth }
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
     * Körnings-id (`batchEpochMillis`) för den senaste bytesplansskanning som gav en **tom** plan
     * (HEM-8, issue #143), null om ingen gjort det. En tom plan går inte att spela in som rader i
     * `suggestion_records`, så utan markören hade den senast inspelade (icke-tomma) körningen
     * fortsatt visas som "senaste planen" i upp till `PLAN_TTL_DAYS` efter att bytet gjorts.
     * [se.partee71.fonder.domain.usecase.SwitchPlanResolver.resolve] visar bara en körning som är
     * nyare än markören.
     *
     * **Härledd cache, inte användardata** — skanningen räknar fram den igen vid nästa körning
     * (backstop, transaktion, avstående), och ingår därför medvetet inte i backup-kontraktet
     * (samma princip som [lastPriceSyncEpochMillis]). En återställning (SET-6) **nollställer**
     * markören ([clearSwitchPlanEmptiedAt]): den hör till den här enhetens egen skanning, och en
     * markör som råkar vara nyare än den återställda planens körning hade annars dolt en plan som
     * aldrig räknats bort — planen gäller tills nästa skanning säger något annat.
     */
    val switchPlanEmptiedAtEpochMillis: Flow<Long?> = preferences.map { prefs -> prefs[switchPlanEmptiedAtKey] }

    suspend fun setSwitchPlanEmptiedAt(batchEpochMillis: Long) {
        dataStore.edit { it[switchPlanEmptiedAtKey] = batchEpochMillis }
    }

    suspend fun clearSwitchPlanEmptiedAt() {
        dataStore.edit { it.remove(switchPlanEmptiedAtKey) }
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

    /**
     * ISIN för den referens **användaren själv valt** (HEM-10, issue #102), null om inget val
     * gjorts — då används appens automatiska blandning ([benchmark]).
     *
     * Skilt fält från [benchmark] med flit: annars gick det inte att skilja "användaren valde X"
     * från "appen råkade välja X", och nästa bakgrundsskanning hade kunnat skriva över ett
     * medvetet val. Samma distinktion som SET-3 gör mellan enkätens förslag och den sparade
     * fördelningen.
     *
     * Till skillnad från [benchmark] är det här **genuin användardata** — ett val, inte något
     * härlett — och ingår därför i backup-kontraktet (NFR-1), se
     * [se.partee71.fonder.data.repository.BackupPayload].
     */
    val chosenBenchmarkIsin: Flow<String?> = preferences.map { prefs -> prefs[chosenBenchmarkKey] }

    suspend fun setChosenBenchmarkIsin(isin: String) {
        dataStore.edit { it[chosenBenchmarkKey] = isin }
    }

    /** Rensar det egna valet, så appens automatiska referens gäller igen. */
    suspend fun clearChosenBenchmarkIsin() {
        dataStore.edit { it.remove(chosenBenchmarkKey) }
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
