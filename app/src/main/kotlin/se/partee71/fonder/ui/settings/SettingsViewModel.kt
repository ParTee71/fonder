package se.partee71.fonder.ui.settings

import android.app.PendingIntent
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import se.partee71.fonder.data.auth.AuthRepository
import se.partee71.fonder.data.auth.AuthUser
import se.partee71.fonder.data.auth.SignInException
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.datastore.ThemeMode
import se.partee71.fonder.data.repository.BackupFormatException
import se.partee71.fonder.data.repository.BackupRepository
import se.partee71.fonder.data.repository.DriveBackupRepository
import se.partee71.fonder.data.repository.DriveResult
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.RestoreSummary
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.worker.BackgroundWork
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import javax.inject.Inject

/**
 * Utfallet av en export eller återställning (SET-6) — en **händelse** som kvitteras bort, aldrig
 * ett bestående tillstånd (samma lärdom som "databasen tömd", issue #78).
 */
sealed interface BackupMessage {
    data object Exported : BackupMessage
    data object ExportFailed : BackupMessage
    data class Restored(val summary: RestoreSummary) : BackupMessage

    /** [reason] skiljer "filen är från en nyare app" från "filen är trasig" — olika åtgärd för användaren. */
    data class RestoreFailed(val reason: BackupFormatException.Reason) : BackupMessage

    /** Molnbackupen lyckades (SET-7). */
    data object DriveSaved : BackupMessage

    /** Ingen kopia fanns i Drive att återställa från. */
    data object DriveEmpty : BackupMessage

    /** Drive svarade fel, eller ingen är inloggad. [signedOut] skiljer de två — olika åtgärd. */
    data class DriveFailed(val signedOut: Boolean) : BackupMessage
}

/** Kontodelen av tillståndet (TP-6) — se [SettingsViewModel.uiState] för varför den combine:as in separat. */
private data class AccountState(
    val user: AuthUser? = null,
    val signInInProgress: Boolean = false,
    val signInError: SignInException.Reason? = null,
)

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val databaseCleared: Boolean = false,
    /** Epoch-millisekunder för senaste lyckade kursuppdatering, null om ingen skett än (SET-2, issue #27). */
    val lastPriceSyncEpochMillis: Long? = null,
    /** Kontotypen fondinnehaven ligger i, null om inget val gjorts (SET-4, issue #70) — styr om bytesplanen (HEM-8) ges alls. */
    val accountType: AccountType? = null,
    /** Sant medan en export eller återställning pågår — knapparna släcks, se [SettingsViewModel.export] (SET-6, issue #82). */
    val backupInProgress: Boolean = false,
    val backupMessage: BackupMessage? = null,
    /**
     * Namnet på den referens användaren själv valt för Hems indexjämförelse (HEM-10, issue
     * #102), null när appens automatiska blandning gäller. Namnet läses ur metadatacachen —
     * går det inte att slå upp visas ISIN:et, så raden aldrig ser tom ut för ett gjort val.
     */
    val chosenBenchmarkName: String? = null,
    /**
     * Sant när den senast valda fonden inte gick att använda: katalogens träffar saknar ISIN,
     * och utan ISIN kan kursen varken hämtas eller cachas. Raden säger det i stället för att
     * tyst behålla den gamla referensen.
     */
    val benchmarkPickFailed: Boolean = false,
    /** Inloggad Google-användare, null när ingen är inloggad (TP-6). */
    val googleUser: AuthUser? = null,
    /** Sant medan kontoväljaren är uppe — knappen släcks, samma princip som [backupInProgress]. */
    val signInInProgress: Boolean = false,
    /**
     * Varför senaste inloggningsförsök misslyckades, null när inget fel finns att visa.
     * `CANCELLED` hamnar aldrig här: att stänga kontoväljaren är ett val, inte ett fel.
     */
    val signInError: SignInException.Reason? = null,
    /** Epoch-millisekunder för senaste lyckade molnbackup till Drive, null om ingen körts än (SET-7). */
    val lastDriveBackupEpochMillis: Long? = null,
    /** Sant när Drive-scopet saknas — kortet erbjuder då knappen som löser det (SET-7). */
    val driveBackupNeedsAuth: Boolean = false,
    /** En kursuppdatering kör just nu ([BackgroundWork.PRICE_REFRESH]) — kurskortets väntesnurra (NAV-6). */
    val priceRefreshWorking: Boolean = false,
    /** Molnbackupen kör just nu ([BackgroundWork.DRIVE_BACKUP]) — bara backup-kortet berörs. */
    val driveBackupWorking: Boolean = false,
) {
    /**
     * Backup-kortet snurrar både för en knapptryckning här (export/återställ, [backupInProgress])
     * och för den dygnsvisa molnkörningen (SET-7) — kortet redovisar båda, och användaren ska
     * inte behöva veta vilken av dem som startade arbetet.
     */
    val backupWorking: Boolean get() = backupInProgress || driveBackupWorking
}

