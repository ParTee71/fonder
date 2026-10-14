package se.partee71.fonder.ui.transaktioner

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.RecordTransaction
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import se.partee71.fonder.testing.testTargetLevelPreselection
import se.partee71.fonder.worker.FakeFundPriceRefreshScheduler
import java.time.LocalDate

/**
 * Händelser (TRX, SLD): listan, filtret, årets realiserade resultat och säljens resultat och
 * bevakning. Säljdelen flyttades hit från SoldFundsViewModelTest när Sålda fonder blev en del av
 * Händelser (0.62.0).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransaktionerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val fund = Fund(fundId = "SHB0000442", name = "Handelsbanken Sverige")
    private val funds = MutableStateFlow(listOf(fund))
    private val transactions = MutableStateFlow<List<Transaction>>(emptyList())
    private val deletedIds = mutableListOf<Long>()

    private val fakeRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = funds
        override fun observeTransactions(): Flow<List<Transaction>> = transactions
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = transactions
        override suspend fun upsertFund(fund: Fund) {}
        override suspend fun addTransaction(tx: Transaction): Long = 0
        override suspend fun deleteTransaction(id: Long) {
            deletedIds.add(id)
        }
        override suspend fun clearAll() {}
    }

    private val fakeSwitchWatchRepo = FakeSwitchWatchRepository()
    private val fakeMetadataRepo = FakeFundMetadataRepository()
    private val scheduler = FakeFundPriceRefreshScheduler()

    /** Fast "idag" så att årets resultat inte beror på när testet körs. */
    private val today = LocalDate.of(2026, 10, 11)

    private fun viewModel() = TransaktionerViewModel(
        fakeRepo,
        fakeSwitchWatchRepo,
        RecordTransaction(
            fakeRepo, fakeSwitchWatchRepo, FakeSuggestionRecordRepository(), fakeMetadataRepo, scheduler,
            testTargetLevelPreselection(fakeRepo, fakeSwitchWatchRepo),
        ),
    ) { today }

    private fun kop(id: Long, day: Long, shares: Double, price: Double, fundId: String = fund.fundId) =
        Transaction(id = id, fundId = fundId, type = TransactionType.KOP, epochDay = day, shares = shares, pricePerShare = price)

    private fun salj(id: Long, day: Long, shares: Double, price: Double, fundId: String = fund.fundId, fee: Double = 0.0) =
        Transaction(id = id, fundId = fundId, type = TransactionType.SALJ, epochDay = day, shares = shares, pricePerShare = price, fee = fee)

    /** Väntar ut laddningen inom ett Turbine-block. */
    private suspend fun app.cash.turbine.ReceiveTurbine<TransaktionerUiState>.awaitLoaded(): TransaktionerUiState {
        var state = awaitItem()
        while (state.loading) state = awaitItem()
        return state
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // --- Listan (TRX-1, TRX-2) ---

    @Test
    fun `rader far fondnamn uppslaget fran fundId`() = runTest(dispatcher) {
        transactions.value = listOf(kop(id = 1, day = 100, shares = 2.0, price = 150.0))
        viewModel().uiState.test {
            val state = awaitLoaded()
            assertEquals(1, state.rows.size)
            assertEquals(fund.name, state.rows.first().fundName)
            assertNull(state.rows.first().sale)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `okand fond faller tillbaka pa fundId som namn`() = runTest(dispatcher) {
        funds.value = emptyList()
        transactions.value = listOf(
            kop(id = 1, day = 100, shares = 5.0, price = 200.0, fundId = "SE0003653302"),
            salj(id = 2, day = 200, shares = 5.0, price = 210.0, fundId = "SE0003653302"),
        )
        viewModel().uiState.test {
            val state = awaitLoaded()
            assertTrue(state.rows.all { it.fundName == "SE0003653302" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleteTransaction anropar repository`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            awaitLoaded()
            cancelAndIgnoreRemainingEvents()
        }

        vm.deleteTransaction(42)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(42L in deletedIds)
    }

    @Test
    fun `tom transaktionshistorik ger tomt tillstand utan realiserat resultat`() = runTest(dispatcher) {
        viewModel().uiState.test {
            val state = awaitLoaded()
            assertTrue(state.isEmpty)
            assertEquals(0.0, state.totalRealized.realizedGain, 1e-9)
            assertNull(state.totalRealized.realizedGainFraction)
            assertFalse(state.showRealizedCard)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Filtret (TRX-3) ---

    @Test
    fun `filtret visar alla, bara kop eller bara salj i datumordning`() = runTest(dispatcher) {
        // DAO:ns ordning: senaste först.
        transactions.value = listOf(
            salj(id = 3, day = 300, shares = 5.0, price = 120.0),
            kop(id = 2, day = 200, shares = 5.0, price = 110.0),
            kop(id = 1, day = 100, shares = 10.0, price = 100.0),
        )
        val vm = viewModel()
        vm.uiState.test {
            assertEquals(listOf(3L, 2L, 1L), awaitLoaded().rows.map { it.transaction.id })

            vm.onFilterSelected(HandelseFilter.KOP)
            val kop = awaitItem()
            assertEquals(HandelseFilter.KOP, kop.filter)
            assertEquals(listOf(2L, 1L), kop.rows.map { it.transaction.id })
            assertFalse("Köp visar inget realiserat resultat", kop.showRealizedCard)

            vm.onFilterSelected(HandelseFilter.SALJ)
            val salj = awaitItem()
            assertEquals(listOf(3L), salj.rows.map { it.transaction.id })
            assertTrue(salj.showRealizedCard)
            // Filtret döljer bara rader – det är inte ett tomt tillstånd för hela fliken.
            assertFalse(salj.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Årets och totalens realiserade resultat (TRX-4, SLD-3) ---

    @Test
    fun `arets realiserade resultat tar bara med salj gjorda i ar`() = runTest(dispatcher) {
        val lastYear = LocalDate.of(2025, 12, 31).toEpochDay()
        val thisYear = LocalDate.of(2026, 1, 2).toEpochDay()
        transactions.value = listOf(
            salj(id = 3, day = thisYear, shares = 10.0, price = 130.0),
            salj(id = 2, day = lastYear, shares = 10.0, price = 110.0),
            kop(id = 1, day = lastYear - 100, shares = 20.0, price = 100.0),
        )
        viewModel().uiState.test {
            val state = awaitLoaded()
            assertEquals(300.0, state.yearRealized.realizedGain, 1e-9)
            assertEquals(0.3, state.yearRealized.realizedGainFraction!!, 1e-9)
            assertEquals(400.0, state.totalRealized.realizedGain, 1e-9)
            assertTrue(state.showRealizedCard)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `totalen summerar over flera salj, aven i olika fonder (SLD-3)`() = runTest(dispatcher) {
        val fondB = Fund(fundId = "SHB0000627", name = "Fond B")
        funds.value = listOf(fund, fondB)
        transactions.value = listOf(
            kop(id = 1, day = 100, shares = 10.0, price = 100.0),
            salj(id = 2, day = 200, shares = 10.0, price = 150.0),
            kop(id = 3, day = 100, shares = 5.0, price = 200.0, fundId = fondB.fundId),
            salj(id = 4, day = 200, shares = 5.0, price = 190.0, fundId = fondB.fundId),
        )
        viewModel().uiState.test {
            val state = awaitLoaded()
            // Fond A: 1500 - 1000 = 500. Fond B: 950 - 1000 = -50. Summa: 450 på 2000.
            assertEquals(450.0, state.totalRealized.realizedGain, 1e-9)
            assertEquals(450.0 / 2000.0, state.totalRealized.realizedGainFraction!!, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `totalens andel ar null och totalen osaker nar summerat anskaffningsvarde ar 0`() = runTest(dispatcher) {
        // En sälj helt utan matchande köp — costBasis blir 0 (osäkert resultat, SLD-2).
        transactions.value = listOf(salj(id = 1, day = 200, shares = 5.0, price = 150.0))
        viewModel().uiState.test {
            val state = awaitLoaded()
            assertNull(state.totalRealized.realizedGainFraction)
            assertTrue(state.totalRealized.uncertain)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Säljraden (SLD-1, SLD-2) ---

    @Test
    fun `saljrad bar realiserad vinst ur RealizedGainCalculator`() = runTest(dispatcher) {
        transactions.value = listOf(
            salj(id = 2, day = 200, shares = 10.0, price = 150.0),
            kop(id = 1, day = 100, shares = 10.0, price = 100.0),
        )
        viewModel().uiState.test {
            val sale = awaitLoaded().rows.first { it.transaction.id == 2L }.sale!!
            assertEquals(500.0, sale.realizedGain, 1e-9)
            assertEquals(0.0, sale.uncoveredShares, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saljrad med forlust far negativt resultat och avgiften avdragen`() = runTest(dispatcher) {
        transactions.value = listOf(
            salj(id = 2, day = 200, shares = 10.0, price = 90.0, fee = 25.0),
            kop(id = 1, day = 100, shares = 10.0, price = 100.0),
        )
        viewModel().uiState.test {
            val sale = awaitLoaded().rows.first { it.transaction.id == 2L }.sale!!
            // 900 − 25 − 1000.
            assertEquals(-125.0, sale.realizedGain, 1e-9)
            assertEquals(25.0, sale.fee, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saljrad utan tackande kop markeras osaker (SLD-2)`() = runTest(dispatcher) {
        transactions.value = listOf(
            salj(id = 2, day = 200, shares = 10.0, price = 150.0),
            kop(id = 1, day = 100, shares = 4.0, price = 100.0),
        )
        viewModel().uiState.test {
            val sale = awaitLoaded().rows.first { it.transaction.id == 2L }.sale!!
            assertEquals(6.0, sale.uncoveredShares, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Bevaka alternativ (SLD-5) ---

    /** En fond med känt ISIN, köpt (id 1, dag 100) och såld (id 2, dag 200, 10 andelar à 150). */
    private fun givenSoldFundWithIsin(fee: Double = 0.0) {
        funds.value = listOf(Fund(fundId = "SHB0000442", name = "Med ISIN", isin = "SE_MED"))
        transactions.value = listOf(
            salj(id = 2, day = 200, shares = 10.0, price = 150.0, fee = fee),
            kop(id = 1, day = 100, shares = 10.0, price = 100.0),
        )
    }

    @Test
    fun `ett salj med kant ISIN kan starta en bevakning, ett utan kan inte, ett kop aldrig (SLD-5)`() = runTest(dispatcher) {
        val medIsin = Fund(fundId = "SHB0000442", name = "Med ISIN", isin = "SE_MED")
        val utanIsin = Fund(fundId = "SHB0000627", name = "Utan ISIN", isin = null)
        funds.value = listOf(medIsin, utanIsin)
        transactions.value = listOf(
            kop(id = 1, day = 100, shares = 10.0, price = 100.0, fundId = medIsin.fundId),
            salj(id = 2, day = 200, shares = 10.0, price = 150.0, fundId = medIsin.fundId),
            kop(id = 3, day = 100, shares = 5.0, price = 100.0, fundId = utanIsin.fundId),
            salj(id = 4, day = 200, shares = 5.0, price = 120.0, fundId = utanIsin.fundId),
        )
        viewModel().uiState.test {
            val rows = awaitLoaded().rows
            // Kurserna hämtas på ISIN — utan det går fonden varken att jämföra eller följa.
            assertTrue(rows.first { it.transaction.id == 2L }.canStartSwitchWatch)
            assertFalse(rows.first { it.transaction.id == 4L }.canStartSwitchWatch)
            assertFalse(rows.first { it.transaction.id == 1L }.canStartSwitchWatch)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startSwitchWatch anvander saljets datum och likvid, inte dagens`() = runTest(dispatcher) {
        givenSoldFundWithIsin(fee = 25.0)
        fakeMetadataRepo.metadataByIsin = mapOf("SE_MED" to metadata("SE_MED", risk = 6))
        val vm = viewModel()

        vm.uiState.test {
            val state = awaitLoaded()
            vm.startSwitchWatch(state.rows.first { it.sale != null })
            advanceUntilIdle()
            assertEquals(fakeSwitchWatchRepo.watches.value.single().id, expectMostRecentItem().startedSwitchWatchId)
            vm.onSwitchWatchOpened()
            advanceUntilIdle()
            assertNull(expectMostRecentItem().startedSwitchWatchId)
            cancelAndIgnoreRemainingEvents()
        }

        val watch = fakeSwitchWatchRepo.watches.value.single()
        assertEquals("SE_MED", watch.sellIsin)
        assertEquals("Med ISIN", watch.sellFundName)
        // Utvecklingen ska mätas från dagen pengarna frigjordes, inte från idag.
        assertEquals(200L, watch.soldAtEpochDay)
        assertEquals(1_475.0, watch.proceedsKr!!, 1e-9)
        // Målnivån är säljfondens egen risknivå ur cachen — det närmaste "något likvärdigt".
        assertEquals(6, watch.targetLevel)
        assertNull(watch.sourceRecordId)
        // Bevakningen hör till just den här säljtransaktionen (BYT-1).
        assertEquals(2L, watch.sellTransactionId)
    }

    @Test
    fun `tva tryck pa bevaka alternativ ger en bevakning for saljet`() = runTest(dispatcher) {
        givenSoldFundWithIsin()
        val vm = viewModel()

        vm.uiState.test {
            val row = awaitLoaded().rows.first { it.sale != null }
            vm.startSwitchWatch(row)
            advanceUntilIdle()
            vm.startSwitchWatch(row)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, fakeSwitchWatchRepo.watches.value.size)
    }

    @Test
    fun `ett salj som redan bevakas erbjuder ingen ny bevakning`() = runTest(dispatcher) {
        // Gaten går på transaktionen (BYT-1): sälj 2 är bevakat, sälj 3 samma fond och dag är det inte.
        givenSoldFundWithIsin()
        transactions.value = listOf(salj(id = 3, day = 200, shares = 2.0, price = 150.0)) + transactions.value
        fakeSwitchWatchRepo.start(SwitchWatch(sellIsin = "SE_MED", sellFundName = "Med ISIN", soldAtEpochDay = 200, sellTransactionId = 2))
        viewModel().uiState.test {
            val rows = awaitLoaded().rows
            assertFalse(rows.first { it.transaction.id == 2L }.canStartSwitchWatch)
            assertTrue(rows.first { it.transaction.id == 3L }.canStartSwitchWatch)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en utgangen okopplad bevakning samma dag blockerar som forut`() = runTest(dispatcher) {
        // Bakgrundskörningen har inte hunnit stänga den: den kan inte kopplas, men är säljets —
        // en knapp som startade en ny bredvid hade gett två bevakningar för samma sälj (fond + dag).
        val soldAt = today.minusDays(SwitchWatchCalc.WATCH_TTL_DAYS + 2).toEpochDay()
        funds.value = listOf(Fund(fundId = "SHB0000442", name = "Med ISIN", isin = "SE_MED"))
        transactions.value = listOf(
            salj(id = 2, day = soldAt, shares = 10.0, price = 150.0),
            kop(id = 1, day = today.minusDays(100).toEpochDay(), shares = 10.0, price = 100.0),
        )
        fakeSwitchWatchRepo.start(SwitchWatch(sellIsin = "SE_MED", sellFundName = "Med ISIN", soldAtEpochDay = soldAt))

        viewModel().uiState.test {
            assertFalse(awaitLoaded().rows.first { it.sale != null }.canStartSwitchWatch)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en bevakning utan kand transaktion blockerar inte - knappen kopplar den till saljet`() = runTest(dispatcher) {
        // Startad ur ett förslag på Beslut (eller från före 0.67.0): samma fond och **samma**
        // säljdag är samma byte, så knappen kopplar den i stället för att starta en andra — dess
        // likvid blir säljets, dagen står kvar. En bevakning från en annan dag kopplas inte (#144).
        val soldAt = today.minusDays(2).toEpochDay()
        funds.value = listOf(Fund(fundId = "SHB0000442", name = "Med ISIN", isin = "SE_MED"))
        transactions.value = listOf(
            salj(id = 2, day = soldAt, shares = 10.0, price = 150.0, fee = 25.0),
            kop(id = 1, day = today.minusDays(100).toEpochDay(), shares = 10.0, price = 100.0),
        )
        val existing = fakeSwitchWatchRepo.start(SwitchWatch(sellIsin = "SE_MED", sellFundName = "Med ISIN", soldAtEpochDay = soldAt, proceedsKr = 4_200.0))
        val vm = viewModel()

        vm.uiState.test {
            val row = awaitLoaded().rows.first { it.sale != null }
            assertTrue(row.canStartSwitchWatch)
            vm.startSwitchWatch(row)
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(existing, state.startedSwitchWatchId)
            assertFalse(state.rows.first { it.sale != null }.canStartSwitchWatch)
            cancelAndIgnoreRemainingEvents()
        }

        val watch = fakeSwitchWatchRepo.watches.value.single()
        assertEquals(2L, watch.sellTransactionId)
        assertEquals(1_475.0, watch.proceedsKr!!, 1e-9)
        assertEquals(soldAt, watch.soldAtEpochDay)
    }

    private fun metadata(isin: String, risk: Int?) = FundMetadata(
        isin = isin,
        name = "Fond $isin",
        orderbookId = isin,
        totalFee = 0.4,
        managementFee = 0.4,
        category = null,
        fundType = null,
        companyName = null,
        risk = risk,
        indexFund = false,
        startDateEpochDay = null,
        minimumBuy = null,
        tags = emptyList(),
    )
}
