package se.partee71.fonder.ui.transaktioner

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FollowedSuggestionLookup
import se.partee71.fonder.domain.usecase.RecordTransaction
import se.partee71.fonder.domain.usecase.SwitchDecision
import se.partee71.fonder.domain.usecase.SwitchOption
import se.partee71.fonder.testing.testPreferencesRepository
import se.partee71.fonder.testing.testSwitchChoicesSource
import se.partee71.fonder.testing.testTargetLevelPreselection
import se.partee71.fonder.ui.navigation.TransactionFormKey
import se.partee71.fonder.worker.FakeFundPriceRefreshScheduler

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionFormViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val fund = Fund(fundId = "SHB0000442", name = "Handelsbanken Amerika Småbolag Tema")
    private val funds = MutableStateFlow(listOf(fund))
    private val addedTransactions = mutableListOf<Transaction>()
    private var latestPriceValue: FundPrice? = null
    private val pricesByFundId = mutableMapOf<String, FundPrice>()

    /** Fonder källan känner på ISIN men som inte bevakas än ("Köpte den här" på en kandidat). */
    private val foundByIsin = mutableMapOf<String, Fund>()
    private val upsertedFunds = mutableListOf<Fund>()

    /** Det som skrivningen av en transaktion kastar — se testerna om sparfel och avbrott (NFR-6). */
    private var addFailure: Exception? = null

    /** Håller kvar skrivningen tills testet släpper den — så att det pågående sparandet syns. */
    private var addGate: CompletableDeferred<Unit>? = null
    private var addCalls = 0

    /** Fonden vars kursuppslag är långsamt — se testet om avbrutet uppslag. */
    private var slowFundId: String? = null

    private val fakeTransactionRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = funds
        override fun observeTransactions(): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override suspend fun upsertFund(fund: Fund) {
            upsertedFunds += fund
            funds.value = funds.value.filterNot { it.fundId == fund.fundId } + fund
        }
        override suspend fun addTransaction(tx: Transaction): Long {
            addCalls++
            addGate?.await()
            addFailure?.let { throw it }
            addedTransactions.add(tx)
            return 1
        }
        override suspend fun deleteTransaction(id: Long) {}
        override suspend fun clearAll() {}
    }

    private val fakePriceRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? {
            if (fundId == slowFundId) delay(1_000)
            return pricesByFundId[fundId] ?: latestPriceValue
        }
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = flowOf(emptyMap())
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long) = emptyList<FundPrice>()
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())
        override suspend fun refresh(fundId: String, since: LocalDate?) = true
        override suspend fun refreshSince(fundId: String, isin: String, since: java.time.LocalDate) = true
        override suspend fun historyForIsin(isin: String, from: LocalDate, to: LocalDate): List<FundPrice> = emptyList()
        override suspend fun suggestIsin(fundName: String): String? = null
        override suspend fun findFundByIsin(isin: String): Fund? = foundByIsin[isin]
        override suspend fun lookupIsin(fundId: String): String? = null
        override suspend fun fetchFundsForCompany(companyId: String): List<Fund>? = emptyList()
        override suspend fun fetchFundCatalog(): FundCatalog = FundCatalog(emptyList(), emptyList())
    }

    @get:Rule
    val tempFolder = TemporaryFolder()
    private lateinit var preferences: PreferencesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        preferences = testPreferencesRepository(dispatcher, tempFolder, "transaction_form_test")
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private val scheduler = FakeFundPriceRefreshScheduler()
    private val suggestionRepo = FakeSuggestionRecordRepository()
    private val switchWatchRepo = FakeSwitchWatchRepository()
    private val metadataRepo = FakeFundMetadataRepository()
    private val recordTransaction = RecordTransaction(
        fakeTransactionRepo,
        switchWatchRepo,
        suggestionRepo,
        metadataRepo,
        scheduler,
        testTargetLevelPreselection(fakeTransactionRepo, switchWatchRepo)
    )

    private fun viewModel(key: TransactionFormKey = TransactionFormKey()) = TransactionFormViewModel(
        key,
        fakeTransactionRepo,
        fakePriceRepo,
        recordTransaction,
        testSwitchChoicesSource(preferences, suggestionRepo, switchWatchRepo, metadataRepo = metadataRepo),
        FollowedSuggestionLookup(suggestionRepo, metadataRepo)
    )

    /** Formuläret är laddat: fonderna och bytesväljarens indata är lästa. */
    private fun TransactionFormUiState.loaded() = funds.isNotEmpty() && switchField != null

    @Test
    fun `formularet ar ogiltigt tills fond antal och kurs fyllts i`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (!state.loaded()) state = awaitItem()
            assertFalse(state.valid)

            vm.onFundSelected(fund)
            state = awaitItem()
            assertFalse(state.valid)

            vm.onSharesTextChange("2")
            state = awaitItem()
            vm.onPriceTextChange("150")
            state = awaitItem()
            assertTrue(state.valid)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onFundSelected forifyller kurs fran senaste kanda pris`() = runTest(dispatcher) {
        latestPriceValue = FundPrice(fundId = fund.fundId, epochDay = 100, nav = 175.5, currency = "SEK")
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (!state.loaded()) state = awaitItem()

            vm.onFundSelected(fund)
            state = awaitItem()
            while (state.priceText.isEmpty()) state = awaitItem()
            assertEquals("175.5", state.priceText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `byte till en fond utan kurs lamnar inte kvar foregaende fonds kurs`() = runTest(dispatcher) {
        // Fältet nollades tidigare aldrig: valde användaren först en fond med cachad kurs och
        // sedan en utan, stod den första fondens NAV kvar — formuläret var giltigt och
        // transaktionen kunde sparas på fel fonds kurs (tyst fel i GAV och realiserat resultat).
        val utanKurs = Fund(fundId = "SHB0000999", name = "Handelsbanken Global Index Criteria")
        funds.value = listOf(fund, utanKurs)
        pricesByFundId[fund.fundId] = FundPrice(fundId = fund.fundId, epochDay = 100, nav = 175.5, currency = "SEK")
        val vm = viewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (!state.loaded()) state = awaitItem()

            vm.onFundSelected(fund)
            state = awaitItem()
            while (state.priceText.isEmpty()) state = awaitItem()
            assertEquals("175.5", state.priceText)

            vm.onFundSelected(utanKurs)
            state = awaitItem()
            while (state.selectedFund?.fundId != utanKurs.fundId) state = awaitItem()

            assertEquals("", state.priceText)
            assertFalse(state.valid)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett langsamt kursuppslag skriver inte over den fond som valts under tiden`() = runTest(dispatcher) {
        // Uppslaget för den övergivna fonden avbryts vid bytet — annars kunde det landa efter
        // att användaren redan bytt fond och skriva över den nya fondens kurs.
        val annanFond = Fund(fundId = "SHB0000999", name = "Handelsbanken Global Index Criteria")
        funds.value = listOf(fund, annanFond)
        pricesByFundId[fund.fundId] = FundPrice(fundId = fund.fundId, epochDay = 100, nav = 175.5, currency = "SEK")
        pricesByFundId[annanFond.fundId] = FundPrice(fundId = annanFond.fundId, epochDay = 100, nav = 99.0, currency = "SEK")
        slowFundId = fund.fundId
        val vm = viewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (!state.loaded()) state = awaitItem()

            vm.onFundSelected(fund)
            vm.onFundSelected(annanFond)

            state = awaitItem()
            while (state.priceText.isEmpty()) state = awaitItem()
            assertEquals("99.0", state.priceText)

            // Även efter att det långsamma uppslaget skulle ha hunnit svara.
            advanceTimeBy(5_000)
            assertEquals("99.0", vm.uiState.value.priceText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `svenskt decimalkomma i antal och kurs gor formularet giltigt`() = runTest(dispatcher) {
        // Regression: KeyboardType.Decimal ger ofta bara komma på ett svenskt tangentbord —
        // formuläret ska inte tyst förbli ogiltigt när användaren skriver "2,5" i stället för "2.5".
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (!state.loaded()) state = awaitItem()

            vm.onFundSelected(fund)
            state = awaitItem()
            vm.onSharesTextChange("2,5")
            state = awaitItem()
            vm.onPriceTextChange("150,75")
            state = awaitItem()
            assertTrue(state.valid)

            vm.save()
            state = awaitItem()
            while (!state.saved) state = awaitItem()

            assertEquals(2.5, addedTransactions.first().shares, 1e-9)
            assertEquals(150.75, addedTransactions.first().pricePerShare, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save sparar transaktion och markerar formularet som sparat`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (!state.loaded()) state = awaitItem()

            vm.onFundSelected(fund)
            state = awaitItem()
            vm.onSharesTextChange("2")
            state = awaitItem()
            vm.onPriceTextChange("150")
            state = awaitItem()
            assertTrue(state.valid)

            vm.save()
            state = awaitItem()
            while (!state.saved) state = awaitItem()

            assertEquals(1, addedTransactions.size)
            assertEquals(fund.fundId, addedTransactions.first().fundId)
            assertEquals(2.0, addedTransactions.first().shares, 1e-9)
            assertEquals(150.0, addedTransactions.first().pricePerShare, 1e-9)
            assertEquals(0.0, addedTransactions.first().fee, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sparat kvitteras och kommer inte tillbaka`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (!state.loaded()) state = awaitItem()
            vm.onFundSelected(fund)
            vm.onSharesTextChange("2")
            vm.onPriceTextChange("150")
            while (!state.valid) state = awaitItem()

            vm.save()
            while (!state.saved) state = awaitItem()

            // Skärmen har gått tillbaka och kvitterar — händelsen är borta och kommer inte igen.
            vm.onSavedHandled()
            assertFalse(awaitItem().saved)
            expectNoEvents()
            assertEquals(1, addedTransactions.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tomt avgiftsfalt sparas som 0 avgift`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (!state.loaded()) state = awaitItem()

            vm.onFundSelected(fund)
            state = awaitItem()
            vm.onSharesTextChange("2")
            state = awaitItem()
            vm.onPriceTextChange("150")
            state = awaitItem()
            assertTrue(state.valid)

            vm.save()
            state = awaitItem()
            while (!state.saved) state = awaitItem()

            assertEquals(0.0, addedTransactions.first().fee, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ifylld avgift sparas med transaktionen`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (!state.loaded()) state = awaitItem()

            vm.onFundSelected(fund)
            state = awaitItem()
            vm.onSharesTextChange("2")
            state = awaitItem()
            vm.onPriceTextChange("150")
            state = awaitItem()
            vm.onFeeTextChange("25")
            state = awaitItem()
            assertTrue(state.valid)

            vm.save()
            state = awaitItem()
            while (!state.saved) state = awaitItem()

            assertEquals(25.0, addedTransactions.first().fee, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `negativ avgift gor formularet ogiltigt`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (!state.loaded()) state = awaitItem()

            vm.onFundSelected(fund)
            state = awaitItem()
            vm.onSharesTextChange("2")
            state = awaitItem()
            vm.onPriceTextChange("150")
            state = awaitItem()
            assertTrue(state.valid)

            vm.onFeeTextChange("-10")
            state = awaitItem()
            assertFalse(state.valid)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Bytesväljaren och det förifyllda läget (BYT-2, issue #144) ---

    private val today: LocalDate = LocalDate.now()
    private val held = Fund(fundId = "SHB_HELD", name = "Sverige Småbolag", isin = "SE_HELD")
    private val other = Fund(fundId = "SHB_OTHER", name = "Global Index", isin = "SE_OTHER")

    private fun meta(isin: String, name: String, risk: Int) = FundMetadata(
        isin = isin, name = name, orderbookId = isin, totalFee = 0.5, managementFee = 0.5,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList()
    )

    /** En inspelad plan med ett byte Sverige Småbolag → Kandidat, som Beslut visar det (HEM-8). */
    private suspend fun setUpPlan() {
        funds.value = listOf(held, other)
        metadataRepo.metadataByIsin = mapOf("SE_HELD" to meta("SE_HELD", "Sverige Småbolag", 5), "SE_CAND" to meta("SE_CAND", "Kandidat", 3))
        preferences.setAccountType(AccountType.ISK_KF)
        suggestionRepo.records.value = listOf(
            SuggestionRecord(
                id = 42,
                suggestedAtEpochDay = today.toEpochDay(),
                planIndex = 0,
                sellIsin = "SE_HELD",
                buyIsin = "SE_CAND",
                sellNavAtSuggestion = 300.0,
                buyNavAtSuggestion = 50.0,
                switchValueKr = 12_500.0
            )
        )
        pricesByFundId[held.fundId] = FundPrice(fundId = held.fundId, epochDay = today.toEpochDay(), nav = 325.0, currency = "SEK")
    }

    private suspend fun openWatch(candidates: List<String> = listOf("SE_CAND"), soldAt: LocalDate = today.minusDays(2), sellTransactionId: Long? = 9) = switchWatchRepo.start(
        SwitchWatch(
            sellIsin = "SE_HELD",
            sellFundName = "Sverige Småbolag",
            soldAtEpochDay = soldAt.toEpochDay(),
            proceedsKr = 18_400.0,
            targetLevel = 3,
            sellTransactionId = sellTransactionId,
            candidates = candidates.map { SwitchWatchCandidate(isin = it, name = it) }
        )
    )

    private suspend fun ReceiveTurbine<TransactionFormUiState>.awaitUntil(predicate: (TransactionFormUiState) -> Boolean): TransactionFormUiState {
        var state = awaitItem()
        while (!predicate(state)) state = awaitItem()
        return state
    }

    @Test
    fun `folj forifyller saljet med last fond och typ dagens datum senaste kurs och andelar ur beloppet`() = runTest(dispatcher) {
        setUpPlan()
        val vm = viewModel(TransactionFormKey(fundIsin = "SE_HELD", type = TransactionType.SALJ, suggestionRecordId = 42, prefillAmountKr = 12_500.0))

        vm.uiState.test {
            val state = awaitUntil { it.loaded() && it.sharesText.isNotEmpty() && it.followIntro != null }

            assertEquals(held, state.selectedFund)
            assertEquals(TransactionType.SALJ, state.type)
            assertTrue(state.prefilled && state.fundLocked && state.typeLocked)
            assertEquals(today, state.date)
            assertEquals("325.0", state.priceText)
            assertEquals("38,4615", state.sharesText)
            assertEquals(12_500.0, state.prefillAmountKr!!, 1e-9)
            assertEquals(FollowIntro(planIndex = 0, buyFundName = "Kandidat", amountKr = 12_500.0), state.followIntro)
            // Planraden är förvald — inget att svara på, Spara är tänd.
            assertEquals(SwitchOption.Decide(SwitchDecision.ForSuggestion(42, 3)), state.switchField?.option)
            assertTrue(state.canSave)

            // Fond och typ är låsta.
            vm.onTypeChange(TransactionType.KOP)
            vm.onFundSelected(other)
            advanceUntilIdle()
            assertEquals(TransactionType.SALJ, vm.uiState.value.type)
            assertEquals(held, vm.uiState.value.selectedFund)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `det sparade saljet fran folj startar bevakningen ur planraden med koppling till saljet`() = runTest(dispatcher) {
        setUpPlan()
        val vm = viewModel(
            TransactionFormKey.follow(
                se.partee71.fonder.domain.usecase.SwitchPlanResolver.Suggestion(
                    recordId = 42, planIndex = 0, sellIsin = "SE_HELD", buyIsin = "SE_CAND", sellFundName = "Sverige Småbolag",
                    buyFundName = "Kandidat", fromLevel = 5, toLevel = 3, feeDeltaPercent = 0.0, switchValueKr = 12_500.0
                )
            )
        )

        vm.uiState.test {
            awaitUntil { it.canSave }
            vm.save()
            awaitUntil { it.saved }
            cancelAndIgnoreRemainingEvents()
        }

        val watch = switchWatchRepo.watches.value.single()
        assertEquals(42L, watch.sourceRecordId)
        assertEquals(3, watch.targetLevel)
        assertEquals(1L, watch.sellTransactionId)
        assertEquals(TransactionType.SALJ, addedTransactions.single().type)
        assertEquals(1, scheduler.switchPlanScans)
    }

    @Test
    fun `kop av en annan fond medan ett byte vantar kraver svar innan Spara tands`() = runTest(dispatcher) {
        funds.value = listOf(held, other)
        val watchId = openWatch()
        val vm = viewModel()

        vm.uiState.test {
            awaitUntil { it.loaded() }
            vm.onFundSelected(other)
            vm.onSharesTextChange("10")
            vm.onPriceTextChange("100")
            var state = awaitUntil { it.valid && it.selectedFund == other }

            assertTrue(state.switchField!!.unanswered)
            assertFalse(state.canSave)
            assertTrue(state.blockedBySwitch)
            vm.save()
            advanceUntilIdle()
            assertTrue(addedTransactions.isEmpty())

            vm.onSwitchSelected(SwitchOption.Decide(SwitchDecision.ClosesWatch(watchId)))
            state = awaitUntil { it.canSave }
            vm.save()
            awaitUntil { it.saved }
            cancelAndIgnoreRemainingEvents()
        }

        val watch = switchWatchRepo.watches.value.single()
        assertFalse(watch.isOpen)
        assertEquals(1L, watch.buyTransactionId)
        assertEquals("SE_OTHER", watch.boughtIsin)
    }

    @Test
    fun `kop utan nagot byte att koppla till visar ingen valjare`() = runTest(dispatcher) {
        funds.value = listOf(other)
        val vm = viewModel()

        vm.uiState.test {
            awaitUntil { it.loaded() }
            vm.onFundSelected(other)
            val state = awaitUntil { it.selectedFund == other }
            assertTrue(state.switchField!!.hidden)
            assertFalse(state.switchField!!.unanswered)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `kopte den har slar upp kandidaten pa ISIN forvaljer bevakningen och lagger till fonden nar kopet sparas`() = runTest(dispatcher) {
        funds.value = listOf(held)
        val watchId = openWatch(candidates = listOf("SE_CAND"))
        val candidate = Fund(fundId = "SE_CAND", name = "Kandidat")
        foundByIsin["SE_CAND"] = candidate
        val vm = viewModel(TransactionFormKey.bought(watchId, "SE_CAND"))

        vm.uiState.test {
            var state = awaitUntil { it.loaded() && it.selectedFund != null }
            assertEquals("SE_CAND", state.selectedFund?.isin)
            assertTrue(state.fundLocked)
            assertEquals(TransactionType.KOP, state.type)
            assertEquals(SwitchOption.Decide(SwitchDecision.ClosesWatch(watchId)), state.switchField?.option)

            vm.onSharesTextChange("100")
            vm.onPriceTextChange("184")
            state = awaitUntil { it.canSave }
            vm.save()
            awaitUntil { it.saved }
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf(candidate.copy(isin = "SE_CAND")), upsertedFunds)
        val watch = switchWatchRepo.watches.value.single()
        assertFalse(watch.isOpen)
        assertEquals(1L, watch.buyTransactionId)
    }

    @Test
    fun `kopte den har for en fond som inte gar att sla upp lamnar fondvaljaren som reserv`() = runTest(dispatcher) {
        funds.value = listOf(held)
        val watchId = openWatch()
        val vm = viewModel(TransactionFormKey.bought(watchId, "SE_OKAND"))

        vm.uiState.test {
            val state = awaitUntil { it.loaded() && it.fundLookupFailed }
            assertFalse(state.fundLocked)
            assertEquals(null, state.selectedFund)
            assertTrue(state.typeLocked)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `salj av en fond med en okopplad bevakning fran en annan dag fragar och ja kopplar saljet`() = runTest(dispatcher) {
        funds.value = listOf(held)
        val watchId = openWatch(soldAt = today.minusDays(3), sellTransactionId = null)
        val vm = viewModel()

        vm.uiState.test {
            awaitUntil { it.loaded() }
            vm.onFundSelected(held)
            vm.onTypeChange(TransactionType.SALJ)
            vm.onSharesTextChange("10")
            vm.onPriceTextChange("300")
            val state = awaitUntil { it.valid && it.type == TransactionType.SALJ && it.switchField?.options?.size == 3 }
            assertTrue(state.blockedBySwitch)
            assertTrue(SwitchOption.LinkOtherDay(watchId) in state.switchField!!.options.map { it.option })

            vm.onSwitchSelected(SwitchOption.LinkOtherDay(watchId))
            awaitUntil { it.canSave }
            vm.save()
            awaitUntil { it.saved }
            cancelAndIgnoreRemainingEvents()
        }

        val watch = switchWatchRepo.watches.value.single()
        assertEquals(1L, watch.sellTransactionId)
        assertEquals(today.toEpochDay(), watch.soldAtEpochDay)
        assertEquals(3_000.0, watch.proceedsKr!!, 1e-9)
        assertEquals("en skrivning, en omräkning", 1, scheduler.switchPlanScans)
    }

    @Test
    fun `kunde bevakningen inte kopplas sags det och formularet stangs inte som om allt gatt bra`() = runTest(dispatcher) {
        // Bevakningen hann kopplas eller stängas av något annat: säljet är sparat, men utan
        // koppling — det ska synas, och Spara är släckt så säljet inte sparas en gång till.
        funds.value = listOf(held)
        val watchId = openWatch(soldAt = today.minusDays(3), sellTransactionId = null)
        switchWatchRepo.linkResult = false
        val vm = viewModel()

        vm.uiState.test {
            awaitUntil { it.loaded() }
            vm.onFundSelected(held)
            vm.onTypeChange(TransactionType.SALJ)
            vm.onSharesTextChange("10")
            vm.onPriceTextChange("300")
            awaitUntil { it.valid && it.switchField?.options?.size == 3 }
            vm.onSwitchSelected(SwitchOption.LinkOtherDay(watchId))
            awaitUntil { it.canSave }
            vm.save()
            val state = awaitUntil { it.linkFailed }
            assertFalse(state.saved)
            assertFalse(state.canSave)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, addedTransactions.size)
        assertEquals(null, switchWatchRepo.watches.value.single().sellTransactionId)
    }

    @Test
    fun `folj pa ett avgiftsalternativ som Besluts kalla inte visar slas upp pa radens id`() = runTest(dispatcher) {
        // Fonddetaljs avgiftsrader och Besluts billigare alternativ filtreras olika — Följ från
        // Fonddetalj får aldrig tappa förslaget tyst (BYT-2). Raden finns bara i inspelningen.
        funds.value = listOf(held)
        metadataRepo.metadataByIsin = mapOf("SE_BILLIG" to meta("SE_BILLIG", "Billig index", 5))
        suggestionRepo.records.value = listOf(
            SuggestionRecord(
                id = 77, suggestedAtEpochDay = today.minusDays(40).toEpochDay(), planIndex = 0, sellIsin = "SE_HELD", buyIsin = "SE_BILLIG",
                sellNavAtSuggestion = 300.0, buyNavAtSuggestion = 50.0, switchValueKr = 9_000.0, kind = SuggestionKind.FEE
            )
        )
        val vm = viewModel(TransactionFormKey.follow(fundId = held.fundId, recordId = 77))

        vm.uiState.test {
            val state = awaitUntil { it.loaded() && it.selectedFund != null && it.followIntro != null }
            assertEquals(SwitchOption.Decide(SwitchDecision.ForSuggestion(77, 5)), state.switchField?.option)
            assertEquals(FollowIntro(planIndex = null, buyFundName = "Billig index", amountKr = 9_000.0), state.followIntro)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fonden ar last fran start nar nyckeln bar en fond och ett anvandarval skriver inte over den`() = runTest(dispatcher) {
        // Förifyllningen väntar på ett uppslag — under tiden får fondväljaren inte gå att använda,
        // och uppslaget får aldrig skriva över ett val (granskning #144).
        setUpPlan()
        slowFundId = held.fundId
        val vm = viewModel(TransactionFormKey(fundIsin = "SE_HELD", type = TransactionType.SALJ, suggestionRecordId = 42, prefillAmountKr = 12_500.0))

        vm.uiState.test {
            var state = awaitUntil { it.loaded() }
            assertTrue(state.fundLocked)
            vm.onFundSelected(other)
            state = awaitUntil { it.selectedFund != null && it.priceText.isNotEmpty() }
            assertEquals(held, state.selectedFund)
            assertEquals("325.0", state.priceText)
            assertFalse(state.fundLoading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Fel vid sparning (NFR-6) ---

    /** Formuläret ifyllt med [fund], 2 andelar à 150 kr — klart att spara. */
    private suspend fun ReceiveTurbine<TransactionFormUiState>.fillValidForm(vm: TransactionFormViewModel) {
        awaitUntil { it.loaded() }
        vm.onFundSelected(fund)
        vm.onSharesTextChange("2")
        vm.onPriceTextChange("150")
        awaitUntil { it.canSave }
    }

    @Test
    fun `ett sparfel sags och formularet star kvar med inmatningen`() = runTest(dispatcher) {
        addFailure = IllegalStateException("trasig")
        addGate = CompletableDeferred()
        val vm = viewModel()
        vm.uiState.test {
            fillValidForm(vm)

            vm.save()
            advanceUntilIdle()
            var state = expectMostRecentItem()
            assertTrue(state.saving)
            assertFalse("Spara är släckt medan det sparar", state.canSave)
            vm.save()
            advanceUntilIdle()
            assertEquals("ett dubbeltryck sparar inte två gånger", 1, addCalls)

            addGate!!.complete(Unit)
            state = awaitUntil { it.failure != null }
            assertEquals(DataError.Unknown, state.failure?.cause)
            assertFalse(state.saving)
            assertFalse(state.saved)
            assertEquals(fund, state.selectedFund)
            assertEquals("2", state.sharesText)
            assertEquals("150", state.priceText)
            assertTrue("ett nytt försök går att göra", state.canSave)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(addedTransactions.isEmpty())
    }

    @Test
    fun `ett nytt forsok efter ett sparfel tar bort felet och sparar`() = runTest(dispatcher) {
        addFailure = IllegalStateException("trasig")
        val vm = viewModel()
        vm.uiState.test {
            fillValidForm(vm)
            vm.save()
            awaitUntil { it.failure != null }

            addFailure = null
            vm.save()
            val state = awaitUntil { it.saved }
            assertEquals(null, state.failure)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, addedTransactions.size)
    }

    @Test
    fun `ett avbrott under sparningen visas inte som fel`() = runTest(dispatcher) {
        addFailure = CancellationException("avbruten")
        val vm = viewModel()
        vm.uiState.test {
            fillValidForm(vm)

            vm.save()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(null, state.failure)
            assertFalse(state.saved)
            assertFalse(state.saving)
            assertEquals("2", state.sharesText)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(addedTransactions.isEmpty())
    }
}
