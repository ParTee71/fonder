package se.partee71.fonder.ui.fond

import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
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
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class FondDetaljViewModelTest {

    private val dispatcher = StandardTestDispatcher()

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
        override suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata> = emptyMap()
        override suspend fun cachedMetadataFor(isins: List<String>): Map<String, FundMetadata> = emptyMap()
    }

    private fun viewModel() =
        FondDetaljViewModel(SavedStateHandle(mapOf("fundId" to fund.fundId)), fakeTransactionRepo, fakePriceRepo, fakeFundMetadataRepo)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
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
}
