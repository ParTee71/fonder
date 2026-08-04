package se.partee71.fonder.ui.facit

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
import org.junit.Test
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import java.time.LocalDate

/** Facit-vyn (SET-5, issue #80) — se [FacitViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
class FacitViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val records = MutableStateFlow<List<SuggestionRecord>>(emptyList())
    private val funds = MutableStateFlow<List<Fund>>(emptyList())
    private val prices = MutableStateFlow<Map<String, FundPrice>>(emptyMap())
    private var metadataByIsin: Map<String, FundMetadata> = emptyMap()
    private val followedCalls = mutableListOf<Pair<Long, Boolean>>()
    private var metadataForCalled = false

    private val fakeSuggestionRepo = object : SuggestionRecordRepository {
        override fun observeLatestBatch(): Flow<List<SuggestionRecord>> = records
        override fun observeHistory(): Flow<List<SuggestionRecord>> = records.map { all ->
            all.sortedWith(
                compareByDescending<SuggestionRecord> { it.suggestedAtEpochDay }
                    .thenByDescending { it.batchEpochMillis }
                    .thenBy { it.planIndex },
            )
        }
        override suspend fun hasRecordedToday(sellIsin: String, buyIsin: String, epochDay: Long): Boolean = false
        override suspend fun record(record: SuggestionRecord) {}
        override suspend fun prune(today: LocalDate) {}
        override suspend fun setFollowed(id: Long, followed: Boolean) {
            followedCalls += id to followed
            records.value = records.value.map { if (it.id == id) it.copy(followed = followed) else it }
        }
    }

    private val fakeTransactionRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = funds
        override fun observeTransactions(): Flow<List<Transaction>> = flowOf(emptyList())
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = flowOf(emptyList())
        override suspend fun upsertFund(fund: Fund) {}
        override suspend fun addTransaction(tx: Transaction): Long = 0
        override suspend fun deleteTransaction(id: Long) {}
        override suspend fun clearAll() {}
    }

    private val fakePriceRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? = prices.value[fundId]
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = prices
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice> = emptyList()
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())
        override suspend fun refresh(fundId: String, since: LocalDate?) = true
        override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate) = true
        override suspend fun historyForIsin(isin: String, from: LocalDate, to: LocalDate): List<FundPrice> = emptyList()
        override suspend fun suggestIsin(fundName: String): String? = null
        override suspend fun findFundByIsin(isin: String): Fund? = null
        override suspend fun lookupIsin(fundId: String): String? = null
        override suspend fun fetchFundsForCompany(companyId: String): List<Fund>? = emptyList()
        override suspend fun fetchFundCatalog(): FundCatalog? = null
    }

    private val fakeMetadataRepo = object : FundMetadataRepository {
        override suspend fun query(query: FundScreenQuery): List<FundMetadata> = emptyList()
        override suspend fun resolveHandelsbankenAvailability(isin: String): Boolean? = null
        override fun observeFilterVocabulary() = flowOf(FundFilterVocabulary())
        override suspend fun suggestCheaperAlternatives(isin: String, holdingValue: Double): List<FeeComparisonCalc.Alternative>? = null
        override suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata> {
            metadataForCalled = true
            return metadataByIsin.filterKeys { it in isins }
        }
        override suspend fun cachedRiskByFundName(): Map<String, Int> = emptyMap()
        override suspend fun cachedMetadataFor(isins: List<String>): Map<String, FundMetadata> =
            metadataByIsin.filterKeys { it in isins }
        override suspend fun knownRiskLevels(): List<Int> = emptyList()
        override suspend fun findSwitchCandidates(level: Int, excludeIsins: Set<String>): List<SwitchPlanCalc.Candidate> = emptyList()
    }

    private fun viewModel() = FacitViewModel(fakeSuggestionRepo, fakeTransactionRepo, fakePriceRepo, fakeMetadataRepo)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun metadata(isin: String, name: String) = FundMetadata(
        isin = isin, name = name, orderbookId = isin, totalFee = 0.2, managementFee = 0.2,
        category = null, fundType = null, companyName = null, risk = 4, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
    )

    private fun record(
        id: Long,
        planIndex: Int = 0,
        sellNav: Double = 100.0,
        buyNav: Double = 200.0,
        switchValueKr: Double? = 10_000.0,
        followed: Boolean? = null,
        suggestedAtEpochDay: Long = 20_000,
    ) = SuggestionRecord(
        id = id,
        suggestedAtEpochDay = suggestedAtEpochDay,
        planIndex = planIndex,
        sellIsin = "SE_SELL",
        buyIsin = "SE_BUY",
        sellNavAtSuggestion = sellNav,
        buyNavAtSuggestion = buyNav,
        switchValueKr = switchValueKr,
        followed = followed,
    )

    /** Säljfonden är bevakad (kurs under fondens egen fundId), köpfonden inte (kurs under ISIN:et). */
    private fun setUpPrices(sellNavNow: Double? = 105.0, buyNavNow: Double? = 224.0) {
        funds.value = listOf(Fund(fundId = "SHB1", name = "Såld fond", isin = "SE_SELL"))
        metadataByIsin = mapOf(
            "SE_SELL" to metadata("SE_SELL", "Såld fond"),
            "SE_BUY" to metadata("SE_BUY", "Köpt fond"),
        )
        prices.value = buildMap {
            sellNavNow?.let { put("SHB1", FundPrice(fundId = "SHB1", epochDay = 20_100, nav = it)) }
            buyNavNow?.let { put("SE_BUY", FundPrice(fundId = "SE_BUY", epochDay = 20_100, nav = it)) }
        }
    }

    @Test
    fun `tomt tillstand utan inspelade forslag`() = runTest(dispatcher) {
        viewModel().uiState.test {
            assertTrue(awaitItem().loading)
            val loaded = awaitItem()
            assertFalse(loaded.loading)
            assertTrue(loaded.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `visar rad med uppslagna fondnamn och berakat utfall`() = runTest(dispatcher) {
        setUpPrices()
        records.value = listOf(record(id = 1))

        viewModel().uiState.test {
            awaitItem()
            val state = awaitItem()
            val row = state.rows.single()
            assertEquals("Såld fond", row.sellFundName)
            assertEquals("Köpt fond", row.buyFundName)
            assertEquals(0.07, row.outcome.excessReturn!!, 1e-9)
            assertEquals(700.0, row.outcome.excessKr!!, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `okant isin visas som isin i stallet for ett gissat namn`() = runTest(dispatcher) {
        setUpPrices()
        metadataByIsin = emptyMap()
        records.value = listOf(record(id = 1))

        viewModel().uiState.test {
            awaitItem()
            val row = awaitItem().rows.single()
            assertEquals("SE_SELL", row.sellFundName)
            assertEquals("SE_BUY", row.buyFundName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `totalen for alla forslag skiljer sig fran totalen for enbart genomforda`() = runTest(dispatcher) {
        setUpPrices()
        records.value = listOf(
            record(id = 1, followed = true), // +7 pp, +700 kr
            record(id = 2, sellNav = 100.0, buyNav = 200.0, followed = null),
        )
        // Rad 2 har samma NAV-utgångsläge och samma kurser, alltså samma utfall — skillnaden
        // mellan måtten ska komma ur *urvalet*, inte ur att raderna råkar ha olika utfall.

        viewModel().uiState.test {
            awaitItem()
            val state = awaitItem()
            assertEquals(2, state.allSummary.totalCount)
            assertEquals(1400.0, state.allSummary.totalExcessKr!!, 1e-9)
            assertEquals(1, state.followedSummary.totalCount)
            assertEquals(700.0, state.followedSummary.totalExcessKr!!, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `rad utan kand kurs ar ej utvarderad och drar inte ner totalen`() = runTest(dispatcher) {
        setUpPrices(buyNavNow = null)
        records.value = listOf(record(id = 1))

        viewModel().uiState.test {
            awaitItem()
            val state = awaitItem()
            assertFalse(state.rows.single().outcome.isEvaluated)
            assertNull(state.allSummary.averageExcessReturn)
            assertEquals(0, state.allSummary.evaluatedCount)
            assertEquals(1, state.allSummary.totalCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `snitt per plats i planen redovisas separat`() = runTest(dispatcher) {
        setUpPrices()
        records.value = listOf(record(id = 1, planIndex = 0), record(id = 2, planIndex = 1))

        viewModel().uiState.test {
            awaitItem()
            val state = awaitItem()
            assertEquals(listOf(0, 1), state.byPlanIndex.map { it.planIndex })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `att oppna facit gor aldrig ett natverksuppslag av metadata`() = runTest(dispatcher) {
        // Regression-skydd för SET-5:s avgränsning: metadataFor hämtar saknade/inaktuella rader
        // från nätet, cachedMetadataFor gör det aldrig. Historiken kan innehålla hundratals ISIN.
        setUpPrices()
        records.value = listOf(record(id = 1))

        viewModel().uiState.test {
            awaitItem()
            awaitItem()
            assertFalse(metadataForCalled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFollowed skriver mot repositoryt och speglas i tillstandet`() = runTest(dispatcher) {
        setUpPrices()
        records.value = listOf(record(id = 7))

        val vm = viewModel()
        vm.uiState.test {
            awaitItem()
            assertFalse(awaitItem().rows.single().followed)

            vm.setFollowed(7, true)

            assertTrue(awaitItem().rows.single().followed)
            assertEquals(listOf(7L to true), followedCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