/** Referensvalets del av tillståndet — se [SettingsViewModel.uiState] för varför den combine:as in separat. */
private data class BenchmarkState(val chosenName: String? = null, val pickFailed: Boolean = false)

/** Backup-delen av tillståndet, hållen för sig så [SettingsViewModel.uiState] kan `combine`:a in den. */
private data class BackupState(
    val inProgress: Boolean = false,
    val message: BackupMessage? = null,
    val lastDriveBackupEpochMillis: Long? = null,
    val driveNeedsAuth: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRefreshScheduler: FundPriceRefreshScheduler,
    private val backupRepository: BackupRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val authRepository: AuthRepository,
    private val driveBackupRepository: DriveBackupRepository,
) : ViewModel() {

    private val databaseCleared = MutableStateFlow(false)
    private val backupProgress = MutableStateFlow(BackupState())

    /**
     * Drive-metadatan bor i DataStore och inte i [backupProgress]: den skrivs även av
     * [se.partee71.fonder.worker.DriveBackupWorker] i bakgrunden, och en lokal spegling hade
     * visat ett inaktuellt "senast säkerhetskopierad" tills skärmen öppnades om.
     */
    private val backupState: Flow<BackupState> = combine(
        backupProgress,
        preferences.lastDriveBackupEpochMillis,
        preferences.driveBackupNeedsAuth,
    ) { progress, lastDrive, needsAuth ->
        progress.copy(lastDriveBackupEpochMillis = lastDrive, driveNeedsAuth = needsAuth)
    }
    private var backupJob: Job? = null
    private val benchmarkPickFailed = MutableStateFlow(false)
    private val signInInProgress = MutableStateFlow(false)
    private val signInError = MutableStateFlow<SignInException.Reason?>(null)
    private var signInJob: Job? = null

    /** Egen gren av samma skäl som [benchmarkState]: `combine` tar högst fem flöden. */
    private val accountState: Flow<AccountState> =
        combine(authRepository.currentUser, signInInProgress, signInError) { user, inProgress, error ->
            AccountState(user = user, signInInProgress = inProgress, signInError = error)
        }

    /**
     * Egen gren, inte en sjätte flöde i combinen nedan: `combine` tar högst fem, och
     * referensvalet har ingenting med tema, backup eller kontotyp att göra — det ska inte kunna
     * räkna om resten av inställningarna bara för att ett namn slogs upp.
     */
    private val benchmarkState: Flow<BenchmarkState> =
        combine(preferences.chosenBenchmarkIsin, benchmarkPickFailed) { isin, failed ->
            BenchmarkState(
                chosenName = isin?.let { fundMetadataRepository.cachedMetadataFor(listOf(it))[it]?.name ?: it },
                pickFailed = failed,
            )
        }

    val uiState: StateFlow<SettingsUiState> =
        combine(
            preferences.themeMode,
            databaseCleared,
            preferences.lastPriceSyncEpochMillis,
            preferences.accountType,
            backupState,
        ) { themeMode, cleared, lastSync, accountType, backup ->
            SettingsUiState(
                themeMode = themeMode,
                databaseCleared = cleared,
                lastPriceSyncEpochMillis = lastSync,
                accountType = accountType,
                backupInProgress = backup.inProgress,
                backupMessage = backup.message,
                lastDriveBackupEpochMillis = backup.lastDriveBackupEpochMillis,
                driveBackupNeedsAuth = backup.driveNeedsAuth,
            )
        }.combine(benchmarkState) { state, benchmark ->
            state.copy(
                chosenBenchmarkName = benchmark.chosenName,
                benchmarkPickFailed = benchmark.pickFailed,
            )
        }.combine(accountState) { state, account ->
            state.copy(
                googleUser = account.user,
                signInInProgress = account.signInInProgress,
                signInError = account.signInError,
            )
        }.combine(fundPriceRefreshScheduler.observeRunningWork()) { state, running ->
            // Eget led i kedjan av samma skäl som grenarna ovan: körstatusen kommer från
            // WorkManager och ska inte kunna räkna om tema, kontotyp eller backup-tillstånd.
            state.copy(
                priceRefreshWorking = BackgroundWork.PRICE_REFRESH in running,
                driveBackupWorking = BackgroundWork.DRIVE_BACKUP in running,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(),
        )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    /**
     * Sparar kontotypen (SET-4) och, vid byte **till** ISK/KF, ber om en omräkning av
     * bytesplanen (HEM-8, issue #88): SET-4-gaten gör att ingen plan alls ges i depå/AF, så
     * det är först i och med bytet hit som en plan kan finnas — utan triggern dröjer den till
     * nästa backstop, upp till 12 timmar bort.
     *
     * Bara vid faktiskt byte: att välja samma kontotyp igen ska inte kosta en skanning. Byte
     * *till* depå/AF triggar ingenting alls — där ges ingen plan att räkna om.
     */
    /**
     * Sparar [fund] som referens för Hems indexjämförelse (HEM-10, issue #102). Katalogens
     * träffar saknar ISIN, så det slås upp när det behövs — hela jämförelsekedjan är
     * ISIN-nycklad, från kurscachen till skuggportföljen.
     *
     * Går ISIN:et inte att slå upp **sparas ingenting** och raden säger varför: ett sparat
     * fond-id som inget annat lager kan använda hade gett ett val som såg gjort ut men aldrig
     * gav en kurva.
     *
     * Ett sparat val startar en skanning direkt, samma princip som en ändrad riskprofil gör för
     * bytesplanen (issue #88) — annars hade den valda fondens historik dröjt till nästa
     * backstop, alltså upp till ett halvt dygn.
     */
    fun chooseBenchmark(fund: Fund) {
        viewModelScope.launch {
            val isin = fund.isin ?: fundPriceRepository.lookupIsin(fund.fundId)
            if (isin == null) {
                benchmarkPickFailed.value = true
                return@launch
            }
            benchmarkPickFailed.value = false
            preferences.setChosenBenchmarkIsin(isin)
            fundPriceRefreshScheduler.triggerBenchmarkScan()
        }
    }

    /** Rensar det egna valet — appens automatiska referens (issue #101) gäller då igen. */
    fun clearBenchmark() {
        viewModelScope.launch {
            benchmarkPickFailed.value = false
            preferences.clearChosenBenchmarkIsin()
            fundPriceRefreshScheduler.triggerBenchmarkScan()
        }
    }

    fun setAccountType(type: AccountType) {
        viewModelScope.launch {
            val previous = preferences.accountType.first()
            preferences.setAccountType(type)
            if (type == AccountType.ISK_KF && previous != AccountType.ISK_KF) {
                fundPriceRefreshScheduler.triggerSwitchPlanScan()
            }
        }
    }

    /** Tömmer hela databasen (fonder, transaktioner, cachade kurser) — irreversibelt, se SET-1. */
    fun clearDatabase() {
        viewModelScope.launch {
            transactionRepository.clearAll()
            databaseCleared.update { true }
        }
    }

    /**
     * Kvitterar "databasen tömd"-meddelandet. Tömningen är en **händelse**, inte ett
     * tillstånd: utan kvittering låg flaggan kvar sann för ViewModel:ens livstid, och eftersom
     * uiState är `WhileSubscribed` kom meddelandet tillbaka vid varje rotation och vid varje
     * återbesök i Inställningar efter fem sekunder (issue #78).
     */
    fun onClearedMessageDismissed() {
        databaseCleared.update { false }
    }

    /**
     * Exporterar hela backup-kontraktet och lämnar JSON:en till [write], som skriver den dit
     * användaren valt (SET-6). Filen och `Uri`:n hör hemma i skärmen — ViewModel:en känner bara
     * till strängen, precis som [se.partee71.fonder.data.repository.BackupRepository] gör.
     *
     * Misslyckas [write] (I/O-fel, användaren valde en plats appen inte får skriva till) räknas
     * det som ett misslyckat export, inte som ett tyst lyckat.
     */
    fun export(write: suspend (String) -> Unit) {
        if (backupJob?.isActive == true) return
        backupJob = viewModelScope.launch {
            backupProgress.update { it.copy(inProgress = true, message = null) }
            val result = backupRepository.export().mapCatching { json -> write(json) }
            backupProgress.update {
                it.copy(
                    inProgress = false,
                    message = if (result.isSuccess) BackupMessage.Exported else BackupMessage.ExportFailed,
                )
            }
        }
    }

    /**
     * Läser en säkerhetskopia via [read] och **ersätter** kontraktets data med den (SET-6).
     * [read] ger null när filen inte gick att läsa alls — samma utfall som en trasig fil.
     *
     * Spärrad mot dubbeltryck av samma skäl som importflödena (issue #75): en återställning ger
     * ingen synlig återkoppling förrän den är klar, och två samtidiga skulle skriva samma rader
     * två gånger.
     */
    fun restore(read: suspend () -> String?) {
        if (backupJob?.isActive == true) return
        backupJob = viewModelScope.launch {
            backupProgress.update { it.copy(inProgress = true, message = null) }
            val json = runCatching { read() }.getOrNull()
            val message = if (json == null) {
                BackupMessage.RestoreFailed(BackupFormatException.Reason.UNREADABLE)
            } else {
                backupRepository.restore(json).fold(
                    onSuccess = { BackupMessage.Restored(it) },
                    onFailure = { error ->
                        val reason = (error as? BackupFormatException)?.reason
                            ?: BackupFormatException.Reason.UNREADABLE
                        BackupMessage.RestoreFailed(reason)
                    },
                )
            }
            backupProgress.update { it.copy(inProgress = false, message = message) }
        }
    }

    /** Kvitterar backup-meddelandet — en händelse, inte ett tillstånd (samma princip som [onClearedMessageDismissed]). */
    fun onBackupMessageDismissed() {
        backupProgress.update { it.copy(message = null) }
    }

    /**
     * Loggar in med Google (TP-6). [activityContext] måste vara aktivitetens — Credential Manager
     * ritar kontoväljaren ovanpå den aktiva aktiviteten och kan inte använda applikationens
     * context. Därför tar den här metoden ett context, till skillnad från resten av ViewModel:en.
     *
     * Den inloggade användaren sätts inte här: den kommer via [AuthRepository.currentUser], som
     * också fångar ändringar appen inte själv startat (utgången token, konto borttaget på
     * enheten). En lokal spegling hade kunnat visa en inloggad användare som inte längre finns.
     *
     * Ett avbrutet val (`CANCELLED`) lämnar inget felmeddelande — användaren stängde rutan med
     * flit och vet redan varför ingen inloggning skedde.
     */
    fun signIn(activityContext: Context) {
        if (signInJob?.isActive == true) return
        signInJob = viewModelScope.launch {
            signInInProgress.value = true
            signInError.value = null
            val result = authRepository.signInWithGoogle(activityContext)
            signInInProgress.value = false
            result.onFailure { error ->
                val reason = (error as? SignInException)?.reason ?: SignInException.Reason.FAILED
                signInError.value = reason.takeIf { it != SignInException.Reason.CANCELLED }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            signInError.value = null
            authRepository.signOut()
        }
    }

    /**
     * Säkerhetskopierar till Drive nu (SET-7). Kör i förgrunden, inte via workern, så användaren
     * får ett kvitto direkt — den periodiska körningen finns kvar oberoende av den här.
     *
     * [onNeedsAuthorization] får den `PendingIntent` som måste startas från aktiviteten när
     * `drive.appdata` inte är beviljat. Den vägen kan inte gå via tillståndet: en `PendingIntent`
     * är en engångsåtgärd, inte något som ska ligga kvar och kunna spelas upp vid rotation.
     */
    fun backupToDrive(onNeedsAuthorization: (PendingIntent) -> Unit) {
        if (backupJob?.isActive == true) return
        backupJob = viewModelScope.launch {
            backupProgress.update { it.copy(inProgress = true, message = null) }
            val message = backupRepository.export().fold(
                onSuccess = { json -> driveMessage(driveBackupRepository.upload(json), onNeedsAuthorization) },
                onFailure = { BackupMessage.ExportFailed },
            )
            backupProgress.update { it.copy(inProgress = false, message = message) }
        }
    }

    /**
     * Återställer från den senaste kopian i Drive (SET-7). **Ersätter** data, precis som
     * filåterställningen — anropas därför bara bakom samma bekräftelsedialog.
     */
    fun restoreFromDrive(onNeedsAuthorization: (PendingIntent) -> Unit) {
        if (backupJob?.isActive == true) return
        backupJob = viewModelScope.launch {
            backupProgress.update { it.copy(inProgress = true, message = null) }
            val download = driveBackupRepository.downloadLatest()
            val message = if (download is DriveResult.Success) {
                backupRepository.restore(download.value).fold(
                    onSuccess = { BackupMessage.Restored(it) },
                    onFailure = { error ->
                        val reason = (error as? BackupFormatException)?.reason
                            ?: BackupFormatException.Reason.UNREADABLE
                        BackupMessage.RestoreFailed(reason)
                    },
                )
            } else {
                driveMessage(download, onNeedsAuthorization)
            }
            backupProgress.update { it.copy(inProgress = false, message = message) }
        }
    }

    /**
     * Översätter ett [DriveResult] till ett meddelande, och lämnar ifrån sig
     * auktoriseringsintentet på vägen. `NeedsAuthorization` ger **inget** felmeddelande —
     * användaren möts av Googles ruta i stället, och en röd rad bakom den vore förvirrande.
     */
    private suspend fun driveMessage(
        result: DriveResult<*>,
        onNeedsAuthorization: (PendingIntent) -> Unit,
    ): BackupMessage? = when (result) {
        is DriveResult.Success -> BackupMessage.DriveSaved
        DriveResult.NoBackupFound -> BackupMessage.DriveEmpty
        DriveResult.NoAccount -> BackupMessage.DriveFailed(signedOut = true)
        is DriveResult.Error -> BackupMessage.DriveFailed(signedOut = false)
        is DriveResult.NeedsAuthorization -> {
            preferences.setDriveBackupNeedsAuth(true)
            onNeedsAuthorization(result.pendingIntent)
            null
        }
    }

    /**
     * Kvitterar att Drive-auktoriseringen är löst. Anropas när användaren kommit tillbaka från
     * Googles ruta — utfallet där rapporteras inte tillförlitligt, så flaggan rensas och nästa
     * körning får avgöra. Ett kvarliggande "behöver tillåtelse" efter ett beviljande vore värre
     * än ett som dyker upp igen.
     */
    fun onDriveAuthorizationResolved() {
        viewModelScope.launch { preferences.setDriveBackupNeedsAuth(false) }
    }

    /** Kvitterar inloggningsfelet — en händelse, inte ett tillstånd (samma princip som [onBackupMessageDismissed]). */
    fun onSignInErrorDismissed() {
        signInError.value = null
    }
}
