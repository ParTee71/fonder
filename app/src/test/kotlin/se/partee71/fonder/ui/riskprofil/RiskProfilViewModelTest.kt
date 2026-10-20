package se.partee71.fonder.ui.riskprofil

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import se.partee71.fonder.data.common.DataError
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
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import se.partee71.fonder.worker.FakeFundPriceRefreshScheduler

@OptIn(ExperimentalCoroutinesApi::class)
class RiskProfilViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferencesRepository: PreferencesRepository
    private var knownLevels: List<Int> = listOf(1, 2, 3, 4, 5, 6)

    /** Räknar bl.a. begärda omräkningar av bytesplanen (HEM-8, issue #88). */
    private val fakeScheduler = FakeFundPriceRefreshScheduler()

    private val fakeFundMetadataRepo = object : FundMetadataRepository {
        override suspend fun query(query: FundScreenQuery): List<FundMetadata> = emptyList()
        override suspend fun resolveHandelsbankenAvailability(isin: String): Boolean? = null
        override fun observeFilterVocabulary() = flowOf(FundFilterVocabulary())
        override suspend fun suggestCheaperAlternatives(isin: String, holdingValue: Double): List<FeeComparisonCalc.Alternative>? = null
        override suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata> = emptyMap()
        override suspend fun cachedRiskByFundName(): Map<String, Int> = emptyMap()
        override suspend fun cachedMetadataFor(isins: List<String>): Map<String, FundMetadata> = emptyMap()
        override suspend fun knownRiskLevels(): List<Int> = knownLevels
        override suspend fun findSwitchCandidates(level: Int, excludeIsins: Set<String>): List<SwitchPlanCalc.Candidate> = emptyList()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            produceFile = { tempFolder.newFile("riskprofil_test.preferences_pb") }
        )
        preferencesRepository = PreferencesRepository(dataStore)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(preferences: PreferencesRepository = preferencesRepository) = RiskProfilViewModel(preferences, fakeFundMetadataRepo, fakeScheduler)

    /** En DataStore vars läsning eller skrivning går fel — som en trasig fil (NFR-6). */
    private class FailingDataStore(private val failRead: Boolean) : DataStore<Preferences> {
        override val data: Flow<Preferences> = if (failRead) flow { throw IllegalStateException("trasig") } else flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("full disk")
    }

    @Test
    fun `initialt tillstand har ingen egen andring och en tom enkat`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.editor.loading || state.availableLevels.isEmpty()) state = awaitItem()
            assertNull(state.horizon)
            assertNull(state.suggestedAllocation)
            assertFalse(state.hasManualEdit)
            assertFalse(state.editor.canSave)
            assertTrue(state.availableLevels.all { (state.allocationText[it] ?: "") == "0" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `forslag berknas forst nar alla tre fragor ar besvarade`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.editor.loading || state.availableLevels.isEmpty()) state = awaitItem()

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
            while (state.editor.loading || state.availableLevels.isEmpty()) state = awaitItem()

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
            while (state.editor.loading || state.availableLevels.isEmpty()) state = awaitItem()

            vm.onAllocationPercentChanged(3, "100")
            state = awaitItem()
            assertEquals(mapOf(3 to 1.0), state.effectiveAllocation)
            assertTrue(state.editor.canSave)
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
            while (state.editor.loading || state.availableLevels.isEmpty()) state = awaitItem()

            vm.onAllocationPercentChanged(3, "50")
            state = awaitItem()
            assertEquals(50, state.allocationSumPercent)
            assertFalse(state.editor.canSave)
            assertTrue(state.effectiveAllocation.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save gor ingenting nar summan inte ar 100`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.editor.loading || state.availableLevels.isEmpty()) state = awaitItem()
            vm.onAllocationPercentChanged(3, "50")
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.editor.done)
        assertNull(preferencesRepository.riskProfile.first())
    }

    @Test
    fun `save persisterar bade fordelning och svar nar enkaten ar fullstandig`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.editor.loading || state.availableLevels.isEmpty()) state = awaitItem()
            vm.onHorizonSelected(TimeHorizon.TRE_TILL_7_AR)
            awaitItem()
            vm.onReactionSelected(DownturnReaction.GOR_INGET)
            awaitItem()
            vm.onGoalSelected(PrimaryGoal.BALANSERAD)
            state = awaitItem()
            assertTrue(state.editor.canSave) // enkätens förslag summerar redan till 100 %
            cancelAndIgnoreRemainingEvents()
        }

        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        val saved = preferencesRepository.riskProfile.first()
        assertEquals(RiskProfileAnswers(TimeHorizon.TRE_TILL_7_AR, DownturnReaction.GOR_INGET, PrimaryGoal.BALANSERAD), saved?.answers)
        assertEquals(RiskProfileCalc.suggest(saved!!.answers!!, knownLevels), saved.targetAllocation)
    }

    @Test
    fun `sparat kvitteras och kommer inte tillbaka`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.editor.loading || state.availableLevels.isEmpty()) state = awaitItem()
            vm.onAllocationPercentChanged(3, "100")
            state = awaitItem()
            assertTrue(state.editor.canSave)

            vm.save()
            while (!state.editor.done) state = awaitItem()

            // Skärmen har gått tillbaka och kvitterar — händelsen är borta och kommer inte igen.
            vm.events.onDoneHandled()
            assertFalse(awaitItem().editor.done)
            dispatcher.scheduler.advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en tidigare sparad profil forifyller enkaten och fordelningen`() = runTest(dispatcher) {
        preferencesRepository.setRiskProfile(
            RiskProfile(
                targetAllocation = mapOf(3 to 0.25, 4 to 0.5, 5 to 0.25),
                answers = RiskProfileAnswers(TimeHorizon.OVER_15_AR, DownturnReaction.KOPER_MER, PrimaryGoal.MAXIMAL_TILLVAXT)
            )
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.editor.loading || state.allocationSumPercent != 100) state = awaitItem()
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
            var state = awaitItem() // initialValue innan enkäten besvarats
            // Den sparade profilen (ingen) är läst — sedan ändras tillståndet bara av svaren nedan.
            while (state.editor.loading) state = awaitItem()
            vm.onHorizonSelected(TimeHorizon.TRE_TILL_7_AR)
            state = awaitItem()
            assertTrue(state.availableLevels.isEmpty())
            vm.onReactionSelected(DownturnReaction.GOR_INGET)
            awaitItem()
            vm.onGoalSelected(PrimaryGoal.BALANSERAD)
            state = awaitItem()
            assertNull(state.suggestedAllocation)
            assertFalse(state.editor.canSave)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Omräkning av bytesplanen vid sparad profil (HEM-8, issue #88) ---

    @Test
    fun `sparad andrad malfordelning ber om en omrakning av bytesplanen`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.editor.loading || state.availableLevels.isEmpty()) state = awaitItem()
            vm.onHorizonSelected(TimeHorizon.TRE_TILL_7_AR)
            awaitItem()
            vm.onReactionSelected(DownturnReaction.GOR_INGET)
            awaitItem()
            vm.onGoalSelected(PrimaryGoal.BALANSERAD)
            state = awaitItem()
            assertTrue(state.editor.canSave)
            cancelAndIgnoreRemainingEvents()
        }

        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, fakeScheduler.switchPlanScans)
    }

    @Test
    fun `oforandrad malfordelning kostar ingen ny skanning`() = runTest(dispatcher) {
        // En skanning kostar en källfråga plus budgeterad köpbarhetsverifiering per underviktad
        // nivå — att spara nya enkätsvar utan att ha ändrat fördelningen ska inte betala det priset.
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.editor.loading || state.availableLevels.isEmpty() || !state.hasManualEdit) state = awaitItem()
            vm.onHorizonSelected(TimeHorizon.OVER_15_AR)
            state = awaitItem()
            assertTrue(state.editor.canSave)
            cancelAndIgnoreRemainingEvents()
        }

        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, fakeScheduler.switchPlanScans)
        assertEquals(mapOf(3 to 1.0), preferencesRepository.riskProfile.first()?.targetAllocation)
    }

    // --- Spara först när något ändrats (UI-15) ---

    @Test
    fun `en oforandrad sparad profil gar inte att spara`() = runTest(dispatcher) {
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.editor.loading || state.availableLevels.isEmpty() || !state.hasManualEdit) state = awaitItem()
            assertTrue(state.valid)
            assertFalse(state.editor.isDirty)
            assertFalse(state.editor.canSave)

            vm.onAllocationPercentChanged(3, "50")
            vm.onAllocationPercentChanged(4, "50")
            while (state.allocationSumPercent != 100 || !state.editor.isDirty) state = awaitItem()
            assertTrue(state.editor.canSave)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Fel vid läsning och sparning (NFR-6) ---

    @Test
    fun `en sparad profil som inte gar att lasa visas som fel i stallet for en tom enkat`() = runTest(dispatcher) {
        val vm = viewModel(PreferencesRepository(FailingDataStore(failRead = true)))
        vm.uiState.test {
            var state = awaitItem()
            while (state.editor.loading) state = awaitItem()
            assertEquals(DataError.Unknown, state.editor.loadError?.cause)
            assertFalse(state.editor.canSave)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett sparfel sags och formularet star kvar`() = runTest(dispatcher) {
        val vm = viewModel(PreferencesRepository(FailingDataStore(failRead = false)))
        vm.uiState.test {
            var state = awaitItem()
            while (state.editor.loading || state.availableLevels.isEmpty()) state = awaitItem()
            vm.onAllocationPercentChanged(3, "100")
            state = awaitItem()
            assertTrue(state.editor.canSave)

            vm.save()
            while (state.editor.failure == null) state = awaitItem()
            assertEquals(DataError.Unknown, state.editor.failure?.cause)
            assertFalse(state.editor.done)
            assertEquals("100", state.allocationText[3])
            assertTrue(state.editor.canSave)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Forsok igen laser den sparade profilen pa nytt efter ett lasfel`() = runTest(dispatcher) {
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(4 to 1.0)))
        var failRead = true
        val flaky = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                if (failRead) throw IllegalStateException("trasig")
                emitAll(dataStore.data)
            }

            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = dataStore.updateData(transform)
        }
        val vm = viewModel(PreferencesRepository(flaky))
        vm.uiState.test {
            var state = awaitItem()
            while (state.editor.loading) state = awaitItem()
            assertEquals(DataError.Unknown, state.editor.loadError?.cause)

            failRead = false
            vm.retry()
            while (state.editor.loading || state.editor.loadError != null || !state.hasManualEdit) state = awaitItem()
            assertEquals(mapOf(4 to 1.0), state.effectiveAllocation)
            assertFalse(state.editor.isDirty)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
