package se.partee71.fonder.worker

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.datastore.PreferencesRepository
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
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import java.time.LocalDate

/**
 * Ren logik-test av [FundPriceUpdateWorker.refreshAll] — kringgår CoroutineWorker/WorkManager
 * (kräver instrumentering) genom att testa den utbrutna, rena funktionen direkt. Regression för
 * kodgranskningen som fann att ISIN-matchade fonder (t.ex. via findFundByIsin, TP-14) aldrig
 * fick sin dagliga kursuppdatering eftersom `refresh()` nycklas på Handelsbankens FundId, som
 * de fonderna saknar.
 */
class FundPriceUpdateWorkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferencesRepository: PreferencesRepository

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(produceFile = { tempFolder.newFile("worker_test.preferences_pb") })
        preferencesRepository = PreferencesRepository(dataStore)
    }

    private val funds = MutableStateFlow<List<Fund>>(emptyList())
    private val transactions = MutableStateFlow<List<Transaction>>(emptyList())
    private val refreshedFundIds = mutableListOf<String>()
    private val refreshCalls = mutableListOf<Pair<String, LocalDate?>>()
    private val refreshSinceCalls = mutableListOf<Triple<String, String, LocalDate>>()
    private var refreshResult = true
    private var refreshSinceResult = true

    /** Cachad kurs per fundId — null (standard) = ingen cachad kurs alls, alltid inaktuellt. */
    private val cachedPrices = mutableMapOf<String, FundPrice>()

    /** ISIN-uppslag för [scanSwitchPlan]s köpkandidat-NAV-upplösning ([FundPriceRepository.findFundByIsin]). */
    private val fundByIsin = mutableMapOf<String, Fund>()

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
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = flowOf(cachedPrices)
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
        override suspend fun findFundByIsin(isin: String): Fund? = fundByIsin[isin]
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

    // --- scanComparisons: inkrementell ifyllnad av billigare-alternativ-jämförelsen (HEM-6, issue #61) ---

    private val metadataByIsin = mutableMapOf<String, FundMetadata>()
    private val suggestCheaperAlternativesCalls = mutableListOf<Pair<String, Double>>()
    private val switchCandidatesByLevel = mutableMapOf<Int, List<SwitchPlanCalc.Candidate>>()

    private val fakeFundMetadataRepo = object : FundMetadataRepository {
        override suspend fun query(query: FundScreenQuery): List<FundMetadata> = emptyList()
        override suspend fun resolveHandelsbankenAvailability(isin: String): Boolean? = null
        override fun observeFilterVocabulary() = flowOf(FundFilterVocabulary())
        override suspend fun knownRiskLevels(): List<Int> = emptyList()
        override suspend fun findSwitchCandidates(level: Int, excludeIsins: Set<String>): List<SwitchPlanCalc.Candidate> =
            switchCandidatesByLevel[level].orEmpty()
        override suspend fun suggestCheaperAlternatives(isin: String, holdingValue: Double): List<FeeComparisonCalc.Alternative>? {
            suggestCheaperAlternativesCalls.add(isin to holdingValue)
            return emptyList()
        }
        override suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata> =
            metadataByIsin.filterKeys { it in isins }
    }

    private fun buy(fundId: String, shares: Double, pricePerShare: Double) = Transaction(
        fundId = fundId, type = TransactionType.KOP,
        epochDay = LocalDate.of(2020, 1, 1).toEpochDay(), shares = shares, pricePerShare = pricePerShare,
    )

    private fun neverScanned(isin: String) = FundMetadata(
        isin = isin, name = isin, orderbookId = isin, totalFee = 0.5, managementFee = 0.5,
        category = null, fundType = null, companyName = null, risk = null, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
    )

    @Test
    fun `scanComparisons gor inget nar inga bevakade fonder finns`() = runTest {
        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)

        assertTrue(suggestCheaperAlternativesCalls.isEmpty())
    }

    @Test
    fun `scanComparisons hoppar over innehav utan isin`() = runTest {
        val utanIsin = Fund(fundId = "UTAN", name = "Utan ISIN")
        val medIsin = Fund(fundId = "MED", name = "Med ISIN", isin = "SE_MED")
        funds.value = listOf(utanIsin, medIsin)
        transactions.value = listOf(buy(utanIsin.fundId, 1.0, 100.0), buy(medIsin.fundId, 1.0, 100.0))
        cachedPrices[utanIsin.fundId] = FundPrice(fundId = utanIsin.fundId, epochDay = LocalDate.now().toEpochDay(), nav = 100.0)
        cachedPrices[medIsin.fundId] = FundPrice(fundId = medIsin.fundId, epochDay = LocalDate.now().toEpochDay(), nav = 100.0)

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)

        assertEquals(listOf("SE_MED"), suggestCheaperAlternativesCalls.map { it.first })
    }

    @Test
    fun `scanComparisons tar hogst tva innehav per korning`() = runTest {
        // Mycket färre än en normal portfölj skulle behöva skannas på en gång — inkrementell
        // ifyllnad i stället för en dyr engångsskanning (issue #61).
        val fundIds = (1..5).map { "F$it" }
        funds.value = fundIds.map { Fund(fundId = it, name = it, isin = "SE_$it") }
        transactions.value = fundIds.map { buy(it, 1.0, 100.0) }
        fundIds.forEach { cachedPrices[it] = FundPrice(fundId = it, epochDay = LocalDate.now().toEpochDay(), nav = 100.0) }

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)

        assertEquals(2, suggestCheaperAlternativesCalls.size)
    }

    @Test
    fun `scanComparisons prioriterar storst innehavsvarde forst`() = runTest {
        val small = Fund(fundId = "SMALL", name = "Small", isin = "SE_SMALL")
        val medium = Fund(fundId = "MEDIUM", name = "Medium", isin = "SE_MEDIUM")
        val large = Fund(fundId = "LARGE", name = "Large", isin = "SE_LARGE")
        funds.value = listOf(small, medium, large)
        transactions.value = listOf(buy(small.fundId, 1.0, 100.0), buy(medium.fundId, 1.0, 100.0), buy(large.fundId, 1.0, 100.0))
        cachedPrices[small.fundId] = FundPrice(fundId = small.fundId, epochDay = LocalDate.now().toEpochDay(), nav = 10.0)
        cachedPrices[medium.fundId] = FundPrice(fundId = medium.fundId, epochDay = LocalDate.now().toEpochDay(), nav = 100.0)
        cachedPrices[large.fundId] = FundPrice(fundId = large.fundId, epochDay = LocalDate.now().toEpochDay(), nav = 1000.0)

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo)

        assertEquals(setOf(large.isin, medium.isin), suggestCheaperAlternativesCalls.map { it.first }.toSet())
    }

    @Test
    fun `scanComparisons hoppar over farska resultat, tar aldrig sokta eller utgangna`() = runTest {
        val today = LocalDate.now()
        val farsk = Fund(fundId = "FARSK", name = "Färsk", isin = "SE_FARSK")
        val utgangen = Fund(fundId = "UTGANGEN", name = "Utgången", isin = "SE_UTGANGEN")
        val aldrigSokt = Fund(fundId = "ALDRIG", name = "Aldrig sökt", isin = "SE_ALDRIG")
        funds.value = listOf(farsk, utgangen, aldrigSokt)
        transactions.value = listOf(buy(farsk.fundId, 1.0, 100.0), buy(utgangen.fundId, 1.0, 100.0), buy(aldrigSokt.fundId, 1.0, 100.0))
        listOf(farsk, utgangen, aldrigSokt).forEach {
            cachedPrices[it.fundId] = FundPrice(fundId = it.fundId, epochDay = today.toEpochDay(), nav = 100.0)
        }
        metadataByIsin["SE_FARSK"] = neverScanned("SE_FARSK").copy(comparisonResolvedAtEpochDay = today.toEpochDay())
        metadataByIsin["SE_UTGANGEN"] = neverScanned("SE_UTGANGEN").copy(
            comparisonResolvedAtEpochDay = today.minusDays(31).toEpochDay(),
        )
        // ALDRIG har ingen fund_metadata-rad alls — aldrig sökt.

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, today)

        assertEquals(setOf("SE_UTGANGEN", "SE_ALDRIG"), suggestCheaperAlternativesCalls.map { it.first }.toSet())
    }

    // --- scanSwitchPlan: bytesplanens facit-inspelning (HEM-8, issue #70) ---

    private class FakeSuggestionRecordRepository : SuggestionRecordRepository {
        val recorded = mutableListOf<SuggestionRecord>()
        private val recordedDays = mutableSetOf<Triple<String, String, Long>>()
        override fun observeAll(): Flow<List<SuggestionRecord>> = flowOf(recorded)
        override suspend fun hasRecordedToday(sellIsin: String, buyIsin: String, epochDay: Long): Boolean =
            Triple(sellIsin, buyIsin, epochDay) in recordedDays
        override suspend fun record(record: SuggestionRecord) {
            recorded += record
            recordedDays += Triple(record.sellIsin, record.buyIsin, record.suggestedAtEpochDay)
        }
    }

    private fun heldMetadata(isin: String, risk: Int, totalFee: Double) = FundMetadata(
        isin = isin, name = isin, orderbookId = isin, totalFee = totalFee, managementFee = totalFee,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
    )

    private fun candidateMetadata(isin: String, risk: Int, totalFee: Double, developmentOneYear: Double) = FundMetadata(
        isin = isin, name = "Kandidat $isin", orderbookId = isin, totalFee = totalFee, managementFee = totalFee,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(), developmentOneYear = developmentOneYear,
    )

    private fun setUpOverweightedPortfolio() {
        val held = Fund(fundId = "HELD", name = "Innehav", isin = "SE_HELD")
        funds.value = listOf(held)
        transactions.value = listOf(buy(held.fundId, 100.0, 100.0)) // 10 000 kr, allt på nivå 5
        cachedPrices[held.fundId] = FundPrice(fundId = held.fundId, epochDay = LocalDate.now().toEpochDay(), nav = 100.0)
        metadataByIsin["SE_HELD"] = heldMetadata("SE_HELD", risk = 5, totalFee = 1.0)
        // Målet är 100 % nivå 3 — hela innehavet är alltså överviktat på nivå 5.
        switchCandidatesByLevel[3] = listOf(SwitchPlanCalc.Candidate(candidateMetadata("SE_CAND", risk = 3, totalFee = 0.3, developmentOneYear = 0.1), 0.1))
        fundByIsin["SE_CAND"] = Fund(fundId = "SE_CAND", name = "Kandidat SE_CAND", isin = "SE_CAND")
        cachedPrices["SE_CAND"] = FundPrice(fundId = "SE_CAND", epochDay = LocalDate.now().toEpochDay(), nav = 50.0)
    }

    @Test
    fun `scanSwitchPlan gor inget utan vald kontotyp`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.scanSwitchPlan(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository, suggestionRepo)

        assertTrue(suggestionRepo.recorded.isEmpty())
    }

    @Test
    fun `scanSwitchPlan gor inget i depa-AF`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.DEPA_AF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.scanSwitchPlan(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository, suggestionRepo)

        assertTrue(suggestionRepo.recorded.isEmpty())
    }

    @Test
    fun `scanSwitchPlan gor inget utan sparad riskprofil`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.scanSwitchPlan(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository, suggestionRepo)

        assertTrue(suggestionRepo.recorded.isEmpty())
    }

    @Test
    fun `scanSwitchPlan spelar in ett byte med NAV-utgangslage i ISK-KF`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.scanSwitchPlan(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository, suggestionRepo, LocalDate.of(2026, 1, 1))

        val record = suggestionRepo.recorded.single()
        assertEquals("SE_HELD", record.sellIsin)
        assertEquals("SE_CAND", record.buyIsin)
        assertEquals(0, record.planIndex)
        assertEquals(100.0, record.sellNavAtSuggestion, 1e-9)
        assertEquals(50.0, record.buyNavAtSuggestion, 1e-9)
        assertEquals(LocalDate.of(2026, 1, 1).toEpochDay(), record.suggestedAtEpochDay)
    }

    @Test
    fun `scanSwitchPlan spelar inte in samma byte tva ganger samma dag`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()
        val today = LocalDate.of(2026, 1, 1)

        FundPriceUpdateWorker.scanSwitchPlan(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository, suggestionRepo, today)
        FundPriceUpdateWorker.scanSwitchPlan(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository, suggestionRepo, today)

        assertEquals(1, suggestionRepo.recorded.size)
    }

    @Test
    fun `scanSwitchPlan gor inget utan bevakade fonder`() = runTest {
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.scanSwitchPlan(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository, suggestionRepo)

        assertTrue(suggestionRepo.recorded.isEmpty())
    }
}
