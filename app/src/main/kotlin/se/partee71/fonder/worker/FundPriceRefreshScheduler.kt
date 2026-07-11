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
 *
 * [observeIsRunning] driver bakgrundsindikatorn (`WorkerStatusIcon`, NAV-6) — sant om någon av
 * de två unika arbetsflödena faktiskt kör just nu.
 */
interface FundPriceRefreshScheduler {
    fun scheduleOnLaunch()
    fun scheduleBackstop()
    fun triggerManualRefresh()
    fun observeIsRunning(): Flow<Boolean>
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

    override fun observeIsRunning(): Flow<Boolean> =
        combine(
            workManager.getWorkInfosForUniqueWorkFlow(ONE_TIME_WORK_NAME),
            workManager.getWorkInfosForUniqueWorkFlow(PERIODIC_WORK_NAME),
        ) { oneTime, periodic -> (oneTime + periodic).any { it.state == WorkInfo.State.RUNNING } }

    companion object {
        internal const val ONE_TIME_WORK_NAME = "fonder_price_refresh"
        internal const val PERIODIC_WORK_NAME = "fonder_daily_price_update"
        private const val BACKSTOP_INTERVAL_HOURS = 12L
    }
}
