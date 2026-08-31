package se.partee71.fonder.ui.navigation

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.partee71.fonder.worker.BackgroundWork
import se.partee71.fonder.worker.FakeFundPriceRefreshScheduler

@OptIn(ExperimentalCoroutinesApi::class)
class WorkerStatusViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val fakeScheduler = FakeFundPriceRefreshScheduler()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `isRunning speglar schedulerns flode`() = runTest(dispatcher) {
        val vm = WorkerStatusViewModel(fakeScheduler)

        vm.isRunning.test {
            assertFalse(awaitItem())

            // Chromets indikator är "något kör alls" — vilken sorts jobb det är spelar ingen
            // roll här, det är kortens sak (NAV-6).
            fakeScheduler.runningWork.value = setOf(BackgroundWork.DRIVE_BACKUP)
            assertTrue(awaitItem())

            fakeScheduler.runningWork.value = emptySet()
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
