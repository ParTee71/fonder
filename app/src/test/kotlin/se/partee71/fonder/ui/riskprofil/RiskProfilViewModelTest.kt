package se.partee71.fonder.ui.riskprofil

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.RiskProfileAnswers
import se.partee71.fonder.domain.model.TimeHorizon
import se.partee71.fonder.domain.usecase.FeeComparisonCalc

@OptIn(ExperimentalCoroutinesApi::class)
class RiskProfilViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferencesRepository: PreferencesRepository
    private var knownLevels: List<Int> = listOf(1, 2, 3, 4, 5, 6)

    private val fakeFundMetadataRepo = object : FundMetadataRepository {
        override suspend fun query(query: FundScreenQuery): List<FundMetadata> = emptyList()
        override suspend fun resolveHandelsbankenAvailability(isin: String): Boolean? = null
        override fun observeFilterVocabulary() = flowOf(FundFilterVocabulary())
        override suspend fun suggestCheaperAlternatives(isin: String, holdingValue: Double): List<FeeComparisonCalc.Alternative>? = null
        override suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata> = emptyMap()
        override suspend fun knownRiskLevels(): List<Int> = knownLevels
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            produceFile = { tempFolder.newFile("riskprofil_test.preferences_pb") },
        )
        preferencesRepository = PreferencesRepository(dataStore)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = RiskProfilViewModel(preferencesRepository, fakeFundMetadataRepo)

    @Test
    fun `initialt tillstand har ingen niva och en tom enkat`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.availableLevels.isEmpty()) state = awaitItem()
            assertNull(state.horizon)
            assertNull(state.suggestedLevel)
            assertNull(state.selectedLevel)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `forslag berknas forst nar alla tre fragor ar besvarade`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.availableLevels.isEmpty()) state = awaitItem()

            vm.onHorizonSelected(TimeHorizon.OVER_15_AR)
            state = awaitItem()
            assertNull(state.suggestedLevel)

            vm.onReactionSelected(DownturnReaction.KOPER_MER)
            state = awaitItem()
            assertNull(state.suggestedLevel)

            vm.onGoalSelected(PrimaryGoal.MAXIMAL_TILLVAXT)
            state = awaitItem()
            assertEquals(6, state.suggestedLevel)
            assertEquals(6, state.selectedLevel)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett eget val av niva vinner over forslaget`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.availableLevels.isEmpty()) state = awaitItem()

            vm.onHorizonSelected(TimeHorizon.OVER_15_AR)
            awaitItem()
            vm.onReactionSelected(DownturnReaction.KOPER_MER)
            awaitItem()
            vm.onGoalSelected(PrimaryGoal.MAXIMAL_TILLVAXT)
            state = awaitItem()
            assertEquals(6, state.selectedLevel) // förslaget innan något eget val gjorts

            vm.onLevelSelected(2)
            state = awaitItem()
            assertEquals(2, state.selectedLevel)
            assertEquals(6, state.suggestedLevel) // förslaget kvarstår oförändrat, bara overridat

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `malniva kan sattas direkt utan att besvara enkaten`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.availableLevels.isEmpty()) state = awaitItem()

            vm.onLevelSelected(3)
            state = awaitItem()
            assertEquals(3, state.selectedLevel)
            assertNull(state.horizon)
            cancelAndIgnoreRemainingEvents()
        }

        vm.save()
        dispatcher.scheduler.advanceUntilIdle()
        val saved = preferencesRepository.riskProfile.first()
        assertEquals(3, saved?.targetRiskLevel)
        assertNull(saved?.answers)
    }

    @Test
    fun `save persisterar bade niva och svar nar enkaten ar fullstandig`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.availableLevels.isEmpty()) state = awaitItem()
            vm.onHorizonSelected(TimeHorizon.TRE_TILL_7_AR)
            awaitItem()
            vm.onReactionSelected(DownturnReaction.GOR_INGET)
            awaitItem()
            vm.onGoalSelected(PrimaryGoal.BALANSERAD)
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        val saved = preferencesRepository.riskProfile.first()
        assertEquals(RiskProfileAnswers(TimeHorizon.TRE_TILL_7_AR, DownturnReaction.GOR_INGET, PrimaryGoal.BALANSERAD), saved?.answers)
    }

    @Test
    fun `en tidigare sparad profil forifyller enkaten och nivan`() = runTest(dispatcher) {
        preferencesRepository.setRiskProfile(
            RiskProfile(
                targetRiskLevel = 5,
                answers = RiskProfileAnswers(TimeHorizon.OVER_15_AR, DownturnReaction.KOPER_MER, PrimaryGoal.MAXIMAL_TILLVAXT),
            ),
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.selectedLevel == null) state = awaitItem()
            assertEquals(5, state.selectedLevel)
            assertEquals(TimeHorizon.OVER_15_AR, state.horizon)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tom skala fran kallan ger ingen niva och kraschar inte`() = runTest(dispatcher) {
        knownLevels = emptyList()
        val vm = viewModel()
        vm.uiState.test {
            awaitItem() // initialValue innan enkäten besvarats
            vm.onHorizonSelected(TimeHorizon.TRE_TILL_7_AR)
            var state = awaitItem()
            assertTrue(state.availableLevels.isEmpty())
            vm.onReactionSelected(DownturnReaction.GOR_INGET)
            awaitItem()
            vm.onGoalSelected(PrimaryGoal.BALANSERAD)
            state = awaitItem()
            assertNull(state.suggestedLevel)
            assertNull(state.selectedLevel)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save gor ingenting utan en vald niva`() = runTest(dispatcher) {
        knownLevels = emptyList()
        val vm = viewModel()
        vm.uiState.test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.saved)
        assertNull(preferencesRepository.riskProfile.first())
    }
}
