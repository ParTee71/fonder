package se.partee71.fonder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.datastore.ThemeMode
import se.partee71.fonder.data.repository.BackupFormatException
import se.partee71.fonder.data.repository.BackupRepository
import se.partee71.fonder.data.repository.RestoreSummary
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.AccountType
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
}

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
)

/** Backup-delen av tillståndet, hållen för sig så [SettingsViewModel.uiState] kan `combine`:a in den. */
private data class BackupState(
    val inProgress: Boolean = false,
    val message: BackupMessage? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRefreshScheduler: FundPriceRefreshScheduler,
    private val backupRepository: BackupRepository,
) : ViewModel() {

    private val databaseCleared = MutableStateFlow(false)
    private val backupState = MutableStateFlow(BackupState())
    private var backupJob: Job? = null

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

    /** Forcerar en kursuppdatering oavsett staleness-gate — den manuella "Uppdatera nu"-knappen (SET-2, issue #27). */
    fun refreshPricesNow() {
        fundPriceRefreshScheduler.triggerManualRefresh()
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
            backupState.update { it.copy(inProgress = true, message = null) }
            val result = backupRepository.export().mapCatching { json -> write(json) }
            backupState.update {
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
            backupState.update { it.copy(inProgress = true, message = null) }
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
            backupState.update { it.copy(inProgress = false, message = message) }
        }
    }

    /** Kvitterar backup-meddelandet — en händelse, inte ett tillstånd (samma princip som [onClearedMessageDismissed]). */
    fun onBackupMessageDismissed() {
        backupState.update { it.copy(message = null) }
    }
}
