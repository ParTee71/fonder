package se.partee71.fonder.worker

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Delad fake för [FundPriceRefreshScheduler] i enhetstesterna — räknar anropen och låter testet
 * styra vad som "kör" just nu via [runningWork].
 *
 * Delad, inte en anonym `object :` per testklass (regel 4-tänket även i testkoden): varje ny
 * metod i gränssnittet hade annars behövt läggas till på fyra ställen, och när körstatusen blev
 * mängdbaserad (NAV-6) hade fyra olika testfaker kunnat tolka den på fyra olika sätt.
 */
class FakeFundPriceRefreshScheduler : FundPriceRefreshScheduler {

    /** Vad som kör just nu — sätt den i testet för att driva kortens väntesnurror (NAV-6). */
    val runningWork = MutableStateFlow<Set<BackgroundWork>>(emptySet())

    var launchScheduled = 0
        private set
    var backstopScheduled = 0
        private set
    var manualRefreshes = 0
        private set
    var switchPlanScans = 0
        private set
    var benchmarkScans = 0
        private set
    var retiredWorkCancellations = 0
        private set

    override fun scheduleOnLaunch() {
        launchScheduled++
    }

    override fun scheduleBackstop() {
        backstopScheduled++
    }

    override fun triggerManualRefresh() {
        manualRefreshes++
    }

    /** Senaste begäran — som den riktiga schemaläggaren antecknar den. */
    var lastSwitchPlanRequest: SwitchPlanScanRequest? = null

    /** Hur många av [switchPlanScans] som begärts med forcerad kursuppdatering — bara "Räkna om" ska. */
    var forcedSwitchPlanScans = 0
        private set

    /** Uppföljare som workern köat, med sin force-flagga. */
    val switchPlanFollowUps = mutableListOf<Boolean>()

    override fun triggerSwitchPlanScan(force: Boolean) {
        switchPlanScans++
        if (force) forcedSwitchPlanScans++
        lastSwitchPlanRequest = SwitchPlanScanRequest(System.currentTimeMillis(), force)
    }

    override fun switchPlanScanRequestedSince(epochMillis: Long): SwitchPlanScanRequest? =
        lastSwitchPlanRequest?.takeIf { it.epochMillis > epochMillis }

    override fun takeForcedSwitchPlanRequest(): Boolean = false

    override fun enqueueSwitchPlanFollowUp(force: Boolean) {
        switchPlanFollowUps += force
    }

    override fun triggerBenchmarkScan() {
        benchmarkScans++
    }

    override fun cancelRetiredWork() {
        retiredWorkCancellations++
    }

    override fun observeRunningWork(): Flow<Set<BackgroundWork>> = runningWork
}
