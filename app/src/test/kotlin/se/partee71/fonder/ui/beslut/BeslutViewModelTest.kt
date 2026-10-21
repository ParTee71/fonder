package se.partee71.fonder.ui.beslut

import app.cash.turbine.test
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FakeCachedPriceRepository
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeSuggestionDismissalRepository
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.data.repository.FakeTransactionRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.RiskProfileAnswers
import se.partee71.fonder.domain.model.SuggestionDismissal
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.model.TimeHorizon
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.DismissedPlanSource
import se.partee71.fonder.domain.usecase.FacitSummary
import se.partee71.fonder.domain.usecase.InProgressSwitches
import se.partee71.fonder.domain.usecase.PendingDecisionsSource
import se.partee71.fonder.domain.usecase.RecordTransaction
import se.partee71.fonder.domain.usecase.SuggestionDismissals
import se.partee71.fonder.domain.usecase.SwitchOrigin
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.domain.usecase.SwitchRow
import se.partee71.fonder.domain.usecase.SwitchRowState
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import se.partee71.fonder.testing.FailingUntilRetry
import se.partee71.fonder.testing.awaitRecoveryAfterRetry
import se.partee71.fonder.testing.testPreferencesRepository
import se.partee71.fonder.testing.testTargetLevelPreselection
import se.partee71.fonder.ui.navigation.TransactionFormKey
import se.partee71.fonder.worker.BackgroundWork
import se.partee71.fonder.worker.FakeFundPriceRefreshScheduler

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
    private val transactionRepo = FakeTransactionRepository()

    private fun viewModel(facitTransactions: TransactionRepository = transactionRepo) = BeslutViewModel(
        PendingDecisionsSource(
            preferencesRepository,
            suggestionRepo,
            switchWatchRepo,
            priceRepo,
            metadataRepo,
            DismissedPlanSource(suggestionRepo, dismissalRepo, preferencesRepository, metadataRepo),
            InProgressSwitches(switchWatchRepo, suggestionRepo)
        ),
        FacitSummary(suggestionRepo, facitTransactions, priceRepo, metadataRepo, dispatcher),
        preferencesRepository,
        RecordTransaction(
            transactionRepo,
            switchWatchRepo,
            suggestionRepo,
            metadataRepo,
            scheduler,
            testTargetLevelPreselection(transactionRepo, switchWatchRepo)
        ),
        dismissalRepo,
        scheduler
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
        startDateEpochDay = null, minimumBuy = null, tags = emptyList()
    )

    private fun planRecord(
        buyIsin: String = "SE_CAND",
        planIndex: Int = 0,
        epochDay: Long = LocalDate.now().toEpochDay(),
        batch: Long = 0,
        valueKr: Double? = 2_500.0,
        id: Long = 42
    ) = SuggestionRecord(
        id = id, suggestedAtEpochDay = epochDay, planIndex = planIndex,
        sellIsin = "SE_HELD", buyIsin = buyIsin,
        sellNavAtSuggestion = 120.0, buyNavAtSuggestion = 50.0,
        switchValueKr = valueKr, batchEpochMillis = batch
    )

    /** Ett innehav på nivå 5 och en köpkandidat på nivå 3, båda i metadatacachen, och en inspelad plan med ett byte. */
    private fun setUpSwitchPlan() {
        metadataRepo.metadataByIsin = mapOf(
            "SE_HELD" to meta("SE_HELD", "Innehav", fee = 1.0, risk = 5),
            "SE_CAND" to meta("SE_CAND", "Kandidat", fee = 0.3, risk = 3)
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

    @Test
    fun `ett läsfel i databasen visas som fel i stället för en tom lista (NFR-6)`() = runTest(dispatcher) {
        val failing = object : TransactionRepository by transactionRepo {
            override fun observeFunds(): Flow<List<Fund>> = flow { throw DataError.Database }
        }
        viewModel(facitTransactions = failing).uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(DataError.Database, state.error?.cause)
            assertFalse(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Rubrikraden: riskprofil och kontotyp ---

    @Test
    fun `Försök igen läser om besluten efter ett läsfel (NFR-6)`() = runTest(dispatcher) {
        val source = FailingUntilRetry()
        val flakyRepo = object : TransactionRepository by transactionRepo {
            override fun observeFunds(): Flow<List<Fund>> = source.guard { transactionRepo.observeFunds() }
        }
        val vm = viewModel(facitTransactions = flakyRepo)
        vm.uiState.test {
            val state = awaitRecoveryAfterRetry(source, { vm.onEvent(BeslutEvent.Retry) }, { it.loading }, { it.error })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `rubrikraden speglar riskprofil och kontotyp`() = runTest(dispatcher) {
        val profile = RiskProfile(
            targetAllocation = mapOf(4 to 1.0),
            answers = RiskProfileAnswers(TimeHorizon.SJU_TILL_15_AR, DownturnReaction.GOR_INGET, PrimaryGoal.BALANSERAD)
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
    fun `en kvittering i Facit speglas i planen och tas ur antalet`() = runTest(dispatcher) {
        setUpSwitchPlan()
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.decisions.switchPlan.isEmpty()) state = awaitItem()
            assertFalse(state.decisions.switchPlan.single().followed)
            assertEquals(1, state.decisions.count)

            // Kvitteringen görs bara i Facit (SET-5) sedan Följ går via formuläret (BYT-2).
            suggestionRepo.setFollowed(42, true)

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
            planRecord(batch = 2_000, id = 2)
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
            planRecord(planIndex = 1, batch = 2_000, id = 2)
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

            vm.onEvent(BeslutEvent.DismissSuggestion(suggestion))

            while (state.decisions.switchPlan.isNotEmpty()) state = awaitItem()
            assertEquals(listOf(42L), state.decisions.dismissedSuggestions.map { it.recordId })
            // Avståendet räknar om planen (HEM-8): paret tas ur kandidatmängden — utan forcerad kursuppdatering.
            assertEquals(1, scheduler.switchPlanScans)
            assertEquals(0, scheduler.forcedSwitchPlanScans)
            // Märket, "Att besluta" och fliken läser samma antal (NAV-9, HEM-13).
            assertEquals(0, state.decisions.count)
            // Utan plan och pågående byte är fliken tom — avståendet ligger kvar i sin egen rad.
            assertTrue(state.isEmpty)
            // Förslaget självt rörs inte: det är fortfarande ett givet råd i facit (SET-5).
            assertNull(suggestionRepo.records.value.single().followed)
            assertEquals(
                listOf(SuggestionDismissal("SE_HELD", "SE_CAND", SuggestionKind.RISK_PLAN, LocalDate.now().toEpochDay())),
                dismissalRepo.dismissals.value
            )

            vm.onEvent(BeslutEvent.UndoDismissal(suggestion))

            while (state.decisions.switchPlan.isEmpty()) state = awaitItem()
            assertTrue(state.decisions.dismissedSuggestions.isEmpty())
            assertEquals(1, state.decisions.count)
            // Ångra räknar också om — paret är tillbaka i kandidatmängden.
            assertEquals(2, scheduler.switchPlanScans)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett avstatt byte kan angras aven nar planen raknats om utan paret`() = runTest(dispatcher) {
        // Efter avståendet tar omräkningen paret ur planen (HEM-8) — den nya inspelningen har ett
        // annat köp. Raden att ångra ska ändå finnas kvar, ur den senaste inspelningen per par.
        setUpSwitchPlan()
        metadataRepo.metadataByIsin = metadataRepo.metadataByIsin + ("SE_ANNAN" to meta("SE_ANNAN", "Annan kandidat", fee = 0.4, risk = 3))
        dismissalRepo.dismiss("SE_HELD", "SE_CAND", SuggestionKind.RISK_PLAN, LocalDate.now())
        suggestionRepo.records.value = listOf(
            planRecord(id = 1, batch = 1_000),
            planRecord(id = 2, buyIsin = "SE_ANNAN", batch = 2_000)
        )
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.decisions.dismissedSuggestions.isEmpty()) state = awaitItem()
            assertEquals(listOf(2L), state.decisions.switchPlan.map { it.recordId })
            assertEquals(listOf(1L), state.decisions.dismissedSuggestions.map { it.recordId })
            assertEquals(listOf(SwitchRowState.DISMISSED), state.switches.closed.map { it.state })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `avsta pa ett billigare alternativ raknar inte om planen`() = runTest(dispatcher) {
        setUpCheaperAlternative()

        val vm = viewModel()
        vm.onEvent(BeslutEvent.DismissSuggestion(loadedCheaper(vm).decisions.cheaperAlternatives.single().suggestion))
        advanceUntilIdle()

        assertEquals(0, scheduler.switchPlanScans)
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
    fun `folj oppnar saljformularet for forslaget och kvitterar ingenting`() = runTest(dispatcher) {
        // HEM-14/BYT-2: Följ navigerar till säljformuläret förifyllt för förslaget — ingen
        // kvittering utan transaktion. Det sparade säljet startar bytet (RecordTransactionTest).
        setUpSwitchPlan()
        preferencesRepository.setAccountType(AccountType.ISK_KF)

        val switch = loadedPlan(viewModel()).decisions.switchPlan.single()
        val key = TransactionFormKey.follow(switch)
        advanceUntilIdle()

        assertEquals(TransactionFormKey(fundIsin = "SE_HELD", type = TransactionType.SALJ, suggestionRecordId = 42, prefillAmountKr = 2_500.0), key)
        assertTrue(key.prefilled)
        assertNull(suggestionRepo.records.value.single().followed)
    }

    // --- Billigare alternativ (ANA-9) som egna förslag ---

    /** Ett innehav vars senaste jämförelse visar ett billigare alternativ, och dess inspelade avgiftsbyte. */
    private fun setUpCheaperAlternative(followed: Boolean? = null) {
        metadataRepo.metadataByIsin = mapOf(
            "SE_HELD" to meta("SE_HELD", "Sverige Småbolag", fee = 1.4, risk = 5).copy(shownAlternativeIsins = listOf("SE_IDX")),
            "SE_IDX" to meta("SE_IDX", "Småbolag Index", fee = 0.4, risk = 5)
        )
        suggestionRepo.records.value = listOf(
            planRecord(id = 77, buyIsin = "SE_IDX", valueKr = 96_000.0).copy(kind = SuggestionKind.FEE, followed = followed)
        )
    }

    private suspend fun loadedCheaper(vm: BeslutViewModel): BeslutUiState {
        var result: BeslutUiState? = null
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.decisions.cheaperAlternatives.isEmpty()) state = awaitItem()
            result = state
            cancelAndIgnoreRemainingEvents()
        }
        return result!!
    }

    @Test
    fun `ett billigare alternativ visas och raknas aven utan bytesplan och kontotyp`() = runTest(dispatcher) {
        // ANA-9 gatas inte på kontotypen (SET-4) — det gör bara bytesplanen.
        setUpCheaperAlternative()

        val state = loadedCheaper(viewModel())

        val alternative = state.decisions.cheaperAlternatives.single()
        assertEquals("Sverige Småbolag", alternative.suggestion.sellFundName)
        assertEquals("Småbolag Index", alternative.suggestion.buyFundName)
        assertEquals(960.0, alternative.annualSavingsKr!!, 1e-6)
        assertTrue(state.decisions.switchPlan.isEmpty())
        assertEquals(1, state.decisions.count)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `avsta pa ett billigare alternativ skriver sorten FEE - angra visar det igen`() = runTest(dispatcher) {
        setUpCheaperAlternative()

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.decisions.cheaperAlternatives.isEmpty()) state = awaitItem()
            val suggestion = state.decisions.cheaperAlternatives.single().suggestion

            vm.onEvent(BeslutEvent.DismissSuggestion(suggestion))

            while (state.decisions.cheaperAlternatives.isNotEmpty()) state = awaitItem()
            assertEquals(listOf(77L), state.decisions.dismissedCheaperAlternatives.map { it.suggestion.recordId })
            assertEquals(0, state.decisions.count)
            assertEquals(
                listOf(SuggestionDismissal("SE_HELD", "SE_IDX", SuggestionKind.FEE, LocalDate.now().toEpochDay())),
                dismissalRepo.dismissals.value
            )

            vm.onEvent(BeslutEvent.UndoDismissal(suggestion))

            while (state.decisions.cheaperAlternatives.isEmpty()) state = awaitItem()
            assertTrue(state.decisions.dismissedCheaperAlternatives.isEmpty())
            assertEquals(1, state.decisions.count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en kvittering i Facit av ett billigare alternativ tar det ur antalet`() = runTest(dispatcher) {
        // Samma rad som Facit skriver mot (SET-5).
        setUpCheaperAlternative()

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.decisions.cheaperAlternatives.isEmpty()) state = awaitItem()

            suggestionRepo.setFollowed(77, true)

            while (!state.decisions.cheaperAlternatives.single().suggestion.followed) state = awaitItem()
            assertEquals(true, suggestionRepo.records.value.single().followed)
            assertEquals(0, state.decisions.count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `bevaka alternativ pa ett kvitterat billigare alternativ bevakar hela positionen`() = runTest(dispatcher) {
        setUpCheaperAlternative(followed = true)

        val vm = viewModel()
        vm.onEvent(BeslutEvent.StartSwitchWatch(loadedCheaper(vm).decisions.cheaperAlternatives.single().suggestion))
        advanceUntilIdle()

        val watch = switchWatchRepo.watches.value.single()
        assertEquals("SE_HELD", watch.sellIsin)
        assertEquals(96_000.0, watch.proceedsKr!!, 1e-9)
        assertEquals(5, watch.targetLevel)
        assertEquals(77L, watch.sourceRecordId)
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
            // Ledaren i ett sålt byte räknas ur kurscachen — listan Byten snurrar.
            assertTrue(state.switchesWorking)
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
            assertTrue(state.switchesWorking)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `recomputeSwitchPlan ber schemalaggaren om en skanning`() = runTest(dispatcher) {
        viewModel().onEvent(BeslutEvent.RecomputeSwitchPlan)

        assertEquals(1, scheduler.switchPlanScans)
        // Användarens uttryckliga omräkning hämtar alla kurser först — till skillnad från omräkningen efter en transaktion.
        assertEquals(1, scheduler.forcedSwitchPlanScans)
    }

    // --- Pågående byten (ANA-12), flyttade från Hem ---

    @Test
    fun `en oppen bevakning visas med ledaren ur cachade kurser`() = runTest(dispatcher) {
        // Kortet ska rendera utan nätverk: ledaren räknas ur kurscachen, som bakgrundskörningen
        // fyller budgeterat — inte ur en hämtning Beslut gör själv.
        priceRepo.latestPrices.value = mapOf(
            "SE_A" to FundPrice("SE_A", LocalDate.now().toEpochDay(), 105.0),
            "SE_B" to FundPrice("SE_B", LocalDate.now().toEpochDay(), 110.0)
        )
        switchWatchRepo.start(
            SwitchWatch(
                sellIsin = "SE_SALJ",
                sellFundName = "Fond A",
                soldAtEpochDay = LocalDate.now().minusDays(2).toEpochDay(),
                proceedsKr = 10_000.0,
                candidates = listOf(
                    SwitchWatchCandidate(isin = "SE_A", name = "Kandidat A", navAtStart = 100.0),
                    SwitchWatchCandidate(isin = "SE_B", name = "Kandidat B", navAtStart = 100.0)
                )
            )
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
            assertEquals(10_000.0, watch.proceedsKr!!, 1e-9)
            assertEquals(SwitchOrigin.STANDALONE, (state.switches.active.single() as SwitchRow.Sold).origin)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en planrad vars saljfond ar sald markeras som pagaende`() = runTest(dispatcher) {
        // Bytet är sålt, inte bara föreslaget (BYT-1): raden ska inte erbjudas som "Sälj X" igen.
        setUpSwitchPlan()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        switchWatchRepo.start(
            SwitchWatch(sellIsin = "SE_HELD", sellFundName = "Innehav", soldAtEpochDay = LocalDate.now().toEpochDay(), sellTransactionId = 9, sourceRecordId = 42)
        )

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.decisions.switchPlan.isEmpty() || state.decisions.openWatches.isEmpty()) state = awaitItem()
            assertTrue(state.decisions.switchPlan.single().inProgress)
            assertEquals(setOf("SE_HELD"), state.decisions.watchedSellIsins)
            // Ett byte, en rad (BYT-4): Sålt-raden ersätter planraden och bär dess plats i planen.
            val sold = state.switches.active.single() as SwitchRow.Sold
            assertEquals(SwitchOrigin.PLAN, sold.origin)
            assertEquals(0, sold.planIndex)
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
                soldAtEpochDay = LocalDate.now().minusDays(SwitchWatchCalc.WATCH_TTL_DAYS + 1).toEpochDay()
            )
        )

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.decisions.watchedSellIsins.isEmpty()) state = awaitItem()

            assertTrue(state.decisions.openWatches.isEmpty())
            assertEquals(setOf("SE_SALJ"), state.decisions.watchedSellIsins)
            assertEquals(0, state.decisions.count)
            // Den står som Utgånget i den hopfällda sektionen tills bakgrundskörningen stängt den (BYT-4).
            assertEquals(listOf(SwitchRowState.EXPIRED), state.switches.closed.map { it.state })
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
            followed = true
        )

        vm.onEvent(BeslutEvent.StartSwitchWatch(suggestion))
        advanceUntilIdle()
        vm.onEvent(BeslutEvent.StartSwitchWatch(suggestion))
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

            vm.onEvent(
                BeslutEvent.StartSwitchWatch(
                    SwitchPlanResolver.Suggestion(
                        recordId = 7, planIndex = 0, sellIsin = "SE_SALJ", buyIsin = "SE_KOP",
                        sellFundName = "Fond A", buyFundName = "Fond B", fromLevel = 6, toLevel = 4, feeDeltaPercent = 0.0
                    )
                )
            )
            while (state.startedSwitchWatchId == null) state = awaitItem()
            assertEquals(switchWatchRepo.watches.value.single().id, state.startedSwitchWatchId)

            vm.onEvent(BeslutEvent.SwitchWatchOpened)
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
            planRecord(id = 4)
        )

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.followedCount == 0) state = awaitItem()
            assertEquals(2, state.followedCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `facitraden bar de foljda radernas resultat i kronor ur facit`() = runTest(dispatcher) {
        // planRecord: sälj 120 → 126 (+5 %), köp 50 → 56 (+12 %) → +7 pp på 2 500 kr = +175 kr.
        // Säljfonden är bevakad, så dess kurs ligger under fondens fundId — samma uppslag som Facit.
        transactionRepo.funds.value = listOf(Fund(fundId = "SHB1", name = "Innehav", isin = "SE_HELD"))
        priceRepo.latestPrices.value = mapOf(
            "SHB1" to FundPrice(fundId = "SHB1", epochDay = 20_100, nav = 126.0),
            "SE_CAND" to FundPrice(fundId = "SE_CAND", epochDay = 20_100, nav = 56.0)
        )
        // Förslaget gjordes före kursdagen 20 100: mätdagen är den senaste dag båda fonderna har en kurs,
        // tidigast förslagsdagen (issue #197).
        suggestionRepo.records.value = listOf(planRecord(id = 1, epochDay = 20_000).copy(followed = true), planRecord(id = 2))

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.followedExcessKr == null) state = awaitItem()
            assertEquals(1, state.followedCount)
            assertEquals(175.0, state.followedExcessKr!!, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `facitradens kronor mats pa samma dag for salj- och kopfonden`() = runTest(dispatcher) {
        // Issue #197: säljfonden har en nyare kurs (dag 20 104, 60 kr) än köpfonden. Båda mäts dag 20 100
        // — +7 pp på 2 500 kr = +175 kr, inte säljfondens fall till 60 kr mot köpfondens äldre kurs.
        transactionRepo.funds.value = listOf(Fund(fundId = "SHB1", name = "Innehav", isin = "SE_HELD"))
        priceRepo.priceHistoryByFundId = mapOf("SHB1" to listOf(FundPrice(fundId = "SHB1", epochDay = 20_100, nav = 126.0)))
        priceRepo.latestPrices.value = mapOf(
            "SHB1" to FundPrice(fundId = "SHB1", epochDay = 20_104, nav = 60.0),
            "SE_CAND" to FundPrice(fundId = "SE_CAND", epochDay = 20_100, nav = 56.0)
        )
        suggestionRepo.records.value = listOf(planRecord(id = 1, epochDay = 20_000).copy(followed = true))

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.followedExcessKr == null) state = awaitItem()
            assertEquals(175.0, state.followedExcessKr!!, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `facitraden saknar belopp nar inget foljt forslag ar matbart`() = runTest(dispatcher) {
        // Ingen kurs i cachen: raden räknas men har inget utfall — beloppet är null, aldrig 0 kr.
        suggestionRepo.records.value = listOf(planRecord(id = 1).copy(followed = true))

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.followedCount == 0) state = awaitItem()
            assertNull(state.followedExcessKr)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Listan Byten (BYT-4) ---

    @Test
    fun `ett stangt byte syns i den hopfallda sektionen i 14 dagar och sedan inte`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val recent = switchWatchRepo.start(SwitchWatch(sellIsin = "SE_1", sellFundName = "Fond 1", soldAtEpochDay = today.toEpochDay() - 20))
        val old = switchWatchRepo.start(SwitchWatch(sellIsin = "SE_2", sellFundName = "Fond 2", soldAtEpochDay = today.toEpochDay() - 40))
        switchWatchRepo.close(recent, SwitchWatchCloseReason.AVBRUTEN, today.minusDays(SwitchWatchCalc.WATCH_TTL_DAYS))
        switchWatchRepo.close(old, SwitchWatchCloseReason.UTGANGEN, today.minusDays(SwitchWatchCalc.WATCH_TTL_DAYS + 1))

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.switches.closed.isEmpty()) state = awaitItem()
            assertEquals(listOf("closed-$recent"), state.switches.closed.map { it.key })
            assertEquals(SwitchRowState.CANCELLED, state.switches.closed.single().state)
            assertTrue(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett salt billigare alternativ blir en Salt-rad i stallet for avgiftsraden`() = runTest(dispatcher) {
        setUpCheaperAlternative()
        switchWatchRepo.start(
            SwitchWatch(sellIsin = "SE_HELD", sellFundName = "Sverige Småbolag", soldAtEpochDay = LocalDate.now().toEpochDay(), sourceRecordId = 77)
        )

        viewModel().uiState.test {
            var state = awaitItem()
            while (state.loading || state.decisions.cheaperAlternatives.isEmpty() || state.switches.active.isEmpty()) state = awaitItem()
            assertTrue(state.decisions.cheaperAlternatives.single().suggestion.inProgress)
            val sold = state.switches.active.single() as SwitchRow.Sold
            assertEquals(SwitchOrigin.FEE, sold.origin)
            assertEquals(1, state.decisions.count)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
