package se.partee71.fonder.ui.bytesfonster

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Test
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCandidateSource
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
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

    private class FakeSuggestionRecordRepository : SuggestionRecordRepository {
        val records = MutableStateFlow<List<SuggestionRecord>>(emptyList())
        override fun observeLatestBatch(): Flow<List<SuggestionRecord>> = records
        override fun observeHistory(): Flow<List<SuggestionRecord>> = records
        override suspend fun hasRecordedToday(sellIsin: String, buyIsin: String, epochDay: Long, kind: SuggestionKind) = false
        override suspend fun record(record: SuggestionRecord) { records.value = records.value + record }
        override suspend fun setFollowed(id: Long, followed: Boolean) {
            records.value = records.value.map { if (it.id == id) it.copy(followed = followed) else it }
        }
        override suspend fun prune(today: LocalDate) {}
    }

    private val switchWatchRepo = FakeSwitchWatchRepository()
    private val metadataRepo = FakeFundMetadataRepository()
    private val suggestionRepo = FakeSuggestionRecordRepository()

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
        SavedStateHandle(mapOf(SwitchWatchViewModel.ARG_WATCH_ID to watchId.toString())),
        switchWatchRepo,
        fakePriceRepo,
        metadataRepo,
        fakeTransactionRepo,
        suggestionRepo,
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
    fun `kopte den har stanger bevakningen och kvitterar det foljda forslaget`() = runTest(dispatcher) {
        suggestionRepo.records.value = listOf(
            SuggestionRecord(
                id = 7, suggestedAtEpochDay = soldAt.toEpochDay(), planIndex = 0,
                sellIsin = "SE_SALJ", buyIsin = "SE_A",
                sellNavAtSuggestion = 200.0, buyNavAtSuggestion = 100.0,
            ),
        )
        val watchId = startWatch(
            targetLevel = null,
            candidates = listOf(SwitchWatchCandidate(isin = "SE_A", name = "Fond A")),
            sourceRecordId = 7,
        )

        val vm = viewModel(watchId)
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            vm.onBought("SE_A")
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        val stored = switchWatchRepo.watches.value.single()
        assertFalse(stored.isOpen)
        assertEquals("SE_A", stored.boughtIsin)
        assertEquals(SwitchWatchCloseReason.KOPT, stored.closeReason)
        assertEquals(true, suggestionRepo.records.value.single().followed)
    }

    @Test
    fun `ett kop av en annan fond an den foreslagna kvitterar inte forslaget`() = runTest(dispatcher) {
        // Facit mäter *följda råd* (SET-5) — ett byte till något annat är inte rådet som följdes.
        suggestionRepo.records.value = listOf(
            SuggestionRecord(
                id = 7, suggestedAtEpochDay = soldAt.toEpochDay(), planIndex = 0,
                sellIsin = "SE_SALJ", buyIsin = "SE_A",
                sellNavAtSuggestion = 200.0, buyNavAtSuggestion = 100.0,
            ),
        )
        val watchId = startWatch(
            targetLevel = null,
            candidates = listOf(
                SwitchWatchCandidate(isin = "SE_A", name = "Fond A"),
                SwitchWatchCandidate(isin = "SE_B", name = "Fond B"),
            ),
            sourceRecordId = 7,
        )

        val vm = viewModel(watchId)
        vm.uiState.test {
            awaitItem()
            advanceUntilIdle()
            vm.onBought("SE_B")
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("SE_B", switchWatchRepo.watches.value.single().boughtIsin)
        assertNull(suggestionRepo.records.value.single().followed)
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
