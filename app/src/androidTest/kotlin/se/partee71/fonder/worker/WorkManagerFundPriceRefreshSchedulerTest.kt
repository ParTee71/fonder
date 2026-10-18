package se.partee71.fonder.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifierar att [WorkManagerFundPriceRefreshScheduler] koalescerar sina tre triggers rätt
 * (issue #27, TP-17) — kräver riktig WorkManager (`work-testing`, `SynchronousExecutor` så
 * enqueue-anrop resolvas synkront i testet i stället för på en bakgrundstråd).
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerFundPriceRefreshSchedulerTest {

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerFundPriceRefreshScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerFundPriceRefreshScheduler(context)
    }

    @Test
    fun scheduleOnLaunch_koar_ett_arbete_under_det_gemensamma_namnet() {
        scheduler.scheduleOnLaunch()

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerFundPriceRefreshScheduler.ONE_TIME_WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.first().state)
    }

    @Test
    fun scheduleOnLaunch_kor_igen_dubbelanropas_inte_tack_vare_KEEP() {
        scheduler.scheduleOnLaunch()
        scheduler.scheduleOnLaunch()

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerFundPriceRefreshScheduler.ONE_TIME_WORK_NAME).get()
        assertEquals(1, infos.size)
    }

    @Test
    fun triggerManualRefresh_ersatter_ett_vantande_launch_gate_arbete() {
        scheduler.scheduleOnLaunch()
        scheduler.triggerManualRefresh()

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerFundPriceRefreshScheduler.ONE_TIME_WORK_NAME).get()
        // REPLACE avbryter det gamla och köar ett nytt — bara det senaste (forcerade) räknas.
        assertEquals(1, infos.size)
        assertEquals(true, infos.first().tags.isNotEmpty()) // sanity: ett riktigt arbete köades
    }

    @Test
    fun scheduleBackstop_koar_ett_periodiskt_arbete_under_det_egna_namnet() {
        scheduler.scheduleBackstop()

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerFundPriceRefreshScheduler.PERIODIC_WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.first().state)
    }

    @Test
    fun observeIsRunning_ar_falskt_utan_schemalagt_arbete() = kotlinx.coroutines.test.runTest {
        assertTrue(!scheduler.observeIsRunning().first())
    }

    // --- Bytesplanen på begäran (HEM-8, issue #88) ---

    @Test
    fun triggerSwitchPlanScan_koar_under_eget_namn_utan_force_med_skanning_och_omrakningsflagga() {
        scheduler.triggerSwitchPlanScan()

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerFundPriceRefreshScheduler.SWITCH_PLAN_WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.first().state)
        // En omräkning efter en transaktion hämtar bara inaktuella kurser (#143) — men den är den
        // begärda skanningen, som räknar om en gång till om en begäran kommer medan den kör.
        val input = scheduler.switchPlanScanRequest(force = false).workSpec.input
        assertEquals(false, input.getBoolean(FundPriceUpdateWorker.KEY_FORCE, true))
        assertEquals(true, input.getBoolean(FundPriceUpdateWorker.KEY_SCAN_SWITCH_PLAN, false))
        assertEquals(true, input.getBoolean(FundPriceUpdateWorker.KEY_RESCAN_ON_REQUEST, false))
    }

    @Test
    fun triggerSwitchPlanScan_med_force_hamtar_alla_kurser() {
        scheduler.triggerSwitchPlanScan(force = true)

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerFundPriceRefreshScheduler.SWITCH_PLAN_WORK_NAME).get()
        assertEquals(1, infos.size)
        val input = scheduler.switchPlanScanRequest(force = true).workSpec.input
        assertEquals(true, input.getBoolean(FundPriceUpdateWorker.KEY_FORCE, false))
        assertEquals(true, input.getBoolean(FundPriceUpdateWorker.KEY_SCAN_SWITCH_PLAN, false))
        assertEquals(true, input.getBoolean(FundPriceUpdateWorker.KEY_RESCAN_ON_REQUEST, false))
    }

    @Test
    fun switchPlanScanRequestedSince_ger_bara_begaranden_strikt_efter_tidpunkten_med_force() {
        val before = System.currentTimeMillis() - 1
        scheduler.triggerSwitchPlanScan()
        val afterFirst = System.currentTimeMillis()
        Thread.sleep(2)
        scheduler.triggerSwitchPlanScan(force = true)

        val request = scheduler.switchPlanScanRequestedSince(before)!!
        assertTrue(request.force)
        assertTrue(request.epochMillis > afterFirst)
        // En begäran som startade jobbet räknas inte som ny.
        assertEquals(null, scheduler.switchPlanScanRequestedSince(request.epochMillis))
        // En icke-forcerad begäran efter den forcerade ärver inte force.
        Thread.sleep(2)
        val afterForced = System.currentTimeMillis()
        Thread.sleep(2)
        scheduler.triggerSwitchPlanScan()
        assertEquals(false, scheduler.switchPlanScanRequestedSince(afterForced)!!.force)
    }

    @Test
    fun takeForcedSwitchPlanRequest_ger_en_forcerad_begaran_en_gang_tills_nagon_tagit_den() {
        // "Räkna om" medan ett icke-forcerat jobb redan väntar: KEEP tappar begäran, men workern
        // ska se den när jobbet startar — en gång, aldrig i nästa jobb igen.
        scheduler.triggerSwitchPlanScan()
        assertEquals(false, scheduler.takeForcedSwitchPlanRequest())
        scheduler.triggerSwitchPlanScan(force = true)

        assertEquals(true, scheduler.takeForcedSwitchPlanRequest())
        assertEquals(false, scheduler.takeForcedSwitchPlanRequest())

        // Rapporteras force av omkollen är den också tagen.
        val before = System.currentTimeMillis() - 1
        Thread.sleep(2)
        scheduler.triggerSwitchPlanScan(force = true)
        assertEquals(true, scheduler.switchPlanScanRequestedSince(before)!!.force)
        assertEquals(false, scheduler.takeForcedSwitchPlanRequest())
    }

    @Test
    fun enqueueSwitchPlanFollowUp_hangs_efter_det_koade_jobbet_i_stallet_for_att_tappas() {
        scheduler.triggerSwitchPlanScan()

        scheduler.enqueueSwitchPlanFollowUp(force = true)

        // KEEP hade gett ett jobb; uppföljaren är ett andra, kedjat efter det första.
        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerFundPriceRefreshScheduler.SWITCH_PLAN_WORK_NAME).get()
        assertEquals(2, infos.size)
        assertEquals(1, infos.count { it.state == WorkInfo.State.BLOCKED })
    }

    @Test
    fun triggerSwitchPlanScan_dubbelanropas_inte_tack_vare_KEEP() {
        scheduler.triggerSwitchPlanScan()
        scheduler.triggerSwitchPlanScan()

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerFundPriceRefreshScheduler.SWITCH_PLAN_WORK_NAME).get()
        assertEquals(1, infos.size)
    }

    @Test
    fun manuell_uppdatering_avbryter_inte_en_koad_bytesplansskanning() {
        // Egen unik kö, just för att "Uppdatera nu" kör REPLACE — annars hade den slagit ut
        // skanningen mitt i (och tvärtom).
        scheduler.triggerSwitchPlanScan()
        scheduler.triggerManualRefresh()

        val scanInfos = workManager.getWorkInfosForUniqueWork(WorkManagerFundPriceRefreshScheduler.SWITCH_PLAN_WORK_NAME).get()
        val refreshInfos = workManager.getWorkInfosForUniqueWork(WorkManagerFundPriceRefreshScheduler.ONE_TIME_WORK_NAME).get()
        assertEquals(1, scanInfos.size)
        assertTrue(scanInfos.first().state != WorkInfo.State.CANCELLED)
        assertEquals(1, refreshInfos.size)
    }

    @Test
    fun triggerBenchmarkScan_koar_under_eget_namn() {
        scheduler.triggerBenchmarkScan()

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerFundPriceRefreshScheduler.BENCHMARK_WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.first().state)
    }

    @Test
    fun triggerBenchmarkScan_dubbelanropas_inte_tack_vare_KEEP() {
        // Hem ber om referensfonden när ingen är vald; två snabba anrop får inte ge två
        // källfrågor och två backfills.
        scheduler.triggerBenchmarkScan()
        scheduler.triggerBenchmarkScan()

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerFundPriceRefreshScheduler.BENCHMARK_WORK_NAME).get()
        assertEquals(1, infos.size)
    }

    @Test
    fun manuell_uppdatering_avbryter_inte_en_koad_referensfondsskanning() {
        scheduler.triggerBenchmarkScan()
        scheduler.triggerManualRefresh()

        val scanInfos = workManager.getWorkInfosForUniqueWork(WorkManagerFundPriceRefreshScheduler.BENCHMARK_WORK_NAME).get()
        assertEquals(1, scanInfos.size)
        assertTrue(scanInfos.first().state != WorkInfo.State.CANCELLED)
    }

    @Test
    fun cancelRetiredWork_avregistrerar_den_gamla_dygnsvisa_molnbackupen() {
        // En uppgraderad installation har kvar det periodiska jobbet från före HEM-12. Workern
        // spelar ingen roll för avregistreringen — det är det unika namnet som avgör. Initial
        // fördröjning: utan den kör SynchronousExecutor jobbet direkt vid enqueue, och ett jobb
        // som redan körts klart (eller fallerat) blir aldrig CANCELLED.
        val legacy = androidx.work.PeriodicWorkRequestBuilder<FundPriceUpdateWorker>(24, java.util.concurrent.TimeUnit.HOURS)
            .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WorkManagerFundPriceRefreshScheduler.RETIRED_DRIVE_BACKUP_WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            legacy
        ).result.get()

        scheduler.cancelRetiredWork()

        val infos = workManager.getWorkInfosForUniqueWork(
            WorkManagerFundPriceRefreshScheduler.RETIRED_DRIVE_BACKUP_WORK_NAME
        ).get()
        assertTrue(infos.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun cancelRetiredWork_ror_inte_kursuppdateringen() {
        scheduler.scheduleBackstop()

        scheduler.cancelRetiredWork()

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerFundPriceRefreshScheduler.PERIODIC_WORK_NAME).get()
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }
}
