package se.partee71.fonder.ui.transaktioner

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.data.repository.FakeTransactionRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.RecordTransaction
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import se.partee71.fonder.testing.FailingUntilRetry
import se.partee71.fonder.testing.awaitRecoveryAfterRetry
import se.partee71.fonder.testing.testTargetLevelPreselection
import se.partee71.fonder.worker.FakeFundPriceRefreshScheduler

/**
 * Händelser (TRX, SLD): listan, filtret, årets realiserade resultat och säljens resultat och
 * bevakning. Säljdelen flyttades hit från SoldFundsViewModelTest när Sålda fonder blev en del av
 * Händelser (0.62.0).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransaktionerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Kontotypen (SET-4) styr årskortets skatt (TRX-8) — en riktig DataStore på testets dispatcher, som i HemViewModelTest. */
    private lateinit var preferencesRepository: PreferencesRepository

    /** Kurscachen för schablonintäkten den 1 januari (TRX-8). */
    private val fakePriceRepo = FakeCachedPriceRepository()

    private val fund = Fund(fundId = "SHB0000442", name = "Handelsbanken Sverige")
    private val funds = MutableStateFlow(listOf(fund))
    private val transactions = MutableStateFlow<List<Transaction>>(emptyList())
    private val deletedIds = mutableListOf<Long>()

    private val fakeRepo = object : TransactionRepository by FakeTransactionRepository(funds, transactions) {
        override suspend fun deleteTransaction(id: Long) {
            deletedIds.add(id)
        }
    }

    private val fakeSwitchWatchRepo = FakeSwitchWatchRepository()
    private val fakeMetadataRepo = FakeFundMetadataRepository()
    private val scheduler = FakeFundPriceRefreshScheduler()

    /** Fast "idag" så att årets resultat inte beror på när testet körs. */
    private val today = LocalDate.of(2026, 10, 11)

    private fun viewModel(repo: TransactionRepository = fakeRepo, prices: FundPriceRepository = fakePriceRepo) = TransaktionerViewModel(
        repo,
        fakeSwitchWatchRepo,
        RecordTransaction(
            repo,
            fakeSwitchWatchRepo,
            FakeSuggestionRecordRepository(),
            fakeMetadataRepo,
            scheduler,
            testTargetLevelPreselection(repo, fakeSwitchWatchRepo)
        ),
        preferencesRepository,
        prices
    ) { today }

    private fun kop(id: Long, day: Long, shares: Double, price: Double, fundId: String = fund.fundId) = Transaction(id = id, fundId = fundId, type = TransactionType.KOP, epochDay = day, shares = shares, pricePerShare = price)

    private fun salj(id: Long, day: Long, shares: Double, price: Double, fundId: String = fund.fundId, fee: Double = 0.0) = Transaction(id = id, fundId = fundId, type = TransactionType.SALJ, epochDay = day, shares = shares, pricePerShare = price, fee = fee)

    /** Väntar ut laddningen inom ett Turbine-block. */
    private suspend fun app.cash.turbine.ReceiveTurbine<TransaktionerUiState>.awaitLoaded(): TransaktionerUiState {
        var state = awaitItem()
        while (state.loading) state = awaitItem()
        return state
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        preferencesRepository = PreferencesRepository(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(dispatcher + SupervisorJob()),
                produceFile = { tempFolder.newFile("transaktioner_test.preferences_pb") }
            )
        )
    }

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
            salj(id = 2, day = 200, shares = 5.0, price = 210.0, fundId = "SE0003653302")
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

        vm.onEvent(TransaktionerEvent.DeleteTransaction(42))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(42L in deletedIds)
    }

    // --- Uppskattat datum (TRX-9, IMP-2) ---

    @Test
    fun `ett rättat datum sätts, markeringen försvinner och listan följer med`() = runTest(dispatcher) {
        val estimated = kop(id = 1, day = 100, shares = 2.0, price = 150.0).copy(dateEstimated = true)
        val other = kop(id = 2, day = 300, shares = 1.0, price = 160.0).copy(dateEstimated = true)
        transactions.value = listOf(other, estimated)
        val vm = viewModel()
        vm.uiState.test {
            val before = awaitLoaded()
            assertTrue(before.rows.all { it.transaction.dateEstimated })

            val corrected = LocalDate.of(2025, 3, 14)
            vm.onEvent(TransaktionerEvent.CorrectDate(1, corrected))

            var state = awaitItem()
            while (state.rows.first { it.transaction.id == 1L }.transaction.dateEstimated) state = awaitItem()
            val row = state.rows.first { it.transaction.id == 1L }.transaction
            assertEquals(corrected.toEpochDay(), row.epochDay)
            assertFalse(row.dateEstimated)
            // Bara den rättade raden ändras.
            assertEquals(other, state.rows.first { it.transaction.id == 2L }.transaction)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett läsfel i databasen visas som fel i stället för en tom lista (NFR-6)`() = runTest(dispatcher) {
        val failingRepo = object : TransactionRepository by fakeRepo {
            override fun observeTransactions(): Flow<List<Transaction>> = flow { throw DataError.Database }
        }
        val vm = viewModel(failingRepo)

        vm.uiState.test {
            val state = awaitLoaded()
            assertEquals(DataError.Database, state.error?.cause)
            assertFalse(state.isEmpty)
            assertTrue(state.allRows.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Försök igen läser om transaktionerna efter ett läsfel (NFR-6)`() = runTest(dispatcher) {
        val source = FailingUntilRetry()
        val flakyRepo = object : TransactionRepository by fakeRepo {
            override fun observeTransactions(): Flow<List<Transaction>> = source.guard { fakeRepo.observeTransactions() }
        }
        val vm = viewModel(flakyRepo)
        vm.uiState.test {
            val state = awaitRecoveryAfterRetry(source, { vm.onEvent(TransaktionerEvent.Retry) }, { it.loading }, { it.error })
            assertTrue(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
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
            kop(id = 1, day = 100, shares = 10.0, price = 100.0)
        )
        val vm = viewModel()
        vm.uiState.test {
            assertEquals(listOf(3L, 2L, 1L), awaitLoaded().rows.map { it.transaction.id })

            vm.onEvent(TransaktionerEvent.FilterSelected(HandelseFilter.KOP))
            val kop = awaitItem()
            assertEquals(HandelseFilter.KOP, kop.filter)
            assertEquals(listOf(2L, 1L), kop.rows.map { it.transaction.id })
            assertFalse("Köp visar inget realiserat resultat", kop.showRealizedCard)

            vm.onEvent(TransaktionerEvent.FilterSelected(HandelseFilter.SALJ))
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
            kop(id = 1, day = lastYear - 100, shares = 20.0, price = 100.0)
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

    // --- Ungefärlig skatt i depå/AF (TRX-8, issue #199) ---

    private val fundY = Fund(fundId = "SHB0000999", name = "Tillväxtmarknad Bred")

    private fun epochDay(year: Int, month: Int, dayOfMonth: Int) = LocalDate.of(year, month, dayOfMonth).toEpochDay()

    /**
     * Två fonder köpta 2025 (200 à 100 kr och 100 à 300 kr), sålda i år med vinst 12 000 kr och
     * förlust 2 400 kr; kurserna föregående bankdag, 150 kr (30 december) och 250 kr (31 december),
     * ger 55 000 kr den 1 januari. Kursen 2 januari används inte.
     */
    private fun setUpDepaYear() {
        funds.value = listOf(fund, fundY)
        transactions.value = listOf(
            salj(id = 4, day = epochDay(2026, 8, 3), shares = 40.0, price = 240.0, fundId = fundY.fundId),
            salj(id = 3, day = epochDay(2026, 5, 12), shares = 120.0, price = 200.0),
            kop(id = 2, day = epochDay(2025, 4, 1), shares = 100.0, price = 300.0, fundId = fundY.fundId),
            kop(id = 1, day = epochDay(2025, 3, 3), shares = 200.0, price = 100.0)
        )
        fakePriceRepo.priceHistoryByFundId = mapOf(
            fund.fundId to listOf(FundPrice(fund.fundId, epochDay(2025, 12, 30), 150.0), FundPrice(fund.fundId, epochDay(2026, 1, 2), 160.0)),
            fundY.fundId to listOf(FundPrice(fundY.fundId, epochDay(2025, 12, 31), 250.0))
        )
    }

    @Test
    fun `depa visar skatt pa arets vinst efter kvittning och schablonintakten (TRX-8)`() = runTest(dispatcher) {
        setUpDepaYear()
        preferencesRepository.setAccountType(AccountType.DEPA_AF)
        viewModel().uiState.test {
            var state = awaitLoaded()
            while (state.standardIncomeTax == null) state = awaitItem()
            // python tools/fondmatte/fondmatte.py depa --gains 12000 -2400 --jan1 55000
            //   → realized.gains 12000, losses −2400, tax 2880; standard_income_tax 66
            assertTrue(state.depa)
            assertTrue(state.showRealizedTax)
            assertEquals(12_000.0, state.yearRealizedTax.gains, 1e-9)
            assertEquals(-2_400.0, state.yearRealizedTax.losses, 1e-9)
            assertEquals(2_880.0, state.yearRealizedTax.tax, 1e-9)
            assertEquals(55_000.0, state.standardIncomeTax!!.valueAtYearStart, 1e-9)
            assertEquals(66.0, state.standardIncomeTax!!.tax, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett lasfel i kurserna ger ingen skatt men faller inte listan (TRX-8, NFR-6)`() = runTest(dispatcher) {
        setUpDepaYear()
        preferencesRepository.setAccountType(AccountType.DEPA_AF)
        val failingPrices = object : FundPriceRepository by fakePriceRepo {
            override fun observeHistories(fundIds: List<String>, fromEpochDay: Long): Flow<Map<String, List<FundPrice>>> = flow { throw IllegalStateException("kurscachen gick inte att läsa") }
        }
        viewModel(prices = failingPrices).uiState.test {
            val state = awaitLoaded()
            assertNull(state.error)
            assertEquals(4, state.rows.size)
            assertFalse(state.depa)
            assertNull(state.standardIncomeTax)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isk visar ingen skatt i arskortet (TRX-8)`() = runTest(dispatcher) {
        setUpDepaYear()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        viewModel().uiState.test {
            val state = awaitLoaded()
            assertFalse(state.depa)
            assertFalse(state.showRealizedTax)
            assertNull(state.standardIncomeTax)
            assertTrue(state.showRealizedCard)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `depa utan salj visar kortet for schablonintakten men inte resultatraden (TRX-8)`() = runTest(dispatcher) {
        funds.value = listOf(fund)
        transactions.value = listOf(kop(id = 1, day = epochDay(2025, 3, 3), shares = 200.0, price = 100.0))
        fakePriceRepo.priceHistoryByFundId = mapOf(fund.fundId to listOf(FundPrice(fund.fundId, epochDay(2025, 12, 30), 150.0)))
        preferencesRepository.setAccountType(AccountType.DEPA_AF)
        viewModel().uiState.test {
            var state = awaitLoaded()
            while (state.standardIncomeTax == null) state = awaitItem()
            assertTrue(state.showRealizedCard)
            assertFalse(state.showRealizedRow)
            assertFalse(state.showRealizedTax)
            assertEquals(30_000.0, state.standardIncomeTax!!.valueAtYearStart, 1e-9)
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
            salj(id = 4, day = 200, shares = 5.0, price = 190.0, fundId = fondB.fundId)
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
            kop(id = 1, day = 100, shares = 10.0, price = 100.0)
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
            kop(id = 1, day = 100, shares = 10.0, price = 100.0)
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
            kop(id = 1, day = 100, shares = 4.0, price = 100.0)
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
            kop(id = 1, day = 100, shares = 10.0, price = 100.0)
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
            salj(id = 4, day = 200, shares = 5.0, price = 120.0, fundId = utanIsin.fundId)
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
            vm.onEvent(TransaktionerEvent.StartSwitchWatch(state.rows.first { it.sale != null }))
            advanceUntilIdle()
            assertEquals(fakeSwitchWatchRepo.watches.value.single().id, expectMostRecentItem().startedSwitchWatchId)
            vm.onEvent(TransaktionerEvent.SwitchWatchOpened)
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
            vm.onEvent(TransaktionerEvent.StartSwitchWatch(row))
            advanceUntilIdle()
            vm.onEvent(TransaktionerEvent.StartSwitchWatch(row))
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
            kop(id = 1, day = today.minusDays(100).toEpochDay(), shares = 10.0, price = 100.0)
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
            kop(id = 1, day = today.minusDays(100).toEpochDay(), shares = 10.0, price = 100.0)
        )
        val existing = fakeSwitchWatchRepo.start(SwitchWatch(sellIsin = "SE_MED", sellFundName = "Med ISIN", soldAtEpochDay = soldAt, proceedsKr = 4_200.0))
        val vm = viewModel()

        vm.uiState.test {
            val row = awaitLoaded().rows.first { it.sale != null }
            assertTrue(row.canStartSwitchWatch)
            vm.onEvent(TransaktionerEvent.StartSwitchWatch(row))
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
        tags = emptyList()
    )
}
