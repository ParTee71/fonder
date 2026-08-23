package se.partee71.fonder.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.BackupRepository
import se.partee71.fonder.data.repository.DriveBackupRepository
import se.partee71.fonder.data.repository.DriveResult

/**
 * Vad en Drive-körning ska leda till. Egen typ så beslutet går att enhetstesta utan att
 * konstruera en `CoroutineWorker` — se [outcomeFor].
 */
internal enum class DriveBackupOutcome {
    /** Klart, eller inget att göra. Tidsstämpeln uppdateras bara vid [SAVED]. */
    SAVED,
    NOTHING_TO_DO,

    /** Scopet saknas — flaggan sätts så Inställningar kan erbjuda knappen. Ingen retry. */
    NEEDS_AUTH,

    /** Övergående (nätverk, Drive-fel) — WorkManager får försöka igen. */
    RETRY,
}

/**
 * Mappar ett [DriveResult] till vad bakgrundsjobbet ska göra.
 *
 * Ren funktion, och den enda logik workern har. **Utloggad är inte ett fel**: ett bakgrundsjobb
 * som failar för att ingen loggat in skulle backa av exponentiellt och sedan ge upp, för ett
 * läge som är helt normalt. Samma sak med [DriveResult.NeedsAuthorization] — en retry kan inte
 * lösa den, bara användaren kan, så den flaggas i stället för att köas om.
 */
internal fun outcomeFor(result: DriveResult<*>): DriveBackupOutcome = when (result) {
    is DriveResult.Success -> DriveBackupOutcome.SAVED
    DriveResult.NoAccount -> DriveBackupOutcome.NOTHING_TO_DO
    DriveResult.NoBackupFound -> DriveBackupOutcome.NOTHING_TO_DO
    is DriveResult.NeedsAuthorization -> DriveBackupOutcome.NEEDS_AUTH
    is DriveResult.Error -> DriveBackupOutcome.RETRY
}

/**
 * Skriver backup-kontraktet till Drive i bakgrunden (TP-7 steg 2, SET-7).
 *
 * Egen worker och eget unikt arbetsnamn, inte påhängd [FundPriceUpdateWorker]: en
 * säkerhetskopiering ska inte kunna avbrytas av den manuella "Uppdatera nu" (SET-2), som
 * ersätter (`REPLACE`) allt som väntar under kursuppdateringens namn. Se
 * [FundPriceRefreshScheduler.scheduleDriveBackup].
 *
 * Workern äger ingen egen serialisering — den ber [BackupRepository] om strängen och lämnar den
 * till [DriveBackupRepository]. Misslyckas exporten är det ett fel i kontraktet, inte i
 * transporten, och då ska ingenting laddas upp.
 */
@HiltWorker
class DriveBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupRepository: BackupRepository,
    private val driveBackupRepository: DriveBackupRepository,
    private val preferencesRepository: PreferencesRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val json = backupRepository.export().getOrElse { return Result.retry() }

        return when (outcomeFor(driveBackupRepository.upload(json))) {
            DriveBackupOutcome.SAVED -> {
                preferencesRepository.setLastDriveBackupEpochMillis(System.currentTimeMillis())
                preferencesRepository.setDriveBackupNeedsAuth(false)
                Result.success()
            }
            DriveBackupOutcome.NOTHING_TO_DO -> Result.success()
            DriveBackupOutcome.NEEDS_AUTH -> {
                preferencesRepository.setDriveBackupNeedsAuth(true)
                Result.success()
            }
            DriveBackupOutcome.RETRY -> Result.retry()
        }
    }
}
