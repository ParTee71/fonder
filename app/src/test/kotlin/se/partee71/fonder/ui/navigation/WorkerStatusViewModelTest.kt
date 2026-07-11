package se.partee71.fonder.ui.navigation

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.partee71.fonder.worker.FundPriceRefreshScheduler

@OptIn(ExperimentalCoroutinesApi::class)
class WorkerStatusViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val isRunning = MutableStateFlow(false)

    private val fakeScheduler = object : FundPriceRefreshScheduler {
        override fun scheduleOnLaunch() {}
        override fun scheduleBackstop() {}
        override fun triggerManualRefresh() {}
        override fun observeIsRunning(): Flow<Boolean> = isRunning
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `isRunning speglar schedulerns flode`() = runTest(dispatcher) {
        val vm = WorkerStatusViewModel(fakeScheduler)

        vm.isRunning.test {
            assertFalse(awaitItem())

            isRunning.value = true
            assertTrue(awaitItem())

            isRunning.value = false
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
