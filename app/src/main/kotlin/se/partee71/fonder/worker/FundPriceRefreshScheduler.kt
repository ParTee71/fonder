package se.partee71.fonder.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schemalägger [FundPriceUpdateWorker] från appens tre triggers (issue #27, TP-17), koalescerade
 * till samma WorkManager-mekanism i stället för tre oberoende ad hoc-lösningar:
 *
 * - [scheduleOnLaunch] — billig launch-gate vid appstart. Bär det verkliga färskhetsvärdet
 *   (garanterar färsk data när användaren faktiskt öppnar appen); gör inget nätverksanrop om
 *   ingen fond är inaktuell ([FundPriceUpdateWorker.refreshAll]s egen gate).
 * - [scheduleBackstop] — gles periodisk körning för när appen inte öppnas alls. Robustare än en
 *   självköande one-time-kedja (som kan brytas av force-stop), och lika billig som launch-gaten
 *   tack vare samma staleness-gate.
 * - [triggerManualRefresh] — den manuella "Uppdatera nu"-knappen (SET-2), forcerar en uppdatering
 *   och ersätter (`REPLACE`) allt som väntar under samma unika namn.
 * - [triggerSwitchPlanScan] — bytesplanen på begäran (HEM-8, issue #88), när dess indata just
 *   ändrats eller användaren bett om det.
 *
 * [observeRunningWork] driver bakgrundsindikatorerna (NAV-6): chromets `WorkerStatusIcon` via
 * [observeIsRunning], och korten via varje skärms egna flaggor. Den är **sorterad per sorts
 * jobb**, inte ett enda booleskt "något kör": ett kort ska snurra när just dess data bearbetas,
 * och en molnbackup rör varken kurser, bytesplan eller referensfond.
 */
interface FundPriceRefreshScheduler {
    fun scheduleOnLaunch()
    fun scheduleBackstop()
    fun triggerManualRefresh()

    /**
     * Räknar om bytesplanen (HEM-8) på begäran i stället för att vänta på backstopen — vid
     * sparad riskprofil (SET-3), byte till ISK/KF (SET-4) eller knappen på Hems riskkort
     * (issue #88). Planens enda användarstyrda indata är just de två inställningarna, så det
     * är också de enda ögonblick då den är inaktuell per definition.
     *
     * Kör **utanför** [triggerManualRefresh]s unika namn: den manuella knappen ersätter
     * (`REPLACE`) allt som väntar där och skulle annars kunna avbryta en pågående skanning
     * mitt i — och tvärtom. Upprepade anrop koalesceras (`KEEP`), så två snabba sparningar
     * eller tryck aldrig ger två parallella (dyra) skanningar.
     */
    fun triggerSwitchPlanScan()

    /**
     * Väljer och hämtar hem Hems referensfond (HEM-10) på begäran i stället för att vänta på
     * backstopen. Anropas av [se.partee71.fonder.ui.hem.HemViewModel] när ingen referensfond är
     * vald än — utan den vägen fanns ingen indexkurva alls förrän en backstop-körning råkat
     * passera, alltså upp till ett halvt dygn efter installation eller uppgradering.
     *
     * Kostnaden bärs **en gång**: valet sparas, och nästa körning hoppar direkt till att hålla
     * kursen färsk. Eget unikt arbetsnamn med `KEEP`, av samma skäl som [triggerSwitchPlanScan] —
     * den manuella knappen (SET-2) ersätter allt som väntar under sitt namn och skulle annars
     * kunna avbryta hämtningen mitt i.
     */
    fun triggerBenchmarkScan()

    /**
     * Schemalägger den dygnsvisa molnbackupen till Drive (SET-7, TP-7 steg 2). Anropas vid
     * appstart; `KEEP` gör upprepade anrop gratis.
     *
     * Eget unikt arbetsnamn och en **egen worker** ([DriveBackupWorker]) — till skillnad från
     * bytesplans- och referensfondsskanningarna, som kör kursuppdateringens worker. En
     * säkerhetskopiering får inte kunna avbrytas av den manuella "Uppdatera nu" (SET-2), som
     * ersätter (`REPLACE`) allt som väntar under kursuppdateringens namn.
     */
    fun scheduleDriveBackup()

    /** Kör molnbackupen nu (knappen i Inställningar, SET-7). Koalescerar med den periodiska. */
    fun triggerDriveBackupNow()

    /**
     * Vilka sorters bakgrundsjobb som kör **just nu**. Tom mängd i vila.
     *
     * Backstopen (`PERIODIC_WORK_NAME`) bär tre saker samtidigt — kursuppdatering,
     * bytesplansskanning och referensfondsval (se [scheduleBackstop]s `KEY_SCAN_*`) — och
     * rapporteras därför som alla tre. Ett kort som väntar på bytesplanen ska snurra även när
     * planen räknas om inuti backstopen, inte bara när knappen på riskkortet startat en egen
     * skanning.
     */
    fun observeRunningWork(): Flow<Set<BackgroundWork>>

    /** Sant om något jobb alls kör — chromets bakgrundsindikator (NAV-6). */
    fun observeIsRunning(): Flow<Boolean> = observeRunningWork().map { it.isNotEmpty() }
}

/**
 * De sorters bakgrundsarbete ett kort kan vänta på (NAV-6). Enum, inte fritt strängnamn: varje
 * kort binder sin snurra till exakt de sorter som skriver dess data, och den kopplingen ska
 * gå att läsa i typen.
 */
enum class BackgroundWork {
    /** Kursuppdatering — NAV, historik och det som räknas ur dem (TP-17). */
    PRICE_REFRESH,

    /** Bytesplansskanningen (HEM-8) — kandidater, köpbarhet och facit-inspelningen. */
    SWITCH_PLAN_SCAN,

    /** Val och hämtning av Hems referensfond (HEM-10). */
    BENCHMARK_SCAN,

    /** Molnbackupen till Drive (SET-7) — rör ingen fonddata, bara Inställningars backup-kort. */
    DRIVE_BACKUP,
}

@Singleton
class WorkManagerFundPriceRefreshScheduler @Inject constructor(
    @ApplicationContext context: Context,
) : FundPriceRefreshScheduler {

    private val workManager = WorkManager.getInstance(context)

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    override fun scheduleOnLaunch() {
        val request = OneTimeWorkRequestBuilder<FundPriceUpdateWorker>()
            .setConstraints(networkConstraints)
            .build()
        workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun scheduleBackstop() {
        val request = PeriodicWorkRequestBuilder<FundPriceUpdateWorker>(BACKSTOP_INTERVAL_HOURS, TimeUnit.HOURS)
            // Bara backstopen fyller inkrementellt på billigare-alternativ-jämförelsen (HEM-6,
            // issue #61), bytesplanens facit (HEM-8, issue #70) och Hems referensfond (HEM-10,
            // issue #96) — aldrig launch-gaten eller den manuella knappen, se
            // FundPriceUpdateWorker.KEY_SCAN_COMPARISONS/KEY_SCAN_SWITCH_PLAN/KEY_SCAN_BENCHMARK.
            .setInputData(
                workDataOf(
                    FundPriceUpdateWorker.KEY_SCAN_COMPARISONS to true,
                    FundPriceUpdateWorker.KEY_SCAN_SWITCH_PLAN to true,
                    FundPriceUpdateWorker.KEY_SCAN_BENCHMARK to true,
                ),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .build()
        // UPDATE (inte KEEP): befintliga installationer har redan ett 24h-jobb schemalagt under
        // samma namn (före issue #27) — UPDATE gör att det nya, glesare intervallet faktiskt slår
        // igenom vid uppgradering i stället för att det gamla jobbet lever kvar för evigt.
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun triggerManualRefresh() {
        val request = OneTimeWorkRequestBuilder<FundPriceUpdateWorker>()
            .setInputData(workDataOf(FundPriceUpdateWorker.KEY_FORCE to true))
            .setConstraints(networkConstraints)
            .build()
        workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    override fun triggerSwitchPlanScan() {
        val request = OneTimeWorkRequestBuilder<FundPriceUpdateWorker>()
            // KEY_FORCE är inte kosmetiskt här: `runScans` gör ingenting om kursuppdateringen
            // inte lyckades, och utan force hoppar `refreshAll` över allt som redan är färskt
            // och rapporterar det som en lyckad körning utan att ha hämtat något. Skanningen
            // ska dessutom räkna på färsk NAV — facit mäter utfallet mot kursen *vid*
            // förslagstillfället (HEM-8).
            .setInputData(
                workDataOf(
                    FundPriceUpdateWorker.KEY_FORCE to true,
                    FundPriceUpdateWorker.KEY_SCAN_SWITCH_PLAN to true,
                ),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .build()
        workManager.enqueueUniqueWork(SWITCH_PLAN_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun triggerBenchmarkScan() {
        val request = OneTimeWorkRequestBuilder<FundPriceUpdateWorker>()
            // KEY_FORCE av samma skäl som i triggerSwitchPlanScan: `runScans` gör ingenting om
            // kursuppdateringen inte lyckades, och utan force rapporterar `refreshAll` framgång
            // för en redan färsk cache utan att ha hämtat något.
            .setInputData(
                workDataOf(
                    FundPriceUpdateWorker.KEY_FORCE to true,
                    FundPriceUpdateWorker.KEY_SCAN_BENCHMARK to true,
                ),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .build()
        workManager.enqueueUniqueWork(BENCHMARK_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun scheduleDriveBackup() {
        val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(DRIVE_BACKUP_INTERVAL_HOURS, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            DRIVE_BACKUP_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override fun triggerDriveBackupNow() {
        val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .build()
        workManager.enqueueUniqueWork(DRIVE_BACKUP_NOW_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun observeRunningWork(): Flow<Set<BackgroundWork>> =
        combine(
            workManager.getWorkInfosForUniqueWorkFlow(ONE_TIME_WORK_NAME),
            workManager.getWorkInfosForUniqueWorkFlow(PERIODIC_WORK_NAME),
            workManager.getWorkInfosForUniqueWorkFlow(SWITCH_PLAN_WORK_NAME),
            workManager.getWorkInfosForUniqueWorkFlow(BENCHMARK_WORK_NAME),
            workManager.getWorkInfosForUniqueWorkFlow(DRIVE_BACKUP_NOW_WORK_NAME),
        ) { oneTime, periodic, switchPlan, benchmark, driveBackup ->
            buildSet {
                if (oneTime.isRunning()) add(BackgroundWork.PRICE_REFRESH)
                // Backstopen bär alla tre skanningarna, se observeRunningWork i gränssnittet.
                if (periodic.isRunning()) {
                    add(BackgroundWork.PRICE_REFRESH)
                    add(BackgroundWork.SWITCH_PLAN_SCAN)
                    add(BackgroundWork.BENCHMARK_SCAN)
                }
                // Skanningarna kör kursuppdateringens worker med KEY_FORCE, alltså uppdateras
                // kurserna på riktigt även här — kurskorten ska snurra med.
                if (switchPlan.isRunning()) {
                    add(BackgroundWork.SWITCH_PLAN_SCAN)
                    add(BackgroundWork.PRICE_REFRESH)
                }
                if (benchmark.isRunning()) {
                    add(BackgroundWork.BENCHMARK_SCAN)
                    add(BackgroundWork.PRICE_REFRESH)
                }
                if (driveBackup.isRunning()) add(BackgroundWork.DRIVE_BACKUP)
            }
        }

    private fun List<WorkInfo>.isRunning(): Boolean = any { it.state == WorkInfo.State.RUNNING }

    companion object {
        internal const val ONE_TIME_WORK_NAME = "fonder_price_refresh"
        internal const val PERIODIC_WORK_NAME = "fonder_daily_price_update"

        /** Eget unikt namn för bytesplansskanningen — se [FundPriceRefreshScheduler.triggerSwitchPlanScan]. */
        internal const val SWITCH_PLAN_WORK_NAME = "fonder_switch_plan_scan"

        /** Eget unikt namn för referensfondsskanningen — se [FundPriceRefreshScheduler.triggerBenchmarkScan]. */
        internal const val BENCHMARK_WORK_NAME = "fonder_benchmark_scan"
        private const val BACKSTOP_INTERVAL_HOURS = 12L

        /**
         * Egna unika namn för molnbackupen (SET-7) — se
         * [FundPriceRefreshScheduler.scheduleDriveBackup]. Den periodiska och den manuella
         * körningen hålls isär så ett knapptryck inte skjuter fram nästa dygnsvisa körning.
         */
        internal const val DRIVE_BACKUP_WORK_NAME = "fonder_drive_backup"
        internal const val DRIVE_BACKUP_NOW_WORK_NAME = "fonder_drive_backup_now"
        private const val DRIVE_BACKUP_INTERVAL_HOURS = 24L
    }
}
