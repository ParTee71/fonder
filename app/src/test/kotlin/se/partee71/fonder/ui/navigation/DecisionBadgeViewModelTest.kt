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
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
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

    private val records = FakeSuggestionRecordRepository()
    private val metadata = FakeFundMetadataRepository()

    private fun viewModel(): DecisionBadgeViewModel {
        val dismissals = FakeSuggestionDismissalRepository()
        val preferences = testPreferencesRepository(dispatcher, tempFolder, "badge_test")
        val source = PendingDecisionsSource(
            preferences,
            records,
            switchWatchRepo,
            FakeCachedPriceRepository(),
            metadata,
            DismissedPlanSource(records, dismissals, preferences, metadata),
            InProgressSwitches(switchWatchRepo, records),
        )
        return DecisionBadgeViewModel(source)
    }

    @Test
    fun `market raknar pagaende byten och nollas nar de stangs`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.count.test {
            assertEquals(0, awaitItem())

            val id = switchWatchRepo.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Fond A", soldAtEpochDay = LocalDate.now().toEpochDay()))
            assertEquals(1, awaitItem())

            switchWatchRepo.close(id, SwitchWatchCloseReason.AVBRUTEN, LocalDate.now())
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett salt billigare alternativ raknas som ett byte - inte tva`() = runTest(dispatcher) {
        // BYT-4: Sålt-raden ersätter avgiftsraden, så märket (= "Att besluta", HEM-13) räknar bytet en gång.
        metadata.metadataByIsin = mapOf(
            "SE_DYR" to meta("SE_DYR", "Dyr fond", fee = 1.4).copy(shownAlternativeIsins = listOf("SE_BILLIG")),
            "SE_BILLIG" to meta("SE_BILLIG", "Billig fond", fee = 0.4),
        )
        records.records.value = listOf(
            SuggestionRecord(
                id = 77, suggestedAtEpochDay = LocalDate.now().toEpochDay(), planIndex = 0, sellIsin = "SE_DYR", buyIsin = "SE_BILLIG",
                sellNavAtSuggestion = 100.0, buyNavAtSuggestion = 100.0, switchValueKr = 10_000.0, kind = SuggestionKind.FEE,
            ),
        )
        val vm = viewModel()

        vm.count.test {
            var count = awaitItem()
            while (count == 0) count = awaitItem()
            assertEquals(1, count)

            val id = switchWatchRepo.start(
                SwitchWatch(sellIsin = "SE_DYR", sellFundName = "Dyr fond", soldAtEpochDay = LocalDate.now().toEpochDay(), sourceRecordId = 77),
            )
            // Avgiftsraden blir Sålt — fortfarande ett byte, och märket blinkar aldrig till 2.
            dispatcher.scheduler.advanceUntilIdle()
            expectNoEvents()
            assertEquals(1, vm.count.value)

            // Avbrutet: förslaget väntar igen.
            switchWatchRepo.close(id, SwitchWatchCloseReason.AVBRUTEN, LocalDate.now())
            dispatcher.scheduler.advanceUntilIdle()
            expectNoEvents()
            assertEquals(1, vm.count.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun meta(isin: String, name: String, fee: Double) = FundMetadata(
        isin = isin, name = name, orderbookId = isin, totalFee = fee, managementFee = fee,
        category = null, fundType = null, companyName = null, risk = 5, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
    )
}
