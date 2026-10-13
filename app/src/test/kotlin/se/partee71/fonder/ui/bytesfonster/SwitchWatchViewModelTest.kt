package se.partee71.fonder.ui.bytesfonster

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCandidateSource
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.ui.navigation.TransactionFormKey
import java.time.LocalDate

/**
 * Skärmen Pågående byte (ANA-12/ANA-13, issue #114) — automatiska förslag, ankring av
 * nollpunkten, utvecklingen sedan säljdagen och kvitteringen av köpet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchWatchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val today: LocalDate = LocalDate.now()
    private val soldAt = today.minusDays(3)

    private val funds = MutableStateFlow(
        listOf(Fund(fundId = "SHB1", name = "Såld fond", isin = "SE_SALJ")),
    )

    private val fakeTransactionRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = funds
        override fun observeTransactions(): Flow<List<Transaction>> = flowOf(emptyList())
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun upsertFund(fund: Fund) {}
        override suspend fun addTransaction(tx: Transaction): Long = 0
        override suspend fun deleteTransaction(id: Long) {}
        override suspend fun clearAll() {}
    }

    /** Kurshistorik per ISIN för [FundPriceRepository.historyForIsin] — kandidaterna appen aldrig ägt. */
    private var historyByIsin: Map<String, List<Pair<Long, Double>>> = emptyMap()
    private var suggestedIsin: String? = null
    private val historyCalls = mutableListOf<String>()

    private val fakePriceRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? = null
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = flowOf(emptyMap())
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice> =
            historyByIsin[fundId].orEmpty().map { (day, nav) -> FundPrice(fundId, day, nav, "SEK") }
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())
        override suspend fun refresh(fundId: String, since: LocalDate?) = true
        override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate) = true
        override suspend fun historyForIsin(isin: String, from: LocalDate, to: LocalDate): List<FundPrice> {
            historyCalls += isin
            return historyByIsin[isin].orEmpty()
                .filter { it.first in from.toEpochDay()..to.toEpochDay() }
                .map { (day, nav) -> FundPrice(isin, day, nav, "SEK") }
        }
        override suspend fun suggestIsin(fundName: String): String? = suggestedIsin
        override suspend fun findFundByIsin(isin: String): Fund? = null
        override suspend fun lookupIsin(fundId: String): String? = null
        override suspend fun fetchFundsForCompany(companyId: String): List<Fund>? = emptyList()
        override suspend fun fetchFundCatalog(): FundCatalog = FundCatalog(emptyList(), emptyList())
    }

    private val switchWatchRepo = FakeSwitchWatchRepository()
    private val metadataRepo = FakeFundMetadataRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun metadata(isin: String, risk: Int? = 4, fee: Double? = 0.4) = FundMetadata(
        isin = isin,
        name = "Fond $isin",
        orderbookId = isin,
        totalFee = fee,
        managementFee = fee,
        category = null,
        fundType = null,
        companyName = null,
        risk = risk,
        indexFund = false,
        startDateEpochDay = null,
        minimumBuy = null,
        tags = emptyList(),
    )

    private suspend fun startWatch(
        targetLevel: Int? = 5,
        candidates: List<SwitchWatchCandidate> = emptyList(),
        sourceRecordId: Long? = null,
        proceedsKr: Double? = 10_000.0,
    ): Long = switchWatchRepo.start(
        SwitchWatch(
            sellIsin = "SE_SALJ",
            sellFundName = "Såld fond",
            soldAtEpochDay = soldAt.toEpochDay(),
            proceedsKr = proceedsKr,
            targetLevel = targetLevel,
            sourceRecordId = sourceRecordId,
            candidates = candidates,
        ),
    )


    private fun viewModel(watchId: Long) = SwitchWatchViewModel(
        watchId,
        switchWatchRepo,
        fakePriceRepo,
        metadataRepo,
        fakeTransactionRepo,
    )

    @Test
    fun `en tom bevakning fylls med appens forslag pa malnivan, utan agda fonder`() = runTest(dispatcher) {
        metadataRepo.switchCandidates = listOf("SE_A", "SE_B", "SE_C", "SE_D").map { isin ->
            SwitchPlanCalc.Candidate(metadata(isin), twelveMonthReturn = 0.1)
        }
        val watchId = startWatch(targetLevel = 5)

        val vm = viewModel(watchId)
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        // Taket för automatiska förslag: resten av utrymmet upp till MAX_CANDIDATES är
        // användarens eget (ANA-13).
        val stored = switchWatchRepo.watches.value.single()
        assertEquals(SwitchWatchCalc.AUTO_CANDIDATES, stored.candidates.size)
        assertTrue(stored.candidates.all { it.source == SwitchWatchCandidateSource.AUTO })
        // Säljfonden och redan ägda fonder ska aldrig föreslås som köpkandidat.
        assertEquals(listOf(5 to setOf("SE_SALJ")), metadataRepo.switchCandidateCalls)
    }

    @Test
    fun `utan kand malniva hamtas inga forslag alls`() = runTest(dispatcher) {
        // En gissad nivå hade gett alternativ på fel risk — sämre än inga alternativ (ANA-4).
        metadataRepo.switchCandidates = listOf(SwitchPlanCalc.Candidate(metadata("SE_A"), 0.1))
        val watchId = startWatch(targetLevel = null)

        val vm = viewModel(watchId)
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(metadataRepo.switchCandidateCalls.isEmpty())
        assertTrue(switchWatchRepo.watches.value.single().candidates.isEmpty())
    }

    // --- Målnivå i efterhand och tomlägets orsak (issue #149) ---

    private suspend fun TestScope.settledState(vm: SwitchWatchViewModel): SwitchWatchUiState {
        lateinit var state: SwitchWatchUiState
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            state = expectMostRecentItem()
            cancelAndIgnoreRemainingEvents()
        }
        return state
    }

    @Test
    fun `utan malniva slas saljfondens risk upp, sparas och forslagen fylls`() = runTest(dispatcher) {
        metadataRepo.metadataByIsin = mapOf("SE_SALJ" to metadata("SE_SALJ", risk = 3))
        metadataRepo.switchCandidates = listOf(SwitchPlanCalc.Candidate(metadata("SE_A"), 0.1))
        val watchId = startWatch(targetLevel = null)

        val state = settledState(viewModel(watchId))

        val stored = switchWatchRepo.watches.value.single()
        assertEquals(3, stored.targetLevel)
        assertEquals(listOf("SE_A"), stored.candidates.map { it.isin })
        assertEquals(listOf(3 to setOf("SE_SALJ")), metadataRepo.switchCandidateCalls)
        assertNull(state.emptyReason)
    }

    @Test
    fun `en niva som hann sparas av annan vag galler fore den uppslagna`() = runTest(dispatcher) {
        metadataRepo.metadataByIsin = mapOf("SE_SALJ" to metadata("SE_SALJ", risk = 3))
        val watchId = startWatch(targetLevel = null)
        switchWatchRepo.beforeFillMissingTargetLevel = { id ->
            switchWatchRepo.watches.value = switchWatchRepo.watches.value.map {
                if (it.id == id) it.copy(targetLevel = 4) else it
            }
        }

        val state = settledState(viewModel(watchId))

        assertEquals(4, switchWatchRepo.watches.value.single().targetLevel)
        assertEquals(listOf(4 to setOf("SE_SALJ")), metadataRepo.switchCandidateCalls)
        assertEquals(4, state.targetLevel)
    }

    @Test
    fun `utan malniva aven i kallan sags orsaken och inget sparas`() = runTest(dispatcher) {
        metadataRepo.metadataByIsin = mapOf("SE_SALJ" to metadata("SE_SALJ", risk = null))
        val watchId = startWatch(targetLevel = null)

        val state = settledState(viewModel(watchId))

        assertEquals(SwitchWatchEmptyReason.NO_TARGET_LEVEL, state.emptyReason)
        assertNull(switchWatchRepo.watches.value.single().targetLevel)
        assertTrue(metadataRepo.switchCandidateCalls.isEmpty())
    }

    @Test
    fun `inga kopbara pa nivan sags som egen orsak`() = runTest(dispatcher) {
        val watchId = startWatch(targetLevel = 5)

        val state = settledState(viewModel(watchId))

        assertEquals(SwitchWatchEmptyReason.NONE_AVAILABLE, state.emptyReason)
        assertEquals(5, state.targetLevel)
    }

    @Test
    fun `misslyckat uppslag sags och forsok igen kor om autofyllningen`() = runTest(dispatcher) {
        metadataRepo.switchLookupFailed = true
        val watchId = startWatch(targetLevel = 5)
        val vm = viewModel(watchId)

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            assertEquals(SwitchWatchEmptyReason.LOOKUP_FAILED, expectMostRecentItem().emptyReason)

            metadataRepo.switchLookupFailed = false
            metadataRepo.switchCandidates = listOf(SwitchPlanCalc.Candidate(metadata("SE_A"), 0.1))
            vm.onRetryAutoFill()
            advanceUntilIdle()

            val state = expectMostRecentItem()
            assertNull(state.emptyReason)
            assertEquals(listOf("SE_A"), state.rows.map { it.isin })
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, metadataRepo.switchCandidateCalls.size)
    }

    @Test
    fun `dubbeltryck pa forsok igen kor autofyllningen en gang`() = runTest(dispatcher) {
        metadataRepo.switchLookupFailed = true
        val watchId = startWatch(targetLevel = 5)
        val vm = viewModel(watchId)

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            metadataRepo.switchLookupFailed = false
            metadataRepo.switchCandidates = listOf(SwitchPlanCalc.Candidate(metadata("SE_A"), 0.1))
            metadataRepo.switchSearchDelayMs = 1_000
            vm.onRetryAutoFill()
            runCurrent() // första försöket väntar nu i sökningen
            vm.onRetryAutoFill()
            advanceUntilIdle()

            assertEquals(listOf("SE_A"), expectMostRecentItem().rows.map { it.isin })
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, metadataRepo.switchCandidateCalls.size)
    }

    @Test
    fun `en handplockad kandidat efter misslyckat uppslag tar bort orsaken`() = runTest(dispatcher) {
        metadataRepo.switchLookupFailed = true
        val watchId = startWatch(targetLevel = 5)
        val vm = viewModel(watchId)

        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            vm.onAddCandidate(Fund(fundId = "X", name = "Egen fond", isin = "SE_EGEN"))
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(1, state.rows.size)
            assertNull(state.emptyReason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `nollpunkten ankras pa saljdagen och utvecklingen mats darifran`() = runTest(dispatcher) {
        historyByIsin = mapOf(
            "SE_A" to listOf(
                soldAt.minusDays(1).toEpochDay() to 90.0,
                soldAt.toEpochDay() to 100.0,
                today.toEpochDay() to 105.0,
            ),
        )
        metadataRepo.metadataByIsin = mapOf("SE_A" to metadata("SE_A", risk = 5, fee = 0.35))
        val watchId = startWatch(
            targetLevel = null,
            candidates = listOf(SwitchWatchCandidate(isin = "SE_A", name = "Fond A", source = SwitchWatchCandidateSource.MANUELL)),
        )

        val vm = viewModel(watchId)
        var row: CandidateRow? = null
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            row = expectMostRecentItem().rows.single()
            cancelAndIgnoreRemainingEvents()
        }

        // 100 → 105 = +5 %, på 10 000 kr = 500 kr. Kursen dagen *före* säljet får inte användas.
        assertEquals(0.05, row!!.changeFraction!!, 1e-9)
        assertEquals(500.0, row!!.changeKr!!, 1e-9)
        assertFalse(row!!.partial)
        assertEquals(5, row!!.riskLevel)
        assertEquals(0.35, row!!.feePercent!!, 1e-9)
        assertEquals(100.0, switchWatchRepo.watches.value.single().candidates.single().navAtStart!!, 1e-9)
    }

    @Test
    fun `en kandidat utan hamtbar historik visas som ej utvarderad, inte som noll`() = runTest(dispatcher) {
        historyByIsin = emptyMap()
        val watchId = startWatch(
            targetLevel = null,
            candidates = listOf(SwitchWatchCandidate(isin = "SE_A", name = "Fond A")),
        )

        val vm = viewModel(watchId)
        var state: SwitchWatchUiState? = null
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            state = expectMostRecentItem()
            cancelAndIgnoreRemainingEvents()
        }

        val row = state!!.rows.single()
        assertTrue(row.historyUnavailable)
        assertNull(row.changeFraction)
        // Raden försvinner aldrig — annars hade kandidaten sett ut som om den aldrig lagts till.
        assertEquals("SE_A", row.isin)
        assertTrue(state!!.candidateSeries.isEmpty())
    }

    @Test
    fun `kopte den har stanger ingenting sjalv - kopet registreras i formularet`() = runTest(dispatcher) {
        // ANA-12/BYT-2: knappen öppnar köpformuläret för kandidaten (TransactionFormKey.bought) och
        // det sparade köpet stänger bevakningen med köptransaktionen — se TransactionFormViewModelTest
        // och RecordTransactionTest. Skärmen själv kvitterar ingenting utan transaktion.
        val watchId = startWatch(
            targetLevel = null,
            candidates = listOf(SwitchWatchCandidate(isin = "SE_A", name = "Fond A")),
            sourceRecordId = 7,
        )

        val vm = viewModel(watchId)
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(
            TransactionFormKey(fundIsin = "SE_A", type = TransactionType.KOP, watchId = watchId),
            TransactionFormKey.bought(watchId, "SE_A"),
        )
        assertTrue(switchWatchRepo.watches.value.single().isOpen)
    }

    @Test
    fun `en fond utan ISIN gar inte att bevaka och sags ut`() = runTest(dispatcher) {
        suggestedIsin = null
        val watchId = startWatch(targetLevel = null)

        val vm = viewModel(watchId)
        var state: SwitchWatchUiState? = null
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            vm.onAddCandidate(Fund(fundId = "SHB9", name = "Fond utan ISIN", isin = null))
            advanceUntilIdle()
            state = expectMostRecentItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(SwitchWatchMessage.IsinUnavailable, state!!.message)
        assertTrue(switchWatchRepo.watches.value.single().candidates.isEmpty())
    }

    @Test
    fun `taket pa antal bevakade alternativ hindrar ett sjatte`() = runTest(dispatcher) {
        val watchId = startWatch(
            targetLevel = null,
            candidates = (1..SwitchWatchCalc.MAX_CANDIDATES).map {
                SwitchWatchCandidate(isin = "SE_$it", name = "Fond $it")
            },
        )

        val vm = viewModel(watchId)
        var state: SwitchWatchUiState? = null
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            vm.onAddCandidate(Fund(fundId = "SE_NY", name = "Fond ny", isin = "SE_NY"))
            advanceUntilIdle()
            state = expectMostRecentItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(SwitchWatchMessage.CandidateLimitReached, state!!.message)
        assertEquals(SwitchWatchCalc.MAX_CANDIDATES, switchWatchRepo.watches.value.single().candidates.size)
        assertFalse(state!!.canAddCandidate)
    }

    @Test
    fun `varje kandidats historik hamtas en gang, inte per emission`() = runTest(dispatcher) {
        historyByIsin = mapOf("SE_A" to listOf(soldAt.toEpochDay() to 100.0, today.toEpochDay() to 101.0))
        val watchId = startWatch(
            targetLevel = null,
            candidates = listOf(SwitchWatchCandidate(isin = "SE_A", name = "Fond A")),
        )

        val vm = viewModel(watchId)
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            // Ankringen skriver till bevakningen, vilket ger en ny emission — hämtningen får
            // inte gå om på den, annars kostar varje skrivning ett nätverksanrop till (TP-14).
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf("SE_A"), historyCalls)
    }

    @Test
    fun `en bevakning som inte finns visas som saknad i stallet for tom`() = runTest(dispatcher) {
        val vm = viewModel(watchId = 404)
        var state: SwitchWatchUiState? = null
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            state = expectMostRecentItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(state!!.missing)
        assertFalse(state!!.loading)
    }
}
