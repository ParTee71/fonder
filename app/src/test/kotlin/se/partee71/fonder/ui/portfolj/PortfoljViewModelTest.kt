package se.partee71.fonder.ui.portfolj

import app.cash.turbine.test
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.data.repository.FakeCachedPriceRepository
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeTransactionRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.model.FundTag
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import se.partee71.fonder.testing.FailingUntilRetry
import se.partee71.fonder.testing.awaitItemSkipping
import se.partee71.fonder.testing.awaitRecoveryAfterRetry
import se.partee71.fonder.testing.buy
import se.partee71.fonder.testing.holdFundAWorth1200
import se.partee71.fonder.testing.holdOnly
import se.partee71.fonder.testing.price
import se.partee71.fonder.testing.quoteOnly
import se.partee71.fonder.testing.testFundMetadata
import se.partee71.fonder.worker.BackgroundWork
import se.partee71.fonder.worker.FakeFundPriceRefreshScheduler

@OptIn(ExperimentalCoroutinesApi::class)
class PortfoljViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** Driver kortens väntesnurror (NAV-6) — se [FakeFundPriceRefreshScheduler.runningWork]. */
    private val fakeScheduler = FakeFundPriceRefreshScheduler()

    private val funds = MutableStateFlow<List<Fund>>(emptyList())
    private val transactions = MutableStateFlow<List<Transaction>>(emptyList())

    private val fakeTransactionRepo = FakeTransactionRepository(funds, transactions)

    private val cachedPrices = FakeCachedPriceRepository()
    private val latestPrices = cachedPrices.latestPrices
    private var priceHistoryByFundId: Map<String, List<FundPrice>> by cachedPrices::priceHistoryByFundId
    private val refreshedFundIds = mutableListOf<String>()
    private val refreshSinceCalls = mutableListOf<Triple<String, String, java.time.LocalDate>>()

    private val fakeFundPriceRepo = object : FundPriceRepository by cachedPrices {
        override suspend fun refresh(fundId: String, since: LocalDate?): Boolean {
            refreshedFundIds.add(fundId)
            return true
        }
        override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate): Boolean {
            refreshSinceCalls.add(Triple(fundId, isin, since))
            return true
        }
    }

    /** Låter metadata-uppslaget hänga, som ett sekventiellt nätverksanrop utan svar. */
    private var metadataForBlocks = false

    private val fakeFundMetadataRepo = object : FakeFundMetadataRepository() {
        override suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata> {
            // Simulerar det sekventiella nätverksuppslaget som aldrig svarar (offline/hängande).
            if (metadataForBlocks) awaitCancellation()
            return super.metadataFor(isins)
        }
    }
    private var metadataByIsin: Map<String, FundMetadata> by fakeFundMetadataRepo::metadataByIsin

    private fun viewModel() = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, fakeScheduler)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tomt tillstand nar inga transaktioner finns`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            // Initialt: laddar
            assertTrue(awaitItem().loading)
            // Efter combine: tomt och inte laddar
            val loaded = awaitItem()
            assertFalse(loaded.loading)
            assertTrue(loaded.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett läsfel i databasen visas som fel i stället för en tom portfölj (NFR-6)`() = runTest(dispatcher) {
        val failingRepo = object : TransactionRepository by fakeTransactionRepo {
            override fun observeTransactions(): Flow<List<Transaction>> = flow { throw DataError.Database }
        }
        val vm = PortfoljViewModel(failingRepo, fakeFundPriceRepo, fakeFundMetadataRepo, fakeScheduler)
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }
            assertEquals(DataError.Database, state.error?.cause)
            assertFalse(state.isEmpty)
            assertTrue(state.holdings.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Försök igen läser om innehaven efter ett läsfel (NFR-6)`() = runTest(dispatcher) {
        val source = FailingUntilRetry()
        val flakyRepo = object : TransactionRepository by fakeTransactionRepo {
            override fun observeTransactions(): Flow<List<Transaction>> = source.guard { fakeTransactionRepo.observeTransactions() }
        }
        val vm = PortfoljViewModel(flakyRepo, fakeFundPriceRepo, fakeFundMetadataRepo, fakeScheduler)
        vm.uiState.test {
            val state = awaitRecoveryAfterRetry(source, { vm.onEvent(PortfoljEvent.Retry) }, { it.loading }, { it.error })
            assertTrue(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `holdings utan kand kurs visar netInvested och null gainLoss`() = runTest(dispatcher) {
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        fakeTransactionRepo.holdOnly(fond, LocalDate.ofEpochDay(1), 2.0, 150.0)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }
            assertEquals(1, state.holdings.size)
            assertEquals(300.0, state.totalInvested, 1e-9)
            assertEquals(0.0, state.totalValue, 1e-9)
            assertNull(state.totalGainLossFraction)
            assertNull(state.holdings.first().currentValue)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `helt avsald fond visas inte i portfoljen`() = runTest(dispatcher) {
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            buy(fond, LocalDate.ofEpochDay(1), 2.0, 150.0),
            Transaction(fundId = fond.fundId, type = TransactionType.SALJ, epochDay = 2, shares = 2.0, pricePerShare = 160.0)
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }
            assertTrue(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `holdings uppdateras reaktivt nar en ny kurs blir kand`() = runTest(dispatcher) {
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        fakeTransactionRepo.holdOnly(fond, LocalDate.ofEpochDay(1), 2.0, 150.0)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }
            assertNull(state.holdings.first().currentValue)

            latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = 5, nav = 200.0))

            // Vänta på den emission som faktiskt bär kursen, inte på "nästa": tillståndet
            // emitterar också när kortens körstatus ändras (NAV-6), och `awaitItem()` ensamt
            // hade då läst av en emission som råkade komma före kursen.
            var updated = awaitItemSkipping { it.holdings.first().currentValue == null }
            assertEquals(400.0, updated.totalValue, 1e-9)
            assertEquals(100.0, updated.totalGainLoss, 1e-9)
            assertEquals(100.0, updated.total.totalGainKr!!, 1e-9) // POR-3, issue #191
            assertEquals(5L, updated.navEpochDay) // POR-7, issue #27
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `totalresultatet raknar med forlusten i en helt avsald fond men orealiserat gor det inte`() = runTest(dispatcher) {
        val fondA = Fund(fundId = "SHB0000442", name = "Fond A")
        val fondB = Fund(fundId = "SHB0000443", name = "Fond B")
        funds.value = listOf(fondA, fondB)
        transactions.value = listOf(
            buy(fondA, LocalDate.ofEpochDay(1), 2.0, 150.0),
            buy(fondB, LocalDate.ofEpochDay(1), 10.0, 100.0),
            Transaction(fundId = fondB.fundId, type = TransactionType.SALJ, epochDay = 2, shares = 10.0, pricePerShare = 80.0, fee = 10.0)
        )
        latestPrices.value = mapOf(fondA.fundId to FundPrice(fundId = fondA.fundId, epochDay = 5, nav = 200.0))

        val vm = viewModel()
        vm.uiState.test {
            val state = awaitItemSkipping { it.loading || it.holdings.firstOrNull()?.currentValue == null }
            assertEquals(100.0, state.totalGainLoss, 1e-9) // orealiserat på A
            // 300 + 1 000 in, 790 ut, 400 kvar: 400 + 790 − 1 300 = −110
            assertEquals(-110.0, state.total.totalGainKr!!, 1e-9)
            assertEquals(510.0, state.total.netDepositsKr, 1e-9)
            assertTrue(state.total.youngerThanYear)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `performance per innehav rakans ut fran kurshistorik (POR-5)`() = runTest(dispatcher) {
        val today = java.time.LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        fakeTransactionRepo.holdOnly(fond, today.minusYears(1))
        priceHistoryByFundId = mapOf(
            fond.fundId to listOf(
                price(fond, today.minusDays(1), 110.0),
                price(fond, today, 120.0)
            )
        )
        cachedPrices.quoteOnly(fond, today, 120.0)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }

            val performance = state.performance[fond.fundId]!!
            val day = performance.day as se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc.PeriodResult.Available
            assertEquals(100.0, day.amount, 1e-9) // 1200 - 10*110
            assertEquals(
                se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc.PeriodResult.InsufficientHistory,
                performance.week
            ) // ingen kurs 7 dagar bak i historiken
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `analys berknas per innehav och flaggar en fond under 200-dagars snitt (POR-8)`() = runTest(dispatcher) {
        val today = java.time.LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        fakeTransactionRepo.holdOnly(fond, today.minusYears(2))
        // NAV var högre förr — dagens kurs (100, vid daysAgo=0) hamnar under 200-dagarssnittet
        // (samma fixturprincip som FundAnalysisCalcTest/HemViewModelTest).
        priceHistoryByFundId = mapOf(
            fond.fundId to (0..730L step 5).map { daysAgo ->
                price(fond, today.minusDays(daysAgo), 100.0 + daysAgo * 0.05)
            }
        )
        cachedPrices.quoteOnly(fond, today, 100.0)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }

            val analysis = state.analysis[fond.fundId]!!
            assertEquals(se.partee71.fonder.domain.usecase.FundAnalysisCalc.SignalLevel.GUL, analysis.trend!!.level)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `analys innehaller vinstsignal vid plus 50 procent mot GAV, aven utan risksignaler (ANA-8)`() = runTest(dispatcher) {
        val today = java.time.LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        fakeTransactionRepo.holdOnly(fond, today.minusYears(1), 10.0, 80.0)
        // Kort historik (en enda punkt) — S1/S2/S3 blir otillräcklig data (null), men
        // vinstsignalen (S4) beror bara på GAV mot nuvarande NAV, inte historikens längd.
        priceHistoryByFundId = mapOf(fond.fundId to listOf(price(fond, today, 120.0)))
        cachedPrices.quoteOnly(fond, today, 120.0)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }

            val analysis = state.analysis[fond.fundId]!!
            assertNull(analysis.status) // ingen risksignal kunde beräknas
            assertTrue(analysis.profitTake!!.triggered) // (120-80)/80 = 0.5
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `engangsuppdatering anvander refreshSince for fond med isin, inte refresh`() = runTest(dispatcher) {
        // Fonder matchade via ISIN (t.ex. findFundByIsin, TP-14) saknar Handelsbanken-FundId
        // — refresh() nycklas på FundId och hittar dem aldrig. Regression för buggen där
        // sådana fonder aldrig fick sin engångsuppdatering vid första öppning av Portfölj.
        val since = java.time.LocalDate.of(2020, 1, 1)
        val fond = Fund(fundId = "LU0496367417", name = "Franklin Gold", isin = "LU0496367417")
        transactions.value = listOf(
            buy(fond, since, 1.0, 100.0)
        )
        funds.value = listOf(fond)

        viewModel()
        advanceUntilIdle()

        assertTrue(refreshedFundIds.isEmpty())
        assertEquals(1, refreshSinceCalls.size)
        assertEquals(Triple(fond.fundId, fond.isin, since), refreshSinceCalls.first())
    }

    @Test
    fun `engangsuppdatering hoppar over fond vars cachade kurs redan ar fran idag`() = runTest(dispatcher) {
        // Regression för issue #18: en redan färsk kurs ska inte trigga onödig nätverksrefresh.
        val today = java.time.LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        transactions.value = listOf(
            buy(fond, today.minusYears(1), 1.0, 100.0)
        )
        cachedPrices.quoteOnly(fond, today, 120.0)
        funds.value = listOf(fond)

        viewModel()
        advanceUntilIdle()

        assertTrue(refreshedFundIds.isEmpty())
        assertTrue(refreshSinceCalls.isEmpty())
    }

    @Test
    fun `engangsuppdatering hamtar ny kurs for fond vars cachade kurs ar aldre an idag`() = runTest(dispatcher) {
        // Regression för issue #18: root-orsaken till det falska "0" var att en inaktuell
        // cachad kurs aldrig hämtades om — engångsuppdateringen ska trigga refresh() även
        // när fonden redan har en (inaktuell) cachad kurs. 10 dagar gammal är otvetydigt
        // inaktuellt oavsett veckodag/klockslag (till skillnad från "igår", se NavCalendar,
        // issue #27) — testet ska vara deterministiskt oavsett när CI kör det.
        val today = java.time.LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        transactions.value = listOf(
            buy(fond, today.minusYears(1), 1.0, 100.0)
        )
        cachedPrices.quoteOnly(fond, today.minusDays(10), 110.0)
        funds.value = listOf(fond)

        viewModel()
        advanceUntilIdle()

        assertEquals(1, refreshedFundIds.size)
        assertEquals(fond.fundId, refreshedFundIds.first())
    }

    // --- Exponeringskarta (POR-9, issue #66) ---

    @Test
    fun `exposure anropar metadataFor med innehavens isin och rapporterar fondtyp`() = runTest(dispatcher) {
        val today = java.time.LocalDate.now()
        val fond = holdFundAWorth1200(fakeTransactionRepo, cachedPrices, today)
        metadataByIsin = mapOf(
            "SE0001466368" to testFundMetadata(isin = "SE0001466368", name = "Fond A", orderbookId = "X", totalFee = 0.73, managementFee = 0.65, indexFund = true, tags = listOf(FundTag(title = "Aktiefond", category = "TYPE")))
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }
            // Metadata hämtas i ett eget flöde (issue #75) — innehav och värden ritas direkt,
            // exponeringskartan fylls i när uppslaget landar.
            assertEquals(1200.0, state.totalValue, 1e-9)

            state = awaitItem()
            while (state.exposure.byType.buckets.isEmpty()) state = awaitItem()

            // 10 andelar × 120 kr = 1200 kr innehavsvärde, helt i "Aktiefond".
            assertEquals(1200.0, state.exposure.includedValueKr, 1e-9)
            assertEquals(0, state.exposure.excludedCount)
            assertEquals(listOf("Aktiefond"), state.exposure.byType.buckets.map { it.label })
            assertEquals(1.0, state.exposure.byType.buckets.single().fraction, 1e-9)
            assertEquals(1.0, state.exposure.indexStatus.indexFraction, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `innehav och totalvarde ritas utan att vanta pa metadata-uppslaget`() = runTest(dispatcher) {
        // Regression (issue #75): metadataFor gör ett sekventiellt nätverksuppslag per ISIN och
        // låg inne i tillståndets map — hela vyn blockerades på nätverket och visade "0,00 kr ·
        // Kurs saknas" så länge uppkopplingen hängde, trots att värdena fanns lokalt.
        val today = java.time.LocalDate.now()
        val fond = holdFundAWorth1200(fakeTransactionRepo, cachedPrices, today)
        metadataForBlocks = true

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }

            // Uppslaget hänger fortfarande — men innehavet är redan ritat.
            assertEquals(1, state.holdings.size)
            assertEquals(1200.0, state.totalValue, 1e-9)
            assertTrue(state.exposure.byType.buckets.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh ber schemalaggaren om en forcerad kursuppdatering (UI-11)`() = runTest(dispatcher) {
        // Samma körning och samma unika arbetsnamn som Hems dragning, så en dragning på var
        // sin skärm aldrig blir två parallella uppdateringar.
        val vm = viewModel()

        vm.onEvent(PortfoljEvent.Refresh)

        assertEquals(1, fakeScheduler.manualRefreshes)
    }

    @Test
    fun `exponeringskortet snurrar medan metadata-uppslaget pagar`() = runTest(dispatcher) {
        // Motstycket till testet ovan: att vyn ritas direkt är rätt, men en tom exponeringskarta
        // ska inte se ut som en portfölj källan inte kan klassificera. Snurran (NAV-6) är det som
        // skiljer "vet inte än" från "vet inte" (ANA-4-principen).
        val today = java.time.LocalDate.now()
        val fond = holdFundAWorth1200(fakeTransactionRepo, cachedPrices, today)
        metadataForBlocks = true

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || !it.metadataWorking }
            assertTrue(state.exposureWorking)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `en kursuppdatering snurrar totalkortet och varje innehavsrad`() = runTest(dispatcher) {
        val today = java.time.LocalDate.now()
        val fond = holdFundAWorth1200(fakeTransactionRepo, cachedPrices, today)
        fakeScheduler.runningWork.value = setOf(BackgroundWork.PRICE_REFRESH)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || !it.pricesWorking }
            assertTrue(state.totalWorking)
            assertTrue(state.holdingWorking(fond.fundId))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `exposure exkluderar innehav utan isin, aldrig gissar en kategori`() = runTest(dispatcher) {
        val today = java.time.LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A", isin = null)
        fakeTransactionRepo.holdOnly(fond, today.minusYears(1))
        cachedPrices.quoteOnly(fond, today, 120.0)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading }
            assertEquals(1, state.exposure.excludedCount)
            assertEquals(0.0, state.exposure.includedValueKr, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Risknivå per innehav (UI-10, issue #85) ---

    @Test
    fun `riskLevels fylls ur samma metadatauppslag som exponeringskartan`() = runTest(dispatcher) {
        val today = java.time.LocalDate.now()
        val medRisk = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE0001466368")
        val utanIsin = Fund(fundId = "SHB0000627", name = "Fond B", isin = null)
        funds.value = listOf(medRisk, utanIsin)
        transactions.value = listOf(
            buy(medRisk, today.minusYears(1), 10.0, 100.0),
            buy(utanIsin, today.minusYears(1), 10.0, 100.0)
        )
        latestPrices.value = mapOf(
            medRisk.fundId to price(medRisk, today, 120.0),
            utanIsin.fundId to price(utanIsin, today, 120.0)
        )
        metadataByIsin = mapOf(
            "SE0001466368" to testFundMetadata(isin = "SE0001466368", name = "Fond A", orderbookId = "X", totalFee = 0.73, managementFee = 0.65, risk = 5, indexFund = true)
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItemSkipping { it.loading || it.riskLevels.isEmpty() }

            assertEquals(5, state.riskLevels[medRisk.fundId])
            assertNull("Fond utan ISIN har ingen känd risk och ska inte gissas", state.riskLevels[utanIsin.fundId])
            cancelAndIgnoreRemainingEvents()
        }
    }
}
