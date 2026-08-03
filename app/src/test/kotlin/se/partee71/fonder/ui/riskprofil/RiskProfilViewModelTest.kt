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
import se.partee71.fonder.domain.usecase.RiskProfileCalc

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
    fun `initialt tillstand har ingen egen andring och en tom enkat`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.availableLevels.isEmpty()) state = awaitItem()
            assertNull(state.horizon)
            assertNull(state.suggestedAllocation)
            assertFalse(state.hasManualEdit)
            assertFalse(state.canSave)
            assertTrue(state.availableLevels.all { (state.allocationText[it] ?: "") == "0" })
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
            assertNull(state.suggestedAllocation)

            vm.onReactionSelected(DownturnReaction.KOPER_MER)
            state = awaitItem()
            assertNull(state.suggestedAllocation)

            vm.onGoalSelected(PrimaryGoal.MAXIMAL_TILLVAXT)
            state = awaitItem()
            assertEquals(RiskProfileCalc.Profile.OFFENSIV.allocation, state.suggestedAllocation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett eget varde i ett falt vinner over forslaget i sin helhet`() = runTest(dispatcher) {
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
            assertFalse(state.hasManualEdit)

            vm.onAllocationPercentChanged(4, "100")
            state = awaitItem()
            assertTrue(state.hasManualEdit)
            assertEquals("100", state.allocationText[4])
            // Övriga nivåer var kvar på förslagets värden innan de själva rörs.
            assertEquals(RiskProfileCalc.Profile.OFFENSIV.allocation, state.suggestedAllocation)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en fordelning kan sattas direkt utan att besvara enkaten`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.availableLevels.isEmpty()) state = awaitItem()

            vm.onAllocationPercentChanged(3, "100")
            state = awaitItem()
            assertEquals(mapOf(3 to 1.0), state.effectiveAllocation)
            assertTrue(state.canSave)
            assertNull(state.horizon)
            cancelAndIgnoreRemainingEvents()
        }

        vm.save()
        dispatcher.scheduler.advanceUntilIdle()
        val saved = preferencesRepository.riskProfile.first()
        assertEquals(mapOf(3 to 1.0), saved?.targetAllocation)
        assertNull(saved?.answers)
    }

    @Test
    fun `en summa skild fran 100 spargar sparande`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.availableLevels.isEmpty()) state = awaitItem()

            vm.onAllocationPercentChanged(3, "50")
            state = awaitItem()
            assertEquals(50, state.allocationSumPercent)
            assertFalse(state.canSave)
            assertTrue(state.effectiveAllocation.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save gor ingenting nar summan inte ar 100`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.availableLevels.isEmpty()) state = awaitItem()
            vm.onAllocationPercentChanged(3, "50")
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.saved)
        assertNull(preferencesRepository.riskProfile.first())
    }

    @Test
    fun `save persisterar bade fordelning och svar nar enkaten ar fullstandig`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.availableLevels.isEmpty()) state = awaitItem()
            vm.onHorizonSelected(TimeHorizon.TRE_TILL_7_AR)
            awaitItem()
            vm.onReactionSelected(DownturnReaction.GOR_INGET)
            awaitItem()
            vm.onGoalSelected(PrimaryGoal.BALANSERAD)
            state = awaitItem()
            assertTrue(state.canSave) // enkätens förslag summerar redan till 100 %
            cancelAndIgnoreRemainingEvents()
        }

        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        val saved = preferencesRepository.riskProfile.first()
        assertEquals(RiskProfileAnswers(TimeHorizon.TRE_TILL_7_AR, DownturnReaction.GOR_INGET, PrimaryGoal.BALANSERAD), saved?.answers)
        assertEquals(RiskProfileCalc.suggest(saved!!.answers!!, knownLevels), saved.targetAllocation)
    }

    @Test
    fun `en tidigare sparad profil forifyller enkaten och fordelningen`() = runTest(dispatcher) {
        preferencesRepository.setRiskProfile(
            RiskProfile(
                targetAllocation = mapOf(3 to 0.25, 4 to 0.5, 5 to 0.25),
                answers = RiskProfileAnswers(TimeHorizon.OVER_15_AR, DownturnReaction.KOPER_MER, PrimaryGoal.MAXIMAL_TILLVAXT),
            ),
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.allocationSumPercent != 100) state = awaitItem()
            assertEquals(TimeHorizon.OVER_15_AR, state.horizon)
            assertEquals(mapOf(3 to 0.25, 4 to 0.5, 5 to 0.25), state.effectiveAllocation)
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
            assertNull(state.suggestedAllocation)
            assertFalse(state.canSave)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
