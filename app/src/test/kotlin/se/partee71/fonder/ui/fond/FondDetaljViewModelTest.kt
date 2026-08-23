package se.partee71.fonder.ui.fond

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.model.FundTag
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class FondDetaljViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferencesRepository: PreferencesRepository

    private val fund = Fund(fundId = "SHB0000442", name = "Fond A")
    private val funds = MutableStateFlow(listOf(fund))
    private val transactionsForFund = MutableStateFlow<List<Transaction>>(emptyList())
    /** Alla transaktioner (alla fonder) — [FundAnalysisCalc] behöver portföljvida holdings, issue #16. */
    private val allTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    private val history = MutableStateFlow<List<FundPrice>>(emptyList())
    private val latestPricesFlow = MutableStateFlow<Map<String, FundPrice>>(emptyMap())
    private var latestPriceValue: FundPrice? = null
    private var refreshCalledFor: String? = null
    private var refreshSinceCall: Triple<String, String, LocalDate>? = null
    private var suggestIsinCalledWith: String? = null
    private var suggestIsinReturn: String? = null
    private var upsertedFund: Fund? = null
    private var capturedFromEpochDay: Long? = null

    private fun transaction(epochDay: Long) =
        Transaction(fundId = fund.fundId, type = TransactionType.KOP, epochDay = epochDay, shares = 1.0, pricePerShare = 100.0)

    private val fakeTransactionRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = funds
        override fun observeTransactions(): Flow<List<Transaction>> = allTransactions
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = transactionsForFund
        override suspend fun upsertFund(fund: Fund) {
            upsertedFund = fund
            funds.value = funds.value.map { if (it.fundId == fund.fundId) fund else it }
        }
        override suspend fun addTransaction(tx: Transaction): Long = 0
        override suspend fun deleteTransaction(id: Long) {}
        override suspend fun clearAll() {}
    }

    private val fakePriceRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? = latestPriceValue
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = latestPricesFlow
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long) = history.value
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> {
            capturedFromEpochDay = fromEpochDay
            return history
        }
        override suspend fun refresh(fundId: String, since: LocalDate?): Boolean {
            refreshCalledFor = fundId
            return true
        }
        override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate): Boolean {
            refreshSinceCall = Triple(fundId, isin, since)
            return true
        }
        override suspend fun historyForIsin(isin: String, from: LocalDate, to: LocalDate): List<FundPrice> {
            historyForIsinCalls += isin
            return historyByIsin[isin].orEmpty()
        }
        override suspend fun suggestIsin(fundName: String): String? {
            suggestIsinCalledWith = fundName
            return suggestIsinReturn
        }
        override suspend fun findFundByIsin(isin: String): Fund? = null
        override suspend fun lookupIsin(fundId: String): String? = null
        override suspend fun fetchFundsForCompany(companyId: String): List<Fund>? = emptyList()
        override suspend fun fetchFundCatalog(): FundCatalog = FundCatalog(emptyList(), emptyList())
    }

    private var suggestCheaperAlternativesCall: Pair<String, Double>? = null
    private var suggestCheaperAlternativesReturn: List<FeeComparisonCalc.Alternative>? = emptyList()

    /** Metadata per ISIN — fondens egen risknivå (UI-10) och bytesplanens uppslag (ANA-10). */
    private var metadataByIsin: Map<String, FundMetadata> = emptyMap()

    /** Kurshistorik per ISIN för jämförelsediagrammet (ANA-11), och räknaren som visar att den hämtas lazily. */
    private var historyByIsin: Map<String, List<FundPrice>> = emptyMap()
    private val historyForIsinCalls = mutableListOf<String>()

    private val fakeFundMetadataRepo = object : FundMetadataRepository {
        override suspend fun query(query: FundScreenQuery): List<FundMetadata> = emptyList()
        override suspend fun resolveHandelsbankenAvailability(isin: String): Boolean? = null
        override fun observeFilterVocabulary() = flowOf(FundFilterVocabulary())
        override suspend fun knownRiskLevels(): List<Int> = emptyList()
        override suspend fun findSwitchCandidates(level: Int, excludeIsins: Set<String>): List<SwitchPlanCalc.Candidate> = emptyList()
        override suspend fun suggestCheaperAlternatives(isin: String, holdingValue: Double): List<FeeComparisonCalc.Alternative>? {
            suggestCheaperAlternativesCall = isin to holdingValue
            return suggestCheaperAlternativesReturn
        }
        override suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata> = metadataByIsin.filterKeys { it in isins }
        override suspend fun cachedRiskByFundName(): Map<String, Int> = emptyMap()
        override suspend fun cachedMetadataFor(isins: List<String>): Map<String, FundMetadata> = emptyMap()
    }

    private val fakeSuggestionRecordRepo = object : SuggestionRecordRepository {
        val records = MutableStateFlow<List<SuggestionRecord>>(emptyList())
        override fun observeLatestBatch(): Flow<List<SuggestionRecord>> =
            records.map { list -> list.filter { it.kind == SuggestionKind.RISK_PLAN } }
        /** Nyast först, precis som DAO:ns `observeHistory` — ordningen är en del av kontraktet (issue #91). */
        override fun observeHistory(): Flow<List<SuggestionRecord>> = records.map { list ->
            list.sortedWith(
                compareByDescending<SuggestionRecord> { it.suggestedAtEpochDay }
                    .thenByDescending { it.batchEpochMillis }
                    .thenBy { it.planIndex },
            )
        }
        override suspend fun hasRecordedToday(sellIsin: String, buyIsin: String, epochDay: Long, kind: SuggestionKind): Boolean = false
        override suspend fun record(record: SuggestionRecord) { records.value = records.value + record }
        override suspend fun setFollowed(id: Long, followed: Boolean) {
            records.value = records.value.map { if (it.id == id) it.copy(followed = followed) else it }
        }
        override suspend fun prune(today: LocalDate) {}
    }

    private val fakeSwitchWatchRepo = FakeSwitchWatchRepository()

    private fun viewModel() = FondDetaljViewModel(
        SavedStateHandle(mapOf("fundId" to fund.fundId)),
        fakeTransactionRepo,
        fakePriceRepo,
        fakeFundMetadataRepo,
        preferencesRepository,
        fakeSuggestionRecordRepo,
        fakeSwitchWatchRepo,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Samma dispatcher som testet (se HemViewModelTest) — undviker en flaky Turbine-timeout
        // om DataStores egen skrivning annars kör på en frikopplad klocka.
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            produceFile = { tempFolder.newFile("fond_test.preferences_pb") },
        )
        preferencesRepository = PreferencesRepository(dataStore)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tomt tillstand nar ingen kurshistorik finns`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.isEmpty)
            assertEquals("Fond A", state.fundName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `visar kurshistorik sorterad fallande pa datum`() = runTest(dispatcher) {
        latestPriceValue = FundPrice(fundId = fund.fundId, epochDay = 200, nav = 145.0, currency = "SEK")
        history.value = listOf(
            FundPrice(fundId = fund.fundId, epochDay = 100, nav = 140.0, currency = "SEK"),
            FundPrice(fundId = fund.fundId, epochDay = 200, nav = 145.0, currency = "SEK"),
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(listOf(200L, 100L), state.prices.map { it.epochDay })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `purchaseEpochDays innehaller bara kop, inte salj, utan dubbletter`() = runTest(dispatcher) {
        val sell = Transaction(fundId = fund.fundId, type = TransactionType.SALJ, epochDay = 250, shares = 1.0, pricePerShare = 100.0)
        transactionsForFund.value = listOf(
            transaction(epochDay = 100),
            transaction(epochDay = 100),
            transaction(epochDay = 200),
            sell,
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(listOf(100L, 200L), state.purchaseEpochDays.sorted())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `triggar engangsuppdatering nar ingen kurs ar cachad`() = runTest(dispatcher) {
        viewModel()
        advanceUntilIdle()
        assertEquals(fund.fundId, refreshCalledFor)
    }

    @Test
    fun `triggar inte uppdatering nar kursen redan ar aktuell`() = runTest(dispatcher) {
        // Gaten är den delade staleness-regeln (TP-17), inte längre "bara om cachen är tom":
        // en fond med en gammal cachad kurs ska uppdateras när man öppnar den (issue #37).
        latestPriceValue = FundPrice(fundId = fund.fundId, epochDay = LocalDate.now().toEpochDay(), nav = 140.0, currency = "SEK")
        viewModel()
        advanceUntilIdle()
        assertEquals(null, refreshCalledFor)
    }

    @Test
    fun `triggar uppdatering nar den cachade kursen ar inaktuell`() = runTest(dispatcher) {
        latestPriceValue = FundPrice(fundId = fund.fundId, epochDay = LocalDate.now().minusDays(10).toEpochDay(), nav = 140.0, currency = "SEK")
        viewModel()
        advanceUntilIdle()
        assertEquals(fund.fundId, refreshCalledFor)
    }

    @Test
    fun `hamtar historik sedan forsta kopet via ISIN nar fonden har ett`() = runTest(dispatcher) {
        val fundWithIsin = fund.copy(isin = "SE0004297927")
        funds.value = listOf(fundWithIsin)
        transactionsForFund.value = listOf(transaction(epochDay = 100), transaction(epochDay = 50))

        viewModel()
        advanceUntilIdle()

        assertEquals(Triple(fund.fundId, "SE0004297927", LocalDate.ofEpochDay(50)), refreshSinceCall)
        assertEquals(null, refreshCalledFor)
    }

    @Test
    fun `vidgar visad kurshistorik till forsta kopet, inte bara ett ar tillbaka`() = runTest(dispatcher) {
        transactionsForFund.value = listOf(transaction(epochDay = 50))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(50L, capturedFromEpochDay)
    }

    @Test
    fun `foreslar isin nar fonden saknar det`() = runTest(dispatcher) {
        suggestIsinReturn = "SE0004297927"

        val vm = viewModel()
        advanceUntilIdle()
        assertEquals("Fond A", suggestIsinCalledWith)

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals("SE0004297927", state.suggestedIsin)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `foreslar inget isin nar fonden redan har ett`() = runTest(dispatcher) {
        funds.value = listOf(fund.copy(isin = "SE0004297927"))
        viewModel()
        advanceUntilIdle()
        assertEquals(null, suggestIsinCalledWith)
    }

    @Test
    fun `onIsinConfirmed sparar isin normaliserat och hamtar historik direkt`() = runTest(dispatcher) {
        transactionsForFund.value = listOf(transaction(epochDay = 50))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onIsinConfirmed(" se0004297927 ")
        advanceUntilIdle()

        assertEquals("SE0004297927", upsertedFund?.isin)
        assertEquals(Triple(fund.fundId, "SE0004297927", LocalDate.ofEpochDay(50)), refreshSinceCall)
    }

    @Test
    fun `onIsinConfirmed med tomt varde gor ingenting`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onIsinConfirmed("   ")
        advanceUntilIdle()

        assertNull(upsertedFund)
    }

    @Test
    fun `bygger analys for ett kvarvarande innehav`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val tx = Transaction(fundId = fund.fundId, type = TransactionType.KOP, epochDay = today.minusYears(2).toEpochDay(), shares = 10.0, pricePerShare = 100.0)
        transactionsForFund.value = listOf(tx)
        allTransactions.value = listOf(tx)
        // Flat kurshistorik varannan vecka i två år — inga signaler ska triggas.
        history.value = (0..730L step 14).map { daysAgo -> FundPrice(fundId = fund.fundId, epochDay = today.minusDays(daysAgo).toEpochDay(), nav = 100.0) }
        latestPricesFlow.value = mapOf(fund.fundId to FundPrice(fundId = fund.fundId, epochDay = today.toEpochDay(), nav = 100.0))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(FundAnalysisCalc.SignalLevel.GRON, state.analysis?.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `analys ar null om fonden ar helt avsald`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val buy = Transaction(fundId = fund.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0)
        val sell = Transaction(fundId = fund.fundId, type = TransactionType.SALJ, epochDay = today.minusMonths(1).toEpochDay(), shares = 10.0, pricePerShare = 110.0)
        transactionsForFund.value = listOf(buy, sell)
        allTransactions.value = listOf(buy, sell)
        history.value = listOf(FundPrice(fundId = fund.fundId, epochDay = today.toEpochDay(), nav = 110.0))
        latestPricesFlow.value = mapOf(fund.fundId to FundPrice(fundId = fund.fundId, epochDay = today.toEpochDay(), nav = 110.0))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertNull(state.analysis)
            // Helt avsåld fond är inget innehav längre — inget första köp/inköpsvärde att visa (POR-6).
            assertNull(state.firstPurchaseEpochDay)
            assertNull(state.netInvested)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `visar forsta kop och inkopsvarde for ett kvarvarande innehav (POR-6)`() = runTest(dispatcher) {
        val today = LocalDate.now()
        val tx = Transaction(fundId = fund.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0)
        transactionsForFund.value = listOf(tx)
        allTransactions.value = listOf(tx)
        history.value = listOf(FundPrice(fundId = fund.fundId, epochDay = today.toEpochDay(), nav = 100.0))
        latestPricesFlow.value = mapOf(fund.fundId to FundPrice(fundId = fund.fundId, epochDay = today.toEpochDay(), nav = 100.0))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(today.minusYears(1).toEpochDay(), state.firstPurchaseEpochDay)
            assertEquals(1000.0, state.netInvested)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Billigare alternativ (ANA-9, issue #59) ---

    private fun setUpHolding(isin: String? = "SE0004297927") {
        funds.value = listOf(if (isin != null) fund.copy(isin = isin) else fund)
        val today = LocalDate.now()
        val tx = Transaction(fundId = fund.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0)
        transactionsForFund.value = listOf(tx)
        allTransactions.value = listOf(tx)
        history.value = listOf(FundPrice(fundId = fund.fundId, epochDay = today.toEpochDay(), nav = 120.0))
        latestPricesFlow.value = mapOf(fund.fundId to FundPrice(fundId = fund.fundId, epochDay = today.toEpochDay(), nav = 120.0))
    }

    @Test
    fun `feeComparison kommer aven nar kurserna landar forst efter forsta tillstandet`() = runTest(dispatcher) {
        // Regression (issue #75): jobbet låste på `first { !it.loading }`. För ett innehav vars
        // kurscache ännu var tom var analysen null just då, jobbet gav upp för gott — och
        // ANA-9-kortet dök aldrig upp trots att refreshSince i samma init fyllde cachen strax
        // efter. Här seedas innehavet *utan* kurser, som i verkligheten vid en nyimport.
        val today = LocalDate.now()
        funds.value = listOf(fund.copy(isin = "SE0004297927"))
        val tx = Transaction(fundId = fund.fundId, type = TransactionType.KOP, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 100.0)
        transactionsForFund.value = listOf(tx)
        allTransactions.value = listOf(tx)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertNull("Utan kurser finns ingen analys ännu", state.analysis)
            assertNull(state.feeComparison)

            // Kurserna landar (motsvarar att refreshSince fyllt cachen).
            history.value = listOf(FundPrice(fundId = fund.fundId, epochDay = today.toEpochDay(), nav = 120.0))
            latestPricesFlow.value = mapOf(fund.fundId to FundPrice(fundId = fund.fundId, epochDay = today.toEpochDay(), nav = 120.0))

            state = awaitItem()
            while (state.feeComparison == null || state.feeComparison is FeeComparisonUiState.Loading) state = awaitItem()

            assertEquals(FeeComparisonUiState.NoCheaperAlternative, state.feeComparison)
            assertEquals("SE0004297927", suggestCheaperAlternativesCall?.first)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `feeComparison forblir null om fonden inte ar ett kvarvarande innehav`() = runTest(dispatcher) {
        // Inga transaktioner — inget innehav, inget kort ska visas alls (skiljs från "Unavailable").
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertNull(state.analysis)
            assertNull(state.feeComparison)
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
        assertNull("Inget uppslag ska göras för en fond som inte är ett innehav", suggestCheaperAlternativesCall)
    }

    @Test
    fun `feeComparison blir Unavailable direkt om innehavet saknar isin`() = runTest(dispatcher) {
        setUpHolding(isin = null)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.analysis != null)
            while (state.feeComparison == null) state = awaitItem()
            assertEquals(FeeComparisonUiState.Unavailable, state.feeComparison)
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
        assertNull("Utan ISIN ska repositoryt aldrig anropas", suggestCheaperAlternativesCall)
    }

    @Test
    fun `feeComparison anropar suggestCheaperAlternatives med ratt isin och innehavsvarde`() = runTest(dispatcher) {
        setUpHolding(isin = "SE0004297927")

        viewModel()
        advanceUntilIdle()

        // 10 andelar × 120 kr senaste NAV = 1200 kr innehavsvärde.
        assertEquals("SE0004297927" to 1200.0, suggestCheaperAlternativesCall)
    }

    @Test
    fun `feeComparison blir Found med alternativen fran repositoryt`() = runTest(dispatcher) {
        setUpHolding()
        val alternative = FeeComparisonCalc.Alternative(
            candidate = FundMetadata(
                isin = "SE0000581434", name = "Länsförsäkringar Sverige Index", orderbookId = "12345",
                totalFee = 0.21, managementFee = 0.2, category = "Sverige", fundType = "EQUITY_FUND",
                companyName = "Länsförsäkringar", risk = null, indexFund = true, startDateEpochDay = null,
                minimumBuy = null, tags = emptyList(),
            ),
            candidateFeePercent = 0.21,
            annualSavingsKr = 1560.0,
        )
        suggestCheaperAlternativesReturn = listOf(alternative)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            while (state.feeComparison == null || state.feeComparison is FeeComparisonUiState.Loading) state = awaitItem()
            val found = state.feeComparison as FeeComparisonUiState.Found
            assertEquals(listOf(alternative), found.alternatives)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `feeComparison blir NoCheaperAlternative nar listan ar tom`() = runTest(dispatcher) {
        setUpHolding()
        suggestCheaperAlternativesReturn = emptyList()

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            while (state.feeComparison == null || state.feeComparison is FeeComparisonUiState.Loading) state = awaitItem()
            assertEquals(FeeComparisonUiState.NoCheaperAlternative, state.feeComparison)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `feeComparison blir Unavailable nar repositoryt inte kan jamfora fonden`() = runTest(dispatcher) {
        setUpHolding()
        suggestCheaperAlternativesReturn = null

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            while (state.feeComparison == null || state.feeComparison is FeeComparisonUiState.Loading) state = awaitItem()
            assertEquals(FeeComparisonUiState.Unavailable, state.feeComparison)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Bytesbeslutet på fondkortet (ANA-10/ANA-11/UI-10, issue #85) ---

    private fun metadata(isin: String, name: String, risk: Int?, fee: Double?) = FundMetadata(
        isin = isin, name = name, orderbookId = "ob-$isin", totalFee = fee, managementFee = fee,
        category = "Sverige", fundType = "EQUITY_FUND", companyName = "Bolaget", risk = risk,
        indexFund = true, startDateEpochDay = null, minimumBuy = null, tags = emptyList<FundTag>(),
    )

    /** Seedar ett innehav med ISIN, en ISK/KF-kontotyp och en färsk inspelad bytesplan. */
    private suspend fun setUpSwitchPlan(
        followed: Boolean? = null,
        suggestedAtEpochDay: Long = LocalDate.now().toEpochDay(),
        accountType: AccountType = AccountType.ISK_KF,
    ) {
        setUpHolding(isin = "SE0004297927")
        metadataByIsin = mapOf(
            "SE0004297927" to metadata("SE0004297927", "Fond A", risk = 5, fee = 1.2),
            "SE0000581434" to metadata("SE0000581434", "Fond B", risk = 4, fee = 0.4),
        )
        preferencesRepository.setAccountType(accountType)
        fakeSuggestionRecordRepo.records.value = listOf(
            SuggestionRecord(
                id = 7, suggestedAtEpochDay = suggestedAtEpochDay, planIndex = 0,
                sellIsin = "SE0004297927", buyIsin = "SE0000581434",
                sellNavAtSuggestion = 120.0, buyNavAtSuggestion = 90.0,
                switchValueKr = 4000.0, followed = followed,
            ),
        )
    }

    @Test
    fun `visar fondens egen risknniva ur metadatan`() = runTest(dispatcher) {
        setUpHolding(isin = "SE0004297927")
        metadataByIsin = mapOf("SE0004297927" to metadata("SE0004297927", "Fond A", risk = 6, fee = 1.2))

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.riskLevel == null) state = awaitItem()
            assertEquals(6, state.riskLevel)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `risknniva forblir null nar metadatan inte kanner fonden`() = runTest(dispatcher) {
        setUpHolding(isin = "SE0004297927")
        metadataByIsin = emptyMap()

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            advanceUntilIdle()
            assertNull("Okänd risk ska aldrig gissas (ANA-4)", state.riskLevel)
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
    }

    @Test
    fun `visar bytesplanens forslag som ror fonden`() = runTest(dispatcher) {
        setUpSwitchPlan()

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.switchPlan.isEmpty()) state = awaitItem()

            val suggestion = state.switchPlan.single()
            assertEquals(0, suggestion.planIndex)
            assertEquals("Fond B", suggestion.buyFundName)
            assertEquals(5, suggestion.fromLevel)
            assertEquals(4, suggestion.toLevel)
            assertEquals(4000.0, suggestion.switchValueKr)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `visar ingen bytesplan utan ISK eller KF`() = runTest(dispatcher) {
        setUpSwitchPlan(accountType = AccountType.DEPA_AF)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            advanceUntilIdle()
            assertTrue("SET-4-gaten gäller även på fondkortet", state.switchPlan.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
    }

    @Test
    fun `visar ingen bytesplan nar den inspelade planen ar for gammal`() = runTest(dispatcher) {
        // Äldre än SwitchPlanCalc.PLAN_TTL_DAYS — ett gammalt råd ska försvinna, inte ligga kvar.
        setUpSwitchPlan(suggestedAtEpochDay = LocalDate.now().minusDays(SwitchPlanCalc.PLAN_TTL_DAYS + 1).toEpochDay())

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            advanceUntilIdle()
            assertTrue(state.switchPlan.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
    }

    @Test
    fun `visar inte bytesforslag som handlar om andra fonder`() = runTest(dispatcher) {
        setUpHolding(isin = "SE0004297927")
        metadataByIsin = mapOf(
            "SE0004297927" to metadata("SE0004297927", "Fond A", risk = 5, fee = 1.2),
            "SE0000581434" to metadata("SE0000581434", "Fond B", risk = 4, fee = 0.4),
            "SE0009778954" to metadata("SE0009778954", "Fond C", risk = 3, fee = 0.3),
        )
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        fakeSuggestionRecordRepo.records.value = listOf(
            SuggestionRecord(
                id = 9, suggestedAtEpochDay = LocalDate.now().toEpochDay(), planIndex = 0,
                sellIsin = "SE0000581434", buyIsin = "SE0009778954",
                sellNavAtSuggestion = 90.0, buyNavAtSuggestion = 80.0, switchValueKr = 1000.0,
            ),
        )

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            advanceUntilIdle()
            assertTrue("Planen nämner inte den öppnade fonden", state.switchPlan.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
    }

    @Test
    fun `hamtar kandidatens kurshistorik forst nar forslaget falls ut, och bara en gang`() = runTest(dispatcher) {
        setUpHolding(isin = "SE0004297927")
        historyByIsin = mapOf(
            "SE0000581434" to listOf(
                FundPrice(fundId = "SE0000581434", epochDay = 200, nav = 110.0),
                FundPrice(fundId = "SE0000581434", epochDay = 100, nav = 100.0),
            ),
        )

        val vm = viewModel()
        // Tillståndet måste kollektas: uiState delas med WhileSubscribed, så utan en aktiv
        // prenumerant körs uppströmsflödet aldrig och `value` stannar på initialtillståndet.
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            advanceUntilIdle()
            assertTrue("Ingen hämtning innan något fällts ut", historyForIsinCalls.isEmpty())

            vm.onSuggestionExpanded("SE0000581434")
            advanceUntilIdle()
            vm.onSuggestionExpanded("SE0000581434")
            advanceUntilIdle()

            assertEquals(listOf("SE0000581434"), historyForIsinCalls)
            val comparison = vm.uiState.value.comparisons["SE0000581434"]
            assertEquals(listOf(100L to 100.0, 200L to 110.0), (comparison as ComparisonUiState.Ready).points)
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
    }

    @Test
    fun `kandidat utan hamtbar historik markeras som ojamforbar`() = runTest(dispatcher) {
        setUpHolding(isin = "SE0004297927")
        historyByIsin = emptyMap()

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            vm.onSuggestionExpanded("SE0000581434")
            advanceUntilIdle()

            assertEquals(ComparisonUiState.Unavailable, vm.uiState.value.comparisons["SE0000581434"])
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
    }

    // --- Kvittering av avgiftsbytet (ANA-9/SET-5, issue #91) ---

    /** Seedar ett innehav med ISIN och en inspelad FEE-rad för kandidaten [buyIsin]. */
    private suspend fun setUpRecordedFeeSwitch(
        buyIsin: String = "SE0000581434",
        followed: Boolean? = null,
        suggestedAtEpochDay: Long = LocalDate.now().toEpochDay(),
        id: Long = 21,
    ) {
        setUpHolding(isin = "SE0004297927")
        fakeSuggestionRecordRepo.records.value = fakeSuggestionRecordRepo.records.value + SuggestionRecord(
            id = id, suggestedAtEpochDay = suggestedAtEpochDay, planIndex = 0,
            sellIsin = "SE0004297927", buyIsin = buyIsin,
            sellNavAtSuggestion = 120.0, buyNavAtSuggestion = 90.0,
            switchValueKr = 10_000.0, followed = followed, kind = SuggestionKind.FEE,
        )
    }

    @Test
    fun `inspelat avgiftsbyte exponeras med sitt rad-id och sin kvittering`() = runTest(dispatcher) {
        setUpRecordedFeeSwitch(followed = true)

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.recordedFeeSwitches.isEmpty()) state = awaitItem()

            val recorded = state.recordedFeeSwitches.getValue("SE0000581434")
            assertEquals(21L, recorded.recordId)
            assertTrue(recorded.followed)
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
    }

    @Test
    fun `alternativ utan inspelad rad saknas i kartan — ingen kryssruta som inte skriver nagonstans`() =
        runTest(dispatcher) {
            // Listan räknas om live vid varje skärmöppning, raden skrivs av bakgrundsskanningen.
            // Ett alternativ som just dykt upp har alltså inget att kvittera mot ännu.
            setUpRecordedFeeSwitch(buyIsin = "SE0009778954")

            val vm = viewModel()
            vm.uiState.test {
                var state = awaitItem()
                while (state.recordedFeeSwitches.isEmpty()) state = awaitItem()

                assertNull(state.recordedFeeSwitches["SE0000581434"])
                cancelAndIgnoreRemainingEvents()
            }
            advanceUntilIdle()
        }

    @Test
    fun `planens rader hamnar aldrig bland avgiftsbytena`() = runTest(dispatcher) {
        // De två sorterna mäts var för sig; skulle en riskplansrad räknas som ett avgiftsbyte
        // hade kvitteringen på avgiftsraden skrivit mot fel rad i facit.
        setUpSwitchPlan()

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.switchPlan.isEmpty()) state = awaitItem()
            advanceUntilIdle()

            assertTrue(state.recordedFeeSwitches.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
    }

    @Test
    fun `senaste inspelningen per kandidat vinner`() = runTest(dispatcher) {
        // observeHistory är nyast först: kvitteringen ska gälla dagens råd, inte ett halvår
        // gammalt som råkar nämna samma kandidat.
        setUpRecordedFeeSwitch(id = 5, suggestedAtEpochDay = LocalDate.now().minusDays(180).toEpochDay())
        setUpRecordedFeeSwitch(id = 6, suggestedAtEpochDay = LocalDate.now().toEpochDay())

        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.recordedFeeSwitches.isEmpty()) state = awaitItem()

            assertEquals(6L, state.recordedFeeSwitches.getValue("SE0000581434").recordId)
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()
    }

    @Test
    fun `setSwitchFollowed skriver flaggan pa den inspelade raden`() = runTest(dispatcher) {
        // Samma rad som Hems bytesplan skriver mot (SET-5) — en kvittering på fondkortet och
        // en på Hem ska vara samma händelse, inte två olika mätningar.
        setUpSwitchPlan()
        val vm = viewModel()
        advanceUntilIdle()

        vm.setSwitchFollowed(recordId = 7, followed = true)
        advanceUntilIdle()

        assertEquals(true, fakeSuggestionRecordRepo.records.value.single().followed)
    }
}
