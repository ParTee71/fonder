package se.partee71.fonder.ui.navigation

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeCachedPriceRepository
import se.partee71.fonder.data.repository.FakeSuggestionDismissalRepository
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.usecase.DismissedPlanSource
import se.partee71.fonder.domain.usecase.InProgressSwitches
import se.partee71.fonder.domain.usecase.PendingDecisionsSource
import se.partee71.fonder.testing.testPreferencesRepository
import java.time.LocalDate

/** Kopparmärket på Beslut (NAV-9) följer den delade källans antal — samma tal som fliken och "Att besluta". */
@OptIn(ExperimentalCoroutinesApi::class)
class DecisionBadgeViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val switchWatchRepo = FakeSwitchWatchRepository()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `market raknar pagaende byten och nollas nar de stangs`() = runTest(dispatcher) {
        val records = FakeSuggestionRecordRepository()
        val dismissals = FakeSuggestionDismissalRepository()
        val preferences = testPreferencesRepository(dispatcher, tempFolder, "badge_test")
        val metadata = FakeFundMetadataRepository()
        val source = PendingDecisionsSource(
            preferences,
            records,
            switchWatchRepo,
            FakeCachedPriceRepository(),
            metadata,
            DismissedPlanSource(records, dismissals, preferences, metadata),
            InProgressSwitches(switchWatchRepo, records),
        )
        val vm = DecisionBadgeViewModel(source)

        vm.count.test {
            assertEquals(0, awaitItem())

            val id = switchWatchRepo.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Fond A", soldAtEpochDay = LocalDate.now().toEpochDay()))
            assertEquals(1, awaitItem())

            switchWatchRepo.close(id, SwitchWatchCloseReason.AVBRUTEN, LocalDate.now())
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
