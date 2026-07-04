package se.partee71.fonder.ui.imports

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
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Transaction
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class ImportHoldingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val addedFunds = mutableListOf<Fund>()
    private val addedTransactions = mutableListOf<Transaction>()
    private var refreshedFundId: String? = null

    private val handelsbankenFund = Fund(fundId = "SHB0000442", name = "Handelsbanken Sverige (A1 SEK)")
    private val catalog = FundCatalog(companies = emptyList(), funds = listOf(handelsbankenFund))

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
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice> =
            listOf(FundPrice(fundId = fundId, epochDay = fromEpochDay, nav = 950.0, currency = "SEK"))
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())
        override suspend fun refresh(fundId: String) {
            refreshedFundId = fundId
        }
        override suspend fun fetchFundCatalog(): FundCatalog = catalog
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun xlsxBytes(sheetXml: String): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheetXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    private val sampleSheetXml = """
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <sheetData>
            <row r="5"><c r="A5" t="inlineStr"><is><t>ISIN</t></is></c></row>
            <row r="6"><c r="A6" t="inlineStr"><is><t>SE0000582033</t></is></c><c r="B6" t="inlineStr"><is><t>Handelsbanken Fonder AB</t></is></c><c r="C6" t="inlineStr"><is><t>Handelsbanken Sverige (A1 SEK)</t></is></c><c r="D6" t="inlineStr"><is><t>1,9378</t></is></c><c r="E6"><v>3949.49</v></c><c r="F6" t="inlineStr"><is><t>SEK</t></is></c><c r="G6" t="inlineStr"><is><t>2026-07-03</t></is></c><c r="H6"><v>7653.32</v></c><c r="I6"><v>1841.0</v></c></row>
            </sheetData>
        </worksheet>
    """.trimIndent()

    @Test
    fun `parsar och matchar fond automatiskt`() = runTest(dispatcher) {
        val vm = ImportHoldingsViewModel(fakeTransactionRepo, fakePriceRepo)
        vm.uiState.test {
            assertFalse(awaitItem().fileSelected)
            vm.onFileSelected(xlsxBytes(sampleSheetXml))
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertEquals(1, state.rows.size)
            assertEquals(handelsbankenFund, state.rows.first().matchedFund)
            assertEquals(1, state.rows.first().occasions.size)
            assertEquals(1.9378, state.rows.first().occasions.first().shares!!, 1e-9)
            assertNull(state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fil som inte ar en zip ger EMPTY_FILE`() = runTest(dispatcher) {
        // ZipInputStream kastar inte på ogiltiga byte — den hittar bara inga poster,
        // så parse() returnerar en tom lista i stället för att kasta.
        val vm = ImportHoldingsViewModel(fakeTransactionRepo, fakePriceRepo)
        vm.uiState.test {
            awaitItem()
            vm.onFileSelected(byteArrayOf(1, 2, 3))
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertEquals(ImportError.EMPTY_FILE, state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `trasig xml i arbetsbladet ger PARSE_FAILED`() = runTest(dispatcher) {
        val vm = ImportHoldingsViewModel(fakeTransactionRepo, fakePriceRepo)
        vm.uiState.test {
            awaitItem()
            vm.onFileSelected(xlsxBytes("<worksheet><sheetData><row>"))
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertEquals(ImportError.PARSE_FAILED, state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fondval och inkopstillfalle kan overridas per rad`() = runTest(dispatcher) {
        val vm = ImportHoldingsViewModel(fakeTransactionRepo, fakePriceRepo)
        vm.uiState.test {
            awaitItem()
            vm.onFileSelected(xlsxBytes(sampleSheetXml))
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            val row = state.rows.first().row

            vm.onFundOverride(row, null)
            state = awaitItem()
            assertNull(state.rows.first().matchedFund)

            vm.onFundOverride(row, handelsbankenFund)
            state = awaitItem()
            assertEquals(handelsbankenFund, state.rows.first().matchedFund)

            val newDate = LocalDate.of(2025, 1, 1)
            vm.onOccasionDateChange(row, 0, newDate)
            state = awaitItem()
            assertEquals(newDate, state.rows.first().occasions.first().date)
            assertTrue(state.rows.first().occasions.first().dateConfident)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `rad kan delas i flera inkopstillfallen`() = runTest(dispatcher) {
        val vm = ImportHoldingsViewModel(fakeTransactionRepo, fakePriceRepo)
        vm.uiState.test {
            awaitItem()
            vm.onFileSelected(xlsxBytes(sampleSheetXml))
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            val row = state.rows.first().row

            vm.onAddOccasion(row)
            state = awaitItem()
            assertEquals(2, state.rows.first().occasions.size)
            // Nya tillfället saknar andelar tills användaren fyller i — blockerar import trots att
            // summan (0 + ursprungliga andelarna) råkar matcha totalt.
            assertFalse(state.rows.first().readyToImport)

            vm.onOccasionSharesChange(row, 0, "1")
            state = awaitItem()
            // Nu är summan (1) för låg jämfört med totalt (1,9378) — riktig avvikelse.
            assertTrue(state.rows.first().sharesMismatch)

            vm.onOccasionSharesChange(row, 1, "0,9378")
            state = awaitItem()
            assertFalse(state.rows.first().sharesMismatch)
            assertTrue(state.rows.first().readyToImport)

            vm.onOccasionDateChange(row, 1, LocalDate.of(2024, 6, 1))
            state = awaitItem()

            vm.onRemoveOccasion(row, 1)
            state = awaitItem()
            assertEquals(1, state.rows.first().occasions.size)
            assertTrue(state.rows.first().sharesMismatch)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `import skapar fond och transaktion for bekraftade rader`() = runTest(dispatcher) {
        val vm = ImportHoldingsViewModel(fakeTransactionRepo, fakePriceRepo)
        vm.uiState.test {
            awaitItem()
            vm.onFileSelected(xlsxBytes(sampleSheetXml))
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            vm.import()
            state = awaitItem()
            while (!state.imported) state = awaitItem()

            assertEquals(1, addedFunds.size)
            assertEquals(handelsbankenFund, addedFunds.first())
            assertEquals(1, addedTransactions.size)
            assertEquals(1.9378, addedTransactions.first().shares, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `import skapar en transaktion per inkopstillfalle`() = runTest(dispatcher) {
        val vm = ImportHoldingsViewModel(fakeTransactionRepo, fakePriceRepo)
        vm.uiState.test {
            awaitItem()
            vm.onFileSelected(xlsxBytes(sampleSheetXml))
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            val row = state.rows.first().row
            val expectedPrice = state.rows.first().row.averageCostPerShare

            vm.onAddOccasion(row)
            state = awaitItem()
            vm.onOccasionSharesChange(row, 0, "1")
            state = awaitItem()
            vm.onOccasionSharesChange(row, 1, "0,9378")
            state = awaitItem()
            val secondDate = LocalDate.of(2024, 6, 1)
            vm.onOccasionDateChange(row, 1, secondDate)
            state = awaitItem()

            vm.import()
            state = awaitItem()
            while (!state.imported) state = awaitItem()

            assertEquals(2, addedTransactions.size)
            assertEquals(1.0, addedTransactions[0].shares, 1e-9)
            assertEquals(0.9378, addedTransactions[1].shares, 1e-9)
            assertEquals(secondDate.toEpochDay(), addedTransactions[1].epochDay)
            assertEquals(expectedPrice, addedTransactions[0].pricePerShare, 1e-9)
            assertEquals(expectedPrice, addedTransactions[1].pricePerShare, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `rad med felaktig andelssumma importeras inte`() = runTest(dispatcher) {
        val vm = ImportHoldingsViewModel(fakeTransactionRepo, fakePriceRepo)
        vm.uiState.test {
            awaitItem()
            vm.onFileSelected(xlsxBytes(sampleSheetXml))
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            val row = state.rows.first().row

            vm.onAddOccasion(row)
            state = awaitItem()
            assertFalse(state.rows.first().readyToImport)
            assertFalse(state.canImport)

            vm.import()
            advanceUntilIdle()

            assertEquals(0, addedTransactions.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `avmarkerad rad importeras inte`() = runTest(dispatcher) {
        val vm = ImportHoldingsViewModel(fakeTransactionRepo, fakePriceRepo)
        vm.uiState.test {
            awaitItem()
            vm.onFileSelected(xlsxBytes(sampleSheetXml))
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            val row = state.rows.first().row

            vm.onIncludedChange(row, false)
            state = awaitItem()
            assertFalse(state.canImport)

            vm.import()
            advanceUntilIdle()

            assertEquals(0, addedTransactions.size)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
