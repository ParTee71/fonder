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
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.TransactionType

@OptIn(ExperimentalCoroutinesApi::class)
class SoldFundsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val fund = Fund(fundId = "SHB0000442", name = "Handelsbanken Sverige")
    private val funds = MutableStateFlow(listOf(fund))
    private val transactions = MutableStateFlow<List<Transaction>>(emptyList())

    private val fakeRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = funds
        override fun observeTransactions(): Flow<List<Transaction>> = transactions
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = transactions
        override suspend fun upsertFund(fund: Fund) {}
        override suspend fun addTransaction(tx: Transaction): Long = 0
        override suspend fun deleteTransaction(id: Long) {}
        override suspend fun clearAll() {}
    }

    private val fakeSwitchWatchRepo = FakeSwitchWatchRepository()
    private val fakeMetadataRepo = FakeFundMetadataRepository()

    private fun viewModel() = SoldFundsViewModel(fakeRepo, fakeSwitchWatchRepo, fakeMetadataRepo)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `tom transaktionshistorik ger tomt tillstand`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.isEmpty)
            assertEquals(0.0, state.totalRealizedGain, 1e-9)
            assertNull(state.totalRealizedGainFraction)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `totalRealizedGain summerar over flera salj, aven i olika fonder (SLD-3)`() = runTest(dispatcher) {
        val fondB = Fund(fundId = "SHB0000627", name = "Fond B")
        funds.value = listOf(fund, fondB)
        transactions.value = listOf(
            Transaction(id = 1, fundId = fund.fundId, type = TransactionType.KOP, epochDay = 100, shares = 10.0, pricePerShare = 100.0),
            Transaction(id = 2, fundId = fund.fundId, type = TransactionType.SALJ, epochDay = 200, shares = 10.0, pricePerShare = 150.0),
            Transaction(id = 3, fundId = fondB.fundId, type = TransactionType.KOP, epochDay = 100, shares = 5.0, pricePerShare = 200.0),
            Transaction(id = 4, fundId = fondB.fundId, type = TransactionType.SALJ, epochDay = 200, shares = 5.0, pricePerShare = 190.0),
        )
        val vm = viewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            // Fond A: 1500 - 1000 = 500. Fond B: 950 - 1000 = -50. Summa: 450.
            assertEquals(450.0, state.totalRealizedGain, 1e-9)
            // Summerat anskaffningsvärde: 1000 + 1000 = 2000.
            assertEquals(450.0 / 2000.0, state.totalRealizedGainFraction!!, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `totalRealizedGainFraction ar null nar summerat anskaffningsvarde ar 0`() = runTest(dispatcher) {
        // En sälj helt utan matchande köp — costBasis blir 0 (osäkert resultat, SLD-2).
        transactions.value = listOf(
            Transaction(id = 1, fundId = fund.fundId, type = TransactionType.SALJ, epochDay = 200, shares = 5.0, pricePerShare = 150.0),
        )
        val vm = viewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertNull(state.totalRealizedGainFraction)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `salj-transaktion ger en rad med fondnamn och realiserat resultat`() = runTest(dispatcher) {
        transactions.value = listOf(
            Transaction(id = 1, fundId = "SHB0000442", type = TransactionType.KOP, epochDay = 100, shares = 10.0, pricePerShare = 100.0),
            Transaction(id = 2, fundId = "SHB0000442", type = TransactionType.SALJ, epochDay = 200, shares = 10.0, pricePerShare = 150.0),
        )
        val vm = viewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertEquals(1, state.rows.size)
            val row = state.rows.first()
            assertEquals("Handelsbanken Sverige", row.fundName)
            assertEquals(500.0, row.sale.realizedGain, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `okand fond faller tillbaka pa fundId som namn`() = runTest(dispatcher) {
        funds.value = emptyList()
        transactions.value = listOf(
            Transaction(id = 1, fundId = "SE0003653302", type = TransactionType.KOP, epochDay = 100, shares = 5.0, pricePerShare = 200.0),
            Transaction(id = 2, fundId = "SE0003653302", type = TransactionType.SALJ, epochDay = 200, shares = 5.0, pricePerShare = 210.0),
        )
        val vm = viewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals("SE0003653302", state.rows.first().fundName)
            cancelAndIgnoreRemainingEvents()
        }
    }
    @Test
    fun `ett salj med kant ISIN kan starta en bevakning, ett utan kan inte (SLD-5)`() = runTest(dispatcher) {
        val medIsin = Fund(fundId = "SHB0000442", name = "Med ISIN", isin = "SE_MED")
        val utanIsin = Fund(fundId = "SHB0000627", name = "Utan ISIN", isin = null)
        funds.value = listOf(medIsin, utanIsin)
        transactions.value = listOf(
            Transaction(id = 1, fundId = medIsin.fundId, type = TransactionType.KOP, epochDay = 100, shares = 10.0, pricePerShare = 100.0),
            Transaction(id = 2, fundId = medIsin.fundId, type = TransactionType.SALJ, epochDay = 200, shares = 10.0, pricePerShare = 150.0),
            Transaction(id = 3, fundId = utanIsin.fundId, type = TransactionType.KOP, epochDay = 100, shares = 5.0, pricePerShare = 100.0),
            Transaction(id = 4, fundId = utanIsin.fundId, type = TransactionType.SALJ, epochDay = 200, shares = 5.0, pricePerShare = 120.0),
        )
        val vm = viewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            // Kurserna hämtas på ISIN — utan det går fonden varken att jämföra eller följa.
            assertTrue(state.rows.first { it.fundName == "Med ISIN" }.canStartSwitchWatch)
            assertFalse(state.rows.first { it.fundName == "Utan ISIN" }.canStartSwitchWatch)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startSwitchWatch anvander saljets datum och likvid, inte dagens`() = runTest(dispatcher) {
        val fond = Fund(fundId = "SHB0000442", name = "Med ISIN", isin = "SE_MED")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(id = 1, fundId = fond.fundId, type = TransactionType.KOP, epochDay = 100, shares = 10.0, pricePerShare = 100.0),
            Transaction(id = 2, fundId = fond.fundId, type = TransactionType.SALJ, epochDay = 200, shares = 10.0, pricePerShare = 150.0, fee = 25.0),
        )
        fakeMetadataRepo.metadataByIsin = mapOf("SE_MED" to metadata("SE_MED", risk = 6))
        val vm = viewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            vm.startSwitchWatch(state.rows.single())
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        val watch = fakeSwitchWatchRepo.watches.value.single()
        assertEquals("SE_MED", watch.sellIsin)
        // Utvecklingen ska mätas från dagen pengarna frigjordes, inte från idag.
        assertEquals(200L, watch.soldAtEpochDay)
        assertEquals(1_475.0, watch.proceedsKr!!, 1e-9)
        // Målnivån är säljfondens egen risknivå ur cachen — det närmaste "något likvärdigt".
        assertEquals(6, watch.targetLevel)
        assertNull(watch.sourceRecordId)
    }

    @Test
    fun `ett salj som redan bevakas erbjuder ingen ny bevakning`() = runTest(dispatcher) {
        val fond = Fund(fundId = "SHB0000442", name = "Med ISIN", isin = "SE_MED")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(id = 1, fundId = fond.fundId, type = TransactionType.KOP, epochDay = 100, shares = 10.0, pricePerShare = 100.0),
            Transaction(id = 2, fundId = fond.fundId, type = TransactionType.SALJ, epochDay = 200, shares = 10.0, pricePerShare = 150.0),
        )
        fakeSwitchWatchRepo.start(
            SwitchWatch(sellIsin = "SE_MED", sellFundName = "Med ISIN", soldAtEpochDay = 200),
        )
        val vm = viewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertFalse(state.rows.single().canStartSwitchWatch)
            cancelAndIgnoreRemainingEvents()
        }
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
