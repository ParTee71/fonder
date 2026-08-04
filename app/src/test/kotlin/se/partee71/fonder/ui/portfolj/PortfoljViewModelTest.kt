package se.partee71.fonder.ui.portfolj

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class PortfoljViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val funds = MutableStateFlow<List<Fund>>(emptyList())
    private val transactions = MutableStateFlow<List<Transaction>>(emptyList())

    private val fakeTransactionRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = funds
        override fun observeTransactions(): Flow<List<Transaction>> = transactions
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = transactions
        override suspend fun upsertFund(fund: Fund) {}
        override suspend fun addTransaction(tx: Transaction): Long = 0
        override suspend fun deleteTransaction(id: Long) {}
        override suspend fun clearAll() {}
    }

    private val latestPrices = MutableStateFlow<Map<String, FundPrice>>(emptyMap())
    private var priceHistoryByFundId: Map<String, List<FundPrice>> = emptyMap()
    private val refreshedFundIds = mutableListOf<String>()
    private val refreshSinceCalls = mutableListOf<Triple<String, String, java.time.LocalDate>>()

    private val fakeFundPriceRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? = latestPrices.value[fundId]
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = latestPrices
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice> =
            priceHistoryByFundId[fundId].orEmpty().filter { it.epochDay in fromEpochDay..toEpochDay }
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())
        override suspend fun refresh(fundId: String, since: LocalDate?): Boolean {
            refreshedFundIds.add(fundId)
            return true
        }
        override suspend fun refreshSince(fundId: String, isin: String, since: java.time.LocalDate): Boolean {
            refreshSinceCalls.add(Triple(fundId, isin, since))
            return true
        }
        override suspend fun historyForIsin(isin: String, from: LocalDate, to: LocalDate): List<FundPrice> = emptyList()
        override suspend fun suggestIsin(fundName: String): String? = null
        override suspend fun findFundByIsin(isin: String): Fund? = null
        override suspend fun lookupIsin(fundId: String): String? = null
        override suspend fun fetchFundsForCompany(companyId: String): List<Fund>? = emptyList()
        override suspend fun fetchFundCatalog(): FundCatalog = FundCatalog(emptyList(), emptyList())
    }

    private var metadataByIsin: Map<String, FundMetadata> = emptyMap()

    /** Låter metadata-uppslaget hänga, som ett sekventiellt nätverksanrop utan svar. */
    private var metadataForBlocks = false

    private val fakeFundMetadataRepo = object : FundMetadataRepository {
        override suspend fun query(query: FundScreenQuery): List<FundMetadata> = emptyList()
        override suspend fun resolveHandelsbankenAvailability(isin: String): Boolean? = null
        override fun observeFilterVocabulary() = flowOf(FundFilterVocabulary())
        override suspend fun knownRiskLevels(): List<Int> = emptyList()
        override suspend fun findSwitchCandidates(level: Int, excludeIsins: Set<String>): List<SwitchPlanCalc.Candidate> = emptyList()
        override suspend fun suggestCheaperAlternatives(isin: String, holdingValue: Double): List<FeeComparisonCalc.Alternative>? = null
        override suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata> {
            // Simulerar det sekventiella nätverksuppslaget som aldrig svarar (offline/hängande).
            if (metadataForBlocks) awaitCancellation()
            return metadataByIsin.filterKeys { it in isins }
        }
        override suspend fun cachedRiskByFundName(): Map<String, Int> = emptyMap()
        override suspend fun cachedMetadataFor(isins: List<String>): Map<String, FundMetadata> = metadataByIsin.filterKeys { it in isins }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tomt tillstand nar inga transaktioner finns`() = runTest(dispatcher) {
        val vm = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)
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
    fun `holdings utan kand kurs visar netInvested och null gainLoss`() = runTest(dispatcher) {
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = 1, shares = 2.0, pricePerShare = 150.0),
        )

        val vm = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
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
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = 1, shares = 2.0, pricePerShare = 150.0),
            Transaction(fundId = fond.fundId, type = TransactionType.SALJ, epochDay = 2, shares = 2.0, pricePerShare = 160.0),
        )

        val vm = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `holdings uppdateras reaktivt nar en ny kurs blir kand`() = runTest(dispatcher) {
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = 1, shares = 2.0, pricePerShare = 150.0),
        )

        val vm = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertNull(state.holdings.first().currentValue)

            latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = 5, nav = 200.0))

            val updated = awaitItem()
            assertEquals(400.0, updated.totalValue, 1e-9)
            assertEquals(100.0, updated.totalGainLoss, 1e-9)
            assertEquals(5L, updated.navEpochDay) // POR-7, issue #27
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `performance per innehav rakans ut fran kurshistorik (POR-5)`() = runTest(dispatcher) {
        val today = java.time.LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        priceHistoryByFundId = mapOf(
            fond.fundId to listOf(
                FundPrice(fundId = fond.fundId, epochDay = today.minusDays(1).toEpochDay(), nav = 110.0),
                FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0),
            ),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))

        val vm = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            val performance = state.performance[fond.fundId]!!
            val day = performance.day as se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc.PeriodResult.Available
            assertEquals(100.0, day.amount, 1e-9) // 1200 - 10*110
            assertEquals(
                se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc.PeriodResult.InsufficientHistory,
                performance.week,
            ) // ingen kurs 7 dagar bak i historiken
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `analys berknas per innehav och flaggar en fond under 200-dagars snitt (POR-8)`() = runTest(dispatcher) {
        val today = java.time.LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(2).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        // NAV var högre förr — dagens kurs (100, vid daysAgo=0) hamnar under 200-dagarssnittet
        // (samma fixturprincip som FundAnalysisCalcTest/HemViewModelTest).
        priceHistoryByFundId = mapOf(
            fond.fundId to (0..730L step 5).map { daysAgo ->
                FundPrice(fundId = fond.fundId, epochDay = today.minusDays(daysAgo).toEpochDay(), nav = 100.0 + daysAgo * 0.05)
            },
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 100.0))

        val vm = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            val analysis = state.analysis[fond.fundId]!!
            assertEquals(se.partee71.fonder.domain.usecase.FundAnalysisCalc.SignalLevel.GUL, analysis.trend!!.level)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `analys innehaller vinstsignal vid plus 50 procent mot GAV, aven utan risksignaler (ANA-8)`() = runTest(dispatcher) {
        val today = java.time.LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 80.0),
        )
        // Kort historik (en enda punkt) — S1/S2/S3 blir otillräcklig data (null), men
        // vinstsignalen (S4) beror bara på GAV mot nuvarande NAV, inte historikens längd.
        priceHistoryByFundId = mapOf(fond.fundId to listOf(FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0)))
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))

        val vm = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

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
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = since.toEpochDay(), shares = 1.0, pricePerShare = 100.0),
        )
        funds.value = listOf(fond)

        PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)
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
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 1.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))
        funds.value = listOf(fond)

        PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)
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
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 1.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.minusDays(10).toEpochDay(), nav = 110.0))
        funds.value = listOf(fond)

        PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)
        advanceUntilIdle()

        assertEquals(1, refreshedFundIds.size)
        assertEquals(fond.fundId, refreshedFundIds.first())
    }

    // --- Exponeringskarta (POR-9, issue #66) ---

    @Test
    fun `exposure anropar metadataFor med innehavens isin och rapporterar fondtyp`() = runTest(dispatcher) {
        val today = java.time.LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE0001466368")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))
        metadataByIsin = mapOf(
            "SE0001466368" to FundMetadata(
                isin = "SE0001466368", name = "Fond A", orderbookId = "X", totalFee = 0.73, managementFee = 0.65,
                category = null, fundType = null, companyName = null, risk = null, indexFund = true,
                startDateEpochDay = null, minimumBuy = null,
                tags = listOf(FundTag(title = "Aktiefond", category = "TYPE")),
            ),
        )

        val vm = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
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
        val fond = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE0001466368")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))
        metadataForBlocks = true

        val vm = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            // Uppslaget hänger fortfarande — men innehavet är redan ritat.
            assertEquals(1, state.holdings.size)
            assertEquals(1200.0, state.totalValue, 1e-9)
            assertTrue(state.exposure.byType.buckets.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `exposure exkluderar innehav utan isin, aldrig gissar en kategori`() = runTest(dispatcher) {
        val today = java.time.LocalDate.now()
        val fond = Fund(fundId = "SHB0000442", name = "Fond A", isin = null)
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(fond.fundId to FundPrice(fundId = fond.fundId, epochDay = today.toEpochDay(), nav = 120.0))

        val vm = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
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
            Transaction(fundId = medRisk.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
            Transaction(fundId = utanIsin.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0),
        )
        latestPrices.value = mapOf(
            medRisk.fundId to FundPrice(fundId = medRisk.fundId, epochDay = today.toEpochDay(), nav = 120.0),
            utanIsin.fundId to FundPrice(fundId = utanIsin.fundId, epochDay = today.toEpochDay(), nav = 120.0),
        )
        metadataByIsin = mapOf(
            "SE0001466368" to FundMetadata(
                isin = "SE0001466368", name = "Fond A", orderbookId = "X", totalFee = 0.73, managementFee = 0.65,
                category = null, fundType = null, companyName = null, risk = 5, indexFund = true,
                startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
            ),
        )

        val vm = PortfoljViewModel(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading || state.riskLevels.isEmpty()) state = awaitItem()

            assertEquals(5, state.riskLevels[medRisk.fundId])
            assertNull("Fond utan ISIN har ingen känd risk och ska inte gissas", state.riskLevels[utanIsin.fundId])
            cancelAndIgnoreRemainingEvents()
        }
    }
}
