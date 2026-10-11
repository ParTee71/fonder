package se.partee71.fonder.ui.beslut

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeCachedPriceRepository
import se.partee71.fonder.data.repository.FakeSuggestionDismissalRepository
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.RiskProfileAnswers
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.SuggestionDismissal
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.TimeHorizon
import se.partee71.fonder.domain.usecase.PendingDecisionsSource
import se.partee71.fonder.domain.usecase.SuggestionDismissals
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import se.partee71.fonder.testing.testPreferencesRepository
import se.partee71.fonder.worker.BackgroundWork
import se.partee71.fonder.worker.FakeFundPriceRefreshScheduler
import java.time.LocalDate

/**
 * Fliken Beslut (NAV-9): bytesplanen (HEM-8) och de pågående bytena (ANA-12) ur den delade
 * [PendingDecisionsSource], med riktiga fakes under källan. Testerna om planen och bevakningarna
 * flyttades hit från `HemViewModelTest` när de lämnade Översikten — beteendet är detsamma.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BeslutViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var preferencesRepository: PreferencesRepository

    private val suggestionRepo = FakeSuggestionRecordRepository()
    private val switchWatchRepo = FakeSwitchWatchRepository()
    private val priceRepo = FakeCachedPriceRepository()
    private val metadataRepo = FakeFundMetadataRepository()
    private val scheduler = FakeFundPriceRefreshScheduler()
    private val dismissalRepo = FakeSuggestionDismissalRepository()

    private fun viewModel() = BeslutViewModel(
        PendingDecisionsSource(preferencesRepository, suggestionRepo, switchWatchRepo, priceRepo, metadataRepo, dismissalRepo),
        preferencesRepository,
        suggestionRepo,
        switchWatchRepo,
        dismissalRepo,
        scheduler,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        preferencesRepository = testPreferencesRepository(dispatcher, tempFolder, "beslut_test")
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun meta(isin: String, name: String, fee: Double, risk: Int) = FundMetadata(
        isin = isin, name = name, orderbookId = isin, totalFee = fee, managementFee = fee,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
    )

    private fun planRecord(
        buyIsin: String = "SE_CAND",
        planIndex: Int = 0,
        epochDay: Long = LocalDate.now().toEpochDay(),
        batch: Long = 0,
        valueKr: Double? = 2_500.0,
        id: Long = 42,
    ) = SuggestionRecord(
        id = id, suggestedAtEpochDay = epochDay, planIndex = planIndex,
        sellIsin = "SE_HELD", buyIsin = buyIsin,
        sellNavAtSuggestion = 120.0, buyNavAtSuggestion = 50.0,
        switchValueKr = valueKr, batchEpochMillis = batch,
    )

    /** Ett innehav på nivå 5 och en köpkandidat på nivå 3, båda i metadatacachen, och en inspelad plan med ett byte. */
    private fun setUpSwitchPlan() {
        metadataRepo.metadataByIsin = mapOf(
            "SE_HELD" to meta("SE_HELD", "Innehav", fee = 1.0, risk = 5),
            "SE_CAND" to meta("SE_CAND", "Kandidat", fee = 0.3, risk = 3),
        )
        suggestionRepo.records.value = listOf(planRecord())
    }

    private suspend fun loadedPlan(vm: BeslutViewModel): BeslutUiState {
        var result: BeslutUiState? = null
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.decisions.switchPlan.isEmpty()) state = awaitItem()
            result = state
            cancelAndIgnoreRemainingEvents()
        }
        return result!!
    }

    @Test
    fun `tomt utan plan och utan pagaende byte`() = runTest(dispatcher) {
        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.isEmpty)
            assertEquals(0, state.decisions.count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Rubrikraden: riskprofil och kontotyp ---

    @Test
    fun `rubrikraden speglar riskprofil och kontotyp`() = runTest(dispatcher) {
        val profile = RiskProfile(
            targetAllocation = mapOf(4 to 1.0),
            answers = RiskProfileAnswers(TimeHorizon.SJU_TILL_15_AR, DownturnReaction.GOR_INGET, PrimaryGoal.BALANSERAD),
        )
        preferencesRepository.setRiskProfile(profile)
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.riskProfile == null || state.accountType == null) state = awaitItem()
            assertEquals(profile, state.riskProfile)
            assertEquals(AccountType.ISK_KF, state.accountType)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Bytesplanen (HEM-8), flyttad från Hem ---

    @Test
    fun `switchPlan bar med radens id och genomford-markering`() = runTest(dispatcher) {
        // SET-5 (issue #80): utan id:t finns ingen nyckel att skriva "Genomförd" mot, och utan
        // markeringen kan facit inte skilja ett följt råd från ett bara givet.
        setUpSwitchPlan()
        suggestionRepo.records.value = suggestionRepo.records.value.map { it.copy(followed = true) }
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val switch = loadedPlan(viewModel()).decisions.switchPlan.single()
        assertEquals(42L, switch.recordId)
        assertTrue(switch.followed)
    }

    @Test
    fun `omarkerat forslag ar inte genomfort - null betyder inte markerat`() = runTest(dispatcher) {
        setUpSwitchPlan()
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        assertFalse(loadedPlan(viewModel()).decisions.switchPlan.single().followed)
    }

    @Test
    fun `setSwitchFollowed skriver mot inspelningen och speglas i planen`() = runTest(dispatcher) {
        setUpSwitchPlan()
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.decisions.switchPlan.isEmpty()) state = awaitItem()
            assertFalse(state.decisions.switchPlan.single().followed)
            assertEquals(1, state.decisions.count)

            vm.setSwitchFollowed(42, true)

            while (!state.decisions.switchPlan.single().followed) state = awaitItem()
            assertTrue(suggestionRepo.records.value.single { it.id == 42L }.followed == true)
            // Ett kvitterat byte är beslutat — det räknas inte längre i märket (NAV-9).
            assertEquals(0, state.decisions.count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switchPlan visar senaste inspelade byte i ISK-KF`() = runTest(dispatcher) {
        setUpSwitchPlan()
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val switch = loadedPlan(viewModel()).decisions.switchPlan.single()
        assertEquals("Innehav", switch.sellFundName)
        assertEquals("Kandidat", switch.buyFundName)
        assertEquals(5, switch.fromLevel)
        assertEquals(3, switch.toLevel)
        assertEquals(0.3 - 1.0, switch.feeDeltaPercent, 1e-9)
        // Beloppet är en del av rådet (bytet storleksbestäms till gapet, issue #75) och
        // måste nå UI-lagret — annars är raden tvetydig.
        assertEquals(2_500.0, switch.switchValueKr ?: -1.0, 1e-9)
    }

    @Test
    fun `switchPlan reagerar nar kontotypen andras under prenumeration`() = runTest(dispatcher) {
        // Regression (issue #75): kontotypen lästes med first() inne i map-kroppen, så tillståndet
        // emitterade aldrig om på en inställningsändring.
        setUpSwitchPlan()

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.decisions.switchPlan.isEmpty())

            preferencesRepository.setAccountType(AccountType.ISK_KF)

            while (state.decisions.switchPlan.isEmpty()) state = awaitItem()
            assertEquals("Innehav", state.decisions.switchPlan.single().sellFundName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switchPlan visar bara den senaste korningens plan, inte hela dygnets`() = runTest(dispatcher) {
        // Regression (issue #75): två körningar samma dygn slogs ihop — samma fond kunde säljas två
        // gånger och två rader bära planIndex 0. Batch-id:t håller ihop en körning. Den äldre
        // körningens köpkandidat har metadata, så raden *skulle* renderas om filtret inte fungerade.
        setUpSwitchPlan()
        metadataRepo.metadataByIsin = metadataRepo.metadataByIsin + ("SE_ANNAN" to meta("SE_ANNAN", "Annan kandidat", fee = 0.4, risk = 3))
        suggestionRepo.records.value = listOf(
            planRecord(buyIsin = "SE_ANNAN", batch = 1_000, valueKr = 1_000.0, id = 1),
            planRecord(batch = 2_000, id = 2),
        )
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val switch = loadedPlan(viewModel()).decisions.switchPlan.single()
        assertEquals("Kandidat", switch.buyFundName)
        assertEquals(2_500.0, switch.switchValueKr ?: -1.0, 1e-9)
    }

    @Test
    fun `switchPlan visas inte alls nar det forsta bytet inte gar att sla upp`() = runTest(dispatcher) {
        // Planen är girig och sekventiell: byte 1 förutsätter att byte 0 genomförts. Faller
        // byte 0 bort får resten inte visas som om det vore fristående (issue #75).
        setUpSwitchPlan()
        suggestionRepo.records.value = listOf(
            planRecord(buyIsin = "SE_UTAN_METADATA", batch = 2_000, id = 1),
            planRecord(planIndex = 1, batch = 2_000, id = 2),
        )
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.decisions.switchPlan.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switchPlan doljs nar den inspelade planen blivit for gammal`() = runTest(dispatcher) {
        // Regression (issue #75, punkt 5): ett gammalt "Sälj X → Köp Y" låg kvar, prissatt mot
        // en portfölj som sedan dess rört sig.
        setUpSwitchPlan()
        suggestionRepo.records.value = listOf(planRecord(epochDay = LocalDate.now().minusDays(SwitchPlanCalc.PLAN_TTL_DAYS + 1).toEpochDay()))
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.decisions.switchPlan.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switchPlan visas fortfarande dagen innan farskhetsgransen loper ut`() = runTest(dispatcher) {
        setUpSwitchPlan()
        suggestionRepo.records.value = listOf(planRecord(epochDay = LocalDate.now().minusDays(SwitchPlanCalc.PLAN_TTL_DAYS).toEpochDay()))
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        assertEquals("Innehav", loadedPlan(viewModel()).decisions.switchPlan.single().sellFundName)
    }

    @Test
    fun `switchPlan ar tom utan vald kontotyp`() = runTest(dispatcher) {
        setUpSwitchPlan()

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.decisions.switchPlan.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switchPlan ar tom i depa-AF`() = runTest(dispatcher) {
        setUpSwitchPlan()
        preferencesRepository.setAccountType(AccountType.DEPA_AF)

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.accountType == null) state = awaitItem()
            assertTrue(state.decisions.switchPlan.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Avstå (HEM-14) ---

    @Test
    fun `avsta doljer forslaget och tar det ur antalet - angra visar det igen`() = runTest(dispatcher) {
        setUpSwitchPlan()
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.decisions.switchPlan.isEmpty()) state = awaitItem()
            val suggestion = state.decisions.switchPlan.single()
            assertEquals(1, state.decisions.count)

            vm.dismissSuggestion(suggestion)

            while (state.decisions.switchPlan.isNotEmpty()) state = awaitItem()
            assertEquals(listOf(42L), state.decisions.dismissedSuggestions.map { it.recordId })
            // Märket, "Att besluta" och fliken läser samma antal (NAV-9, HEM-13).
            assertEquals(0, state.decisions.count)
            // Utan plan och pågående byte är fliken tom — avståendet ligger kvar i sin egen rad.
            assertTrue(state.isEmpty)
            // Förslaget självt rörs inte: det är fortfarande ett givet råd i facit (SET-5).
            assertNull(suggestionRepo.records.value.single().followed)
            assertEquals(
                listOf(SuggestionDismissal("SE_HELD", "SE_CAND", SuggestionKind.RISK_PLAN, LocalDate.now().toEpochDay())),
                dismissalRepo.dismissals.value,
            )

            vm.undoDismissal(suggestion)

            while (state.decisions.switchPlan.isEmpty()) state = awaitItem()
            assertTrue(state.decisions.dismissedSuggestions.isEmpty())
            assertEquals(1, state.decisions.count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett avstaende overlever att planen spelas in pa nytt nasta dag`() = runTest(dispatcher) {
        // Planen spelas in på nytt varje dygn med en ny rad (nytt id) för samma par — ett
        // avstående knutet till raden hade släppt igenom förslaget i morgon.
        setUpSwitchPlan()
        val yesterday = LocalDate.now().minusDays(1)
        suggestionRepo.records.value = listOf(planRecord(id = 1, epochDay = yesterday.toEpochDay()))
        dismissalRepo.dismiss("SE_HELD", "SE_CAND", SuggestionKind.RISK_PLAN, yesterday)
        suggestionRepo.records.value += planRecord(id = 2, batch = 5_000)
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.decisions.dismissedSuggestions.isEmpty()) state = awaitItem()
            assertTrue(state.decisions.switchPlan.isEmpty())
            assertEquals(2L, state.decisions.dismissedSuggestions.single().recordId)
            assertEquals(0, state.decisions.count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett utganget avstaende visar forslaget igen`() = runTest(dispatcher) {
        setUpSwitchPlan()
        dismissalRepo.dismiss("SE_HELD", "SE_CAND", SuggestionKind.RISK_PLAN, LocalDate.now().minusDays(SuggestionDismissals.TTL_DAYS + 1))
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val state = loadedPlan(viewModel())
        assertEquals(42L, state.decisions.switchPlan.single().recordId)
        assertTrue(state.decisions.dismissedSuggestions.isEmpty())
        assertEquals(1, state.decisions.count)
    }

    @Test
    fun `folj markerar forslaget som genomfort`() = runTest(dispatcher) {
        // "Följ" är samma kvittering som "Genomförd" (SET-5) — den skriver mot inspelningen.
        setUpSwitchPlan()
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val vm = viewModel()
        loadedPlan(vm)
        vm.setSwitchFollowed(42, true)
        advanceUntilIdle()

        assertEquals(true, suggestionRepo.records.value.single().followed)
    }

    // --- Omräkning av bytesplanen på begäran (HEM-8, issue #88) ---

    @Test
    fun `canRecomputeSwitchPlan kraver bade profil och ISK eller KF`() = runTest(dispatcher) {
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || !state.canRecomputeSwitchPlan) state = awaitItem()
            assertTrue(state.canRecomputeSwitchPlan)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `canRecomputeSwitchPlan ar falskt i depa eller AF`() = runTest(dispatcher) {
        // SET-4-gaten ger ingen plan där — knappen ska då inte lova en omräkning.
        preferencesRepository.setAccountType(AccountType.DEPA_AF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.riskProfile == null) state = awaitItem()
            assertFalse(state.canRecomputeSwitchPlan)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `canRecomputeSwitchPlan ar falskt utan sparad riskprofil`() = runTest(dispatcher) {
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.accountType == null) state = awaitItem()
            assertFalse(state.canRecomputeSwitchPlan)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `backgroundWorkRunning speglar schemalaggarens korstatus`() = runTest(dispatcher) {
        scheduler.runningWork.value = setOf(BackgroundWork.PRICE_REFRESH)

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || !state.backgroundWorkRunning) state = awaitItem()
            assertTrue(state.backgroundWorkRunning)
            // Ledaren i ett pågående byte räknas ur kurscachen — den snurrar, förslagen inte.
            assertTrue(state.switchWatchWorking)
            assertFalse(state.suggestionsWorking)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en bytesplansskanning snurrar forslagen`() = runTest(dispatcher) {
        // Flyttad från Hems riskkort (NAV-6): skanningen skriver planen, och planen visas här.
        scheduler.runningWork.value = setOf(BackgroundWork.SWITCH_PLAN_SCAN)

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || !state.switchPlanWorking) state = awaitItem()
            assertTrue(state.suggestionsWorking)
            assertFalse(state.switchWatchWorking)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `recomputeSwitchPlan ber schemalaggaren om en skanning`() = runTest(dispatcher) {
        viewModel().recomputeSwitchPlan()

        assertEquals(1, scheduler.switchPlanScans)
    }

    // --- Pågående byten (ANA-12), flyttade från Hem ---

    @Test
    fun `en oppen bevakning visas med ledaren ur cachade kurser`() = runTest(dispatcher) {
        // Kortet ska rendera utan nätverk: ledaren räknas ur kurscachen, som bakgrundskörningen
        // fyller budgeterat — inte ur en hämtning Beslut gör själv.
        priceRepo.latestPrices.value = mapOf(
            "SE_A" to FundPrice("SE_A", LocalDate.now().toEpochDay(), 105.0),
            "SE_B" to FundPrice("SE_B", LocalDate.now().toEpochDay(), 110.0),
        )
        switchWatchRepo.start(
            SwitchWatch(
                sellIsin = "SE_SALJ",
                sellFundName = "Fond A",
                soldAtEpochDay = LocalDate.now().minusDays(2).toEpochDay(),
                proceedsKr = 10_000.0,
                candidates = listOf(
                    SwitchWatchCandidate(isin = "SE_A", name = "Kandidat A", navAtStart = 100.0),
                    SwitchWatchCandidate(isin = "SE_B", name = "Kandidat B", navAtStart = 100.0),
                ),
            ),
        )

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.decisions.openWatches.isEmpty()) state = awaitItem()

            val watch = state.decisions.openWatches.single()
            assertEquals("Fond A", watch.sellFundName)
            assertEquals(2, watch.daysWaiting)
            assertEquals(2, watch.candidateCount)
            assertEquals("Kandidat B", watch.leaderName)
            assertEquals(0.10, watch.leaderChangeFraction!!, 1e-9)
            assertEquals(setOf("SE_SALJ"), state.decisions.watchedSellIsins)
            assertEquals(1, state.decisions.count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `alla oppna bevakningar visas, inte bara den forsta`() = runTest(dispatcher) {
        val today = LocalDate.now().toEpochDay()
        switchWatchRepo.start(SwitchWatch(sellIsin = "SE_1", sellFundName = "Fond 1", soldAtEpochDay = today - 1))
        switchWatchRepo.start(SwitchWatch(sellIsin = "SE_2", sellFundName = "Fond 2", soldAtEpochDay = today))

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.decisions.openWatches.size < 2) state = awaitItem()
            assertEquals(setOf("Fond 1", "Fond 2"), state.decisions.openWatches.map { it.sellFundName }.toSet())
            // Ingen kandidat utvärderad — sägs ut i stället för en tom ledare (ANA-4-principen).
            assertTrue(state.decisions.openWatches.all { it.leaderName == null })
            assertEquals(2, state.decisions.count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en utgangen bevakning visas inte men blockerar fortfarande en ny for samma salj`() = runTest(dispatcher) {
        // Den beskriver inte längre läget (ANA-12) — men att samtidigt dölja den och erbjuda en
        // ny hade gett två rader om samma byte tills bakgrundskörningen hunnit stänga den.
        switchWatchRepo.start(
            SwitchWatch(
                sellIsin = "SE_SALJ",
                sellFundName = "Fond A",
                soldAtEpochDay = LocalDate.now().minusDays(SwitchWatchCalc.WATCH_TTL_DAYS + 1).toEpochDay(),
            ),
        )

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.decisions.watchedSellIsins.isEmpty()) state = awaitItem()

            assertTrue(state.decisions.openWatches.isEmpty())
            assertEquals(setOf("SE_SALJ"), state.decisions.watchedSellIsins)
            assertEquals(0, state.decisions.count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startSwitchWatch skapar en bevakning ur forslaget och startar den bara en gang`() = runTest(dispatcher) {
        val vm = viewModel()
        val suggestion = SwitchPlanResolver.Suggestion(
            recordId = 42,
            planIndex = 0,
            sellIsin = "SE_SALJ",
            buyIsin = "SE_KOP",
            sellFundName = "Fond A",
            buyFundName = "Fond B",
            fromLevel = 6,
            toLevel = 4,
            feeDeltaPercent = -0.1,
            switchValueKr = 4_200.0,
            followed = true,
        )

        vm.startSwitchWatch(suggestion)
        advanceUntilIdle()
        vm.startSwitchWatch(suggestion)
        advanceUntilIdle()

        val watch = switchWatchRepo.watches.value.single()
        assertEquals("SE_SALJ", watch.sellIsin)
        assertEquals("Fond A", watch.sellFundName)
        assertEquals(LocalDate.now().toEpochDay(), watch.soldAtEpochDay)
        assertEquals(4_200.0, watch.proceedsKr!!, 1e-9)
        // Målnivån är den nivå bytet skulle fylla — annars föreslås alternativ på fel risk.
        assertEquals(4, watch.targetLevel)
        assertEquals(42L, watch.sourceRecordId)
    }

    @Test
    fun `en nystartad bevakning signaleras en gang och kvitteras`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            vm.startSwitchWatch(
                SwitchPlanResolver.Suggestion(
                    recordId = 7, planIndex = 0, sellIsin = "SE_SALJ", buyIsin = "SE_KOP",
                    sellFundName = "Fond A", buyFundName = "Fond B", fromLevel = 6, toLevel = 4, feeDeltaPercent = 0.0,
                ),
            )
            while (state.startedSwitchWatchId == null) state = awaitItem()
            assertEquals(switchWatchRepo.watches.value.single().id, state.startedSwitchWatchId)

            vm.onSwitchWatchOpened()
            while (state.startedSwitchWatchId != null) state = awaitItem()
            assertNull(state.startedSwitchWatchId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Hur gick det? (SET-5) ---

    @Test
    fun `facitraden raknar genomforda forslag over hela historiken`() = runTest(dispatcher) {
        suggestionRepo.records.value = listOf(
            planRecord(id = 1).copy(followed = true),
            planRecord(id = 2, epochDay = LocalDate.now().minusDays(30).toEpochDay()).copy(followed = true),
            planRecord(id = 3).copy(followed = false),
            planRecord(id = 4),
        )

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.followedCount == 0) state = awaitItem()
            assertEquals(2, state.followedCount)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
