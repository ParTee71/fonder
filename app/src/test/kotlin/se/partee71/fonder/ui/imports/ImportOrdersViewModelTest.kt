package se.partee71.fonder.ui.imports

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.partee71.fonder.data.imports.PdfTextExtractor
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import java.time.LocalDate

private const val NORDEA_TEXT = """
Nordea Fonder AB
Småbolagsfond Sverige
ISIN: SE0003653302
Text Belopp Kurs Andelar Saldo andelar
In Självbetjäning 2020-03-13 5 000.00 294.36 16.9862 16.9862
"""

@OptIn(ExperimentalCoroutinesApi::class)
class ImportOrdersViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val addedFunds = mutableListOf<Fund>()
    private val addedTransactions = mutableListOf<Transaction>()
    private val handelsbankenFund = Fund(fundId = "SHB0000442", name = "Handelsbanken Sverige (A1 SEK)")
    private val catalog = FundCatalog(companies = emptyList(), funds = listOf(handelsbankenFund))
    private var findFundByIsinResult: Fund? = null

    private val fakeTransactionRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = MutableStateFlow(emptyList())
        override fun observeTransactions(): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override suspend fun upsertFund(fund: Fund) {
            addedFunds.add(fund)
        }
        override suspend fun addTransaction(tx: Transaction): Long {
            addedTransactions.add(tx)
            return 1
        }
        override suspend fun deleteTransaction(id: Long) {}
    }

    private val fakePriceRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? = null
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = flowOf(emptyMap())
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice> = emptyList()
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())
        override suspend fun refresh(fundId: String) {}
        override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate) {}
        override suspend fun suggestIsin(fundName: String): String? = null
        override suspend fun findFundByIsin(isin: String): Fund? = findFundByIsinResult
        override suspend fun fetchFundCatalog(): FundCatalog = catalog
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(extractor: PdfTextExtractor) =
        ImportOrdersViewModel(fakeTransactionRepo, fakePriceRepo, extractor)

    @Test
    fun `parsar och matchar fond via ISIN fran en PDF`() = runTest(dispatcher) {
        findFundByIsinResult = Fund(fundId = "SE0003653302", name = "Nordea Småbolagsfond Sverige", currency = "SEK", isin = "SE0003653302")
        val vm = viewModel(PdfTextExtractor { NORDEA_TEXT })

        vm.uiState.test {
            assertTrue(awaitItem().rows.isEmpty())
            vm.onFilesSelected(listOf("nota.pdf" to ByteArray(0)))
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertEquals(1, state.rows.size)
            val row = state.rows.first()
            assertEquals("SE0003653302", row.matchedFund?.fundId)
            assertEquals(1.0, row.matchConfidence)
            assertEquals("16.9862", row.sharesText)
            assertEquals("294.36", row.priceText)
            assertEquals(LocalDate.of(2020, 3, 13), row.date)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fallback till namnmatchning mot Handelsbankens katalog om ISIN inte kanns igen`() = runTest(dispatcher) {
        val text = """
            Handelsbanken Fonder AB
            Handelsbanken Sverige (A1 SEK)
            ISIN: SE0000582033
            Text Belopp Kurs Andelar Saldo andelar
            In Självbetjäning 2020-03-13 5 000.00 294.36 16.9862 16.9862
        """.trimIndent()
        val vm = viewModel(PdfTextExtractor { text })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(listOf("nota.pdf" to ByteArray(0)))
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertEquals(handelsbankenFund, state.rows.first().matchedFund)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fil som inte kan tolkas alls ger felstate`() = runTest(dispatcher) {
        val vm = viewModel(PdfTextExtractor { "inte en avräkningsnota" })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(listOf("skrap.pdf" to ByteArray(0)))
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertEquals(ImportOrdersError.NONE_PARSED, state.error)
            assertTrue(state.rows.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flera filer samtidigt ger en rad per parsad transaktion`() = runTest(dispatcher) {
        findFundByIsinResult = Fund(fundId = "SE0003653302", name = "Nordea Småbolagsfond Sverige", currency = "SEK", isin = "SE0003653302")
        val extractorPerFile: (String) -> String = { fileName ->
            if (fileName == "unparsable.pdf") "ingen avräkningsnota" else NORDEA_TEXT
        }
        val vm = ImportOrdersViewModel(
            fakeTransactionRepo,
            fakePriceRepo,
            PdfTextExtractor { bytes -> extractorPerFile(String(bytes)) },
        )

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(
                listOf(
                    "nota1.pdf" to "nota1.pdf".toByteArray(),
                    "nota2.pdf" to "nota2.pdf".toByteArray(),
                    "unparsable.pdf" to "unparsable.pdf".toByteArray(),
                ),
            )
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertEquals(2, state.rows.size)
            assertEquals(listOf("unparsable.pdf"), state.unparsedFileNames)
            assertNull(state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `import skapar fond och transaktion fran bekraftade rader`() = runTest(dispatcher) {
        findFundByIsinResult = Fund(fundId = "SE0003653302", name = "Nordea Småbolagsfond Sverige", currency = "SEK", isin = "SE0003653302")
        val vm = viewModel(PdfTextExtractor { NORDEA_TEXT })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(listOf("nota.pdf" to ByteArray(0)))
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            vm.import()
            state = awaitItem()
            while (!state.imported) state = awaitItem()

            assertEquals(1, addedFunds.size)
            assertEquals("SE0003653302", addedFunds.first().isin)
            assertEquals(1, addedTransactions.size)
            val tx = addedTransactions.first()
            assertEquals(TransactionType.KOP, tx.type)
            assertEquals(16.9862, tx.shares, 1e-9)
            assertEquals(294.36, tx.pricePerShare, 1e-9)
            assertEquals(LocalDate.of(2020, 3, 13).toEpochDay(), tx.epochDay)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
