package se.partee71.fonder.worker

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import java.time.LocalDate

/**
 * Ren logik-test av [FundPriceUpdateWorker.refreshAll] — kringgår CoroutineWorker/WorkManager
 * (kräver instrumentering) genom att testa den utbrutna, rena funktionen direkt. Regression för
 * kodgranskningen som fann att ISIN-matchade fonder (t.ex. via findFundByIsin, TP-14) aldrig
 * fick sin dagliga kursuppdatering eftersom `refresh()` nycklas på Handelsbankens FundId, som
 * de fonderna saknar.
 */
class FundPriceUpdateWorkerTest {

    private val funds = MutableStateFlow<List<Fund>>(emptyList())
    private val transactions = MutableStateFlow<List<Transaction>>(emptyList())
    private val refreshedFundIds = mutableListOf<String>()
    private val refreshCalls = mutableListOf<Pair<String, LocalDate?>>()
    private val refreshSinceCalls = mutableListOf<Triple<String, String, LocalDate>>()
    private var refreshResult = true
    private var refreshSinceResult = true

    /** Cachad kurs per fundId — null (standard) = ingen cachad kurs alls, alltid inaktuellt. */
    private val cachedPrices = mutableMapOf<String, FundPrice>()

    private val fakeTransactionRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = funds
        override fun observeTransactions(): Flow<List<Transaction>> = transactions
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> =
            flowOf(transactions.value.filter { it.fundId == fundId })
        override suspend fun upsertFund(fund: Fund) {}
        override suspend fun addTransaction(tx: Transaction): Long = 0
        override suspend fun deleteTransaction(id: Long) {}
        override suspend fun clearAll() {}
    }

    private val fakeFundPriceRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? = cachedPrices[fundId]
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = flowOf(emptyMap())
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice> = emptyList()
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())
        override suspend fun refresh(fundId: String, since: LocalDate?): Boolean {
            refreshedFundIds.add(fundId)
            refreshCalls.add(fundId to since)
            return refreshResult
        }
        override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate): Boolean {
            refreshSinceCalls.add(Triple(fundId, isin, since))
            return refreshSinceResult
        }
        override suspend fun suggestIsin(fundName: String): String? = null
        override suspend fun findFundByIsin(isin: String): Fund? = null
        override suspend fun lookupIsin(fundId: String): String? = null
        override suspend fun fetchFundsForCompany(companyId: String): List<Fund>? = emptyList()
        override suspend fun fetchFundCatalog(): FundCatalog = FundCatalog(emptyList(), emptyList())
    }

    @Test
    fun `fond utan isin uppdateras via refresh`() = runTest {
        funds.value = listOf(Fund(fundId = "SHB0000442", name = "Fond A"))

        val success = FundPriceUpdateWorker.refreshAll(fakeTransactionRepo, fakeFundPriceRepo)

        assertTrue(success)
        assertEquals(listOf("SHB0000442"), refreshedFundIds)
        assertTrue(refreshSinceCalls.isEmpty())
    }

    @Test
    fun `fond med isin uppdateras via refreshSince, inte refresh`() = runTest {
        val since = LocalDate.of(2020, 1, 1)
        val fond = Fund(fundId = "LU0496367417", name = "Franklin Gold", isin = "LU0496367417")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = since.toEpochDay(), shares = 1.0, pricePerShare = 100.0),
        )

        val success = FundPriceUpdateWorker.refreshAll(fakeTransactionRepo, fakeFundPriceRepo)

        assertTrue(success)
        assertTrue(refreshedFundIds.isEmpty())
        assertEquals(listOf(Triple(fond.fundId, fond.isin, since)), refreshSinceCalls)
    }

    @Test
    fun `fond utan kop hamtar utan historikhorisont — bara ett kort farskt fonster`() = runTest {
        // Tidigare gissades ett femårsfönster här. En bevakad men aldrig köpt fond har ingen
        // historik att backfilla mot — den behöver bara en färsk kurs (TP-18, issue #37).
        val fond = Fund(fundId = "LU0496367417", name = "Franklin Gold", isin = "LU0496367417")
        funds.value = listOf(fond)
        // Ingen transaktion (fonden bara bevakad, aldrig köpt) — inget känt inköpsdatum.

        FundPriceUpdateWorker.refreshAll(fakeTransactionRepo, fakeFundPriceRepo)

        assertTrue(refreshSinceCalls.isEmpty())
        assertEquals(fond.fundId to null, refreshCalls.single())
    }

    @Test
    fun `fond utan isin men med kop hamtar med kopdatumet som horisont`() = runTest {
        val since = LocalDate.of(2014, 5, 6)
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = since.toEpochDay(), shares = 1.0, pricePerShare = 100.0),
        )

        FundPriceUpdateWorker.refreshAll(fakeTransactionRepo, fakeFundPriceRepo)

        assertEquals(fond.fundId to since, refreshCalls.single())
    }

    @Test
    fun `inga bevakade fonder ar inte ett fel`() = runTest {
        val success = FundPriceUpdateWorker.refreshAll(fakeTransactionRepo, fakeFundPriceRepo)

        assertTrue(success)
        assertTrue(refreshedFundIds.isEmpty())
        assertTrue(refreshSinceCalls.isEmpty())
    }

    @Test
    fun `om alla fonder misslyckas returneras false for att mojliggora omkorning`() = runTest {
        refreshResult = false
        funds.value = listOf(
            Fund(fundId = "SHB0000442", name = "Fond A"),
            Fund(fundId = "SHB0000443", name = "Fond B"),
        )

        val success = FundPriceUpdateWorker.refreshAll(fakeTransactionRepo, fakeFundPriceRepo)

        assertFalse(success)
    }

    @Test
    fun `om minst en fond lyckas racker det`() = runTest {
        refreshResult = false
        val ok = Fund(fundId = "SHB0000442", name = "Fond A", isin = "SE0004297927")
        val fails = Fund(fundId = "SHB0000443", name = "Fond B")
        funds.value = listOf(ok, fails)
        // `ok` behöver ett köp för att nå refreshSince-grenen (som lyckas); `fails` går via
        // refresh, som är riggad att misslyckas.
        transactions.value = listOf(
            Transaction(fundId = ok.fundId, type = TransactionType.KOP, epochDay = LocalDate.of(2020, 1, 1).toEpochDay(), shares = 1.0, pricePerShare = 100.0),
        )

        val success = FundPriceUpdateWorker.refreshAll(fakeTransactionRepo, fakeFundPriceRepo)

        assertTrue(success)
    }

    // --- Handelsdagsmedveten gating (issue #27, TP-17) ---

    @Test
    fun `fond med redan aktuell kurs hoppas over utan nagon natverksaktivitet`() = runTest {
        // En fond vars cachade kurs redan är aktuell enligt NavCalendar (dagens datum) ska
        // inte trigga en onödig hämtning — gör den periodiska backstopen billig.
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        cachedPrices[fond.fundId] = FundPrice(fundId = fond.fundId, epochDay = LocalDate.now().toEpochDay(), nav = 100.0)

        val success = FundPriceUpdateWorker.refreshAll(fakeTransactionRepo, fakeFundPriceRepo)

        assertTrue(success)
        assertTrue(refreshedFundIds.isEmpty())
        assertTrue(refreshSinceCalls.isEmpty())
    }

    @Test
    fun `bara inaktuella fonder uppdateras, farska fonder hoppas over`() = runTest {
        val farsk = Fund(fundId = "SHB0000442", name = "Fond A")
        val inaktuell = Fund(fundId = "SHB0000443", name = "Fond B")
        funds.value = listOf(farsk, inaktuell)
        cachedPrices[farsk.fundId] = FundPrice(fundId = farsk.fundId, epochDay = LocalDate.now().toEpochDay(), nav = 100.0)
        cachedPrices[inaktuell.fundId] = FundPrice(fundId = inaktuell.fundId, epochDay = LocalDate.now().minusDays(10).toEpochDay(), nav = 100.0)

        val success = FundPriceUpdateWorker.refreshAll(fakeTransactionRepo, fakeFundPriceRepo)

        assertTrue(success)
        assertEquals(listOf(inaktuell.fundId), refreshedFundIds)
    }

    @Test
    fun `force hamtar alla fonder aven de med redan aktuell kurs`() = runTest {
        // Den manuella "Uppdatera nu"-knappen (SET-2) bypassar staleness-gaten.
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        cachedPrices[fond.fundId] = FundPrice(fundId = fond.fundId, epochDay = LocalDate.now().toEpochDay(), nav = 100.0)

        val success = FundPriceUpdateWorker.refreshAll(fakeTransactionRepo, fakeFundPriceRepo, force = true)

        assertTrue(success)
        assertEquals(listOf(fond.fundId), refreshedFundIds)
    }
}
