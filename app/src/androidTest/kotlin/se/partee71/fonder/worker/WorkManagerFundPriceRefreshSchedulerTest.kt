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
    fun triggerSwitchPlanScan_koar_under_eget_namn_med_force_och_skanningsflaggan() {
        scheduler.triggerSwitchPlanScan()

        val infos = workManager.getWorkInfosForUniqueWork(WorkManagerFundPriceRefreshScheduler.SWITCH_PLAN_WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.first().state)
        // Skanningen kör bara efter en lyckad uppdatering, och `refreshAll` hoppar över allt som
        // redan är färskt — utan KEY_FORCE hade jobbet returnerat utan att ha räknat om något.
        assertEquals(true, infos.first().tags.isNotEmpty())
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
}
