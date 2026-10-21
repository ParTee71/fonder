package se.partee71.fonder.worker

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.datastore.BenchmarkComponentRef
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FakeSuggestionDismissalRepository
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
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.IndexBenchmarkSelector
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import se.partee71.fonder.testing.TestDays

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

    /**
     * Kurser som **ISIN-källkedjan** kan leverera, per ISIN — speglar produktionens
     * ansvarsfördelning: `refreshSince` går via ISIN-kedjan och når fonder utan
     * Handelsbanken-FundId, medan `refresh` frågar fondlista med `fundId` rakt av och därför
     * aldrig ger någon kurs för en fond vars identitet är ett ISIN (issue #75, punkt 2).
     */
    private val isinChainPrices = mutableMapOf<String, FundPrice>()

    private val fakeTransactionRepo = object : TransactionRepository {
        override fun observeFunds(): Flow<List<Fund>> = funds
        override fun observeTransactions(): Flow<List<Transaction>> = transactions
        override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = flowOf(transactions.value.filter { it.fundId == fundId })
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
            isinChainPrices[isin]?.let { cachedPrices[fundId] = it.copy(fundId = fundId) }
            return refreshSinceResult
        }
        override suspend fun historyForIsin(isin: String, from: LocalDate, to: LocalDate): List<FundPrice> = emptyList()
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
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = since.toEpochDay(), shares = 1.0, pricePerShare = 100.0)
        )

        val success = FundPriceUpdateWorker.refreshAll(fakeTransactionRepo, fakeFundPriceRepo)

        assertTrue(success)
        assertTrue(refreshedFundIds.isEmpty())
        assertEquals(listOf(Triple(fond.fundId, fond.isin, since)), refreshSinceCalls)
    }

    @Test
    fun `fond med isin men utan kop gar via ISIN-kedjan, inte fondlista med isinet som fondnyckel`() = runTest {
        // Regression (issue #75, fynd F): grenen var villkorad på `since != null`, så en fond
        // vars fundId *är* dess ISIN (findFundByIsin-vägen, TP-13/TP-14) men som saknar
        // transaktioner föll ner i refresh(fundId) — där fondlista frågas med ISIN:et som
        // fondnyckel utan ISIN-fallback. Den fonden fick aldrig någon kurs. Metodens egen KDoc
        // sa redan att ISIN-fonder *måste* gå via refreshSince; testet låste fast fel gren.
        // Historikhorisonten är analysens (issue #214): tre år bakåt även för en bevakad men
        // aldrig köpt fond, vars snittlinje och signaler handlar om fondens kurs — tidigare ett
        // kort färskt fönster (TP-18, issue #37).
        val days = TestDays()
        val fond = Fund(fundId = "LU0496367417", name = "Franklin Gold", isin = "LU0496367417")
        funds.value = listOf(fond)
        // Ingen transaktion (fonden bara bevakad, aldrig köpt) — inget känt inköpsdatum.

        FundPriceUpdateWorker.refreshAll(fakeTransactionRepo, fakeFundPriceRepo)

        assertTrue(refreshCalls.isEmpty())
        val (fundId, isin, since) = refreshSinceCalls.single()
        assertEquals(fond.fundId, fundId)
        assertEquals(fond.isin, isin)
        days.assertForTestDay(since) { FundAnalysisCalc.analysisHistoryStart(null, it) }
    }

    @Test
    fun `fond utan isin men med kop hamtar med kopdatumet som horisont`() = runTest {
        val since = LocalDate.of(2014, 5, 6)
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = since.toEpochDay(), shares = 1.0, pricePerShare = 100.0)
        )

        FundPriceUpdateWorker.refreshAll(fakeTransactionRepo, fakeFundPriceRepo)

        assertEquals(fond.fundId to since, refreshCalls.single())
    }

    @Test
    fun `nyss kopt fond hamtar analyshistoriken tre ar bakat, inte bara fran kopet (issue 214)`() = runTest {
        // S2/snittlinjen kräver 290 dagar och #195 tre år — samma horisont som skärmarna läser.
        val days = TestDays()
        val purchase = days.today.minusMonths(3)
        val fond = Fund(fundId = "SHB0000442", name = "Fond A")
        funds.value = listOf(fond)
        transactions.value = listOf(
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = purchase.toEpochDay(), shares = 1.0, pricePerShare = 100.0)
        )

        FundPriceUpdateWorker.refreshAll(fakeTransactionRepo, fakeFundPriceRepo)

        days.assertForTestDay(refreshCalls.single()) { fond.fundId to it.minusDays(FundAnalysisCalc.ANALYSIS_LOOKBACK_DAYS) }
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
            Fund(fundId = "SHB0000443", name = "Fond B")
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
            Transaction(fundId = ok.fundId, type = TransactionType.KOP, epochDay = LocalDate.of(2020, 1, 1).toEpochDay(), shares = 1.0, pricePerShare = 100.0)
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

    /** Jämförelsens svar per ISIN — tom lista (inget billigare) om inget riggats, null = kunde inte jämföras. */
    private val cheaperAlternativesByIsin = mutableMapOf<String, List<FeeComparisonCalc.Alternative>?>()
    private val switchCandidatesByLevel = mutableMapOf<Int, List<SwitchPlanCalc.Candidate>>()

    /** Nivåerna källan faktiskt frågades om — budgeten i KEY_SCAN_SWITCH_PLAN mäts på dem. */
    private val switchCandidateLevelsQueried = mutableListOf<Int>()

    /** Låter kandidatfrågan ta (virtuell) tid, så två skanningar kan överlappa i testet. */
    private var switchCandidateDelayMillis = 0L

    /** Uppföljare workern köat — med force-flaggan. */
    private val followUps = mutableListOf<Boolean>()

    /** Kandidater källan svarar med på en fondfråga — riggas av referensfondstesterna (HEM-10). */
    private var queryResult: List<FundMetadata> = emptyList()

    /** Frågorna som faktiskt gick till källan — budgeten i KEY_SCAN_BENCHMARK mäts på dem. */
    private val queriesRun = mutableListOf<FundScreenQuery>()

    private val fakeFundMetadataRepo = object : FundMetadataRepository {
        override suspend fun query(query: FundScreenQuery): List<FundMetadata> {
            queriesRun.add(query)
            return queryResult
        }
        override suspend fun resolveHandelsbankenAvailability(isin: String): Boolean? = null
        override fun observeFilterVocabulary() = flowOf(FundFilterVocabulary())
        override suspend fun knownRiskLevels(): List<Int> = emptyList()
        override suspend fun findSwitchCandidates(level: Int, excludeIsins: Set<String>): List<SwitchPlanCalc.Candidate> {
            switchCandidateLevelsQueried.add(level)
            if (switchCandidateDelayMillis > 0) delay(switchCandidateDelayMillis)
            return switchCandidatesByLevel[level].orEmpty()
        }
        override suspend fun suggestCheaperAlternatives(isin: String, holdingValue: Double): List<FeeComparisonCalc.Alternative>? {
            suggestCheaperAlternativesCalls.add(isin to holdingValue)
            return if (isin in cheaperAlternativesByIsin) cheaperAlternativesByIsin[isin] else emptyList()
        }
        override suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata> = metadataByIsin.filterKeys { it in isins }
        override suspend fun cachedRiskByFundName(): Map<String, Int> = emptyMap()
        override suspend fun cachedMetadataFor(isins: List<String>): Map<String, FundMetadata> = metadataByIsin.filterKeys { it in isins }
    }

    private fun buy(fundId: String, shares: Double, pricePerShare: Double) = Transaction(
        fundId = fundId,
        type = TransactionType.KOP,
        epochDay = LocalDate.of(2020, 1, 1).toEpochDay(),
        shares = shares,
        pricePerShare = pricePerShare
    )

    private fun neverScanned(isin: String) = FundMetadata(
        isin = isin, name = isin, orderbookId = isin, totalFee = 0.5, managementFee = 0.5,
        category = null, fundType = null, companyName = null, risk = null, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList()
    )

    @Test
    fun `scanComparisons gor inget nar inga bevakade fonder finns`() = runTest {
        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, FakeSuggestionRecordRepository())

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

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, FakeSuggestionRecordRepository())

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

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, FakeSuggestionRecordRepository())

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

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, FakeSuggestionRecordRepository())

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
            comparisonResolvedAtEpochDay = today.minusDays(31).toEpochDay()
        )
        // ALDRIG har ingen fund_metadata-rad alls — aldrig sökt.

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, FakeSuggestionRecordRepository(), today)

        assertEquals(setOf("SE_UTGANGEN", "SE_ALDRIG"), suggestCheaperAlternativesCalls.map { it.first }.toSet())
    }

    // --- scanComparisons: avgiftsbytets facit-inspelning (ANA-9/SET-5, issue #91) ---

    /** Innehavet HELD (SE_HELD): 100 andelar à 100 kr = 10 000 kr på risknivå 5, avgift 1 %. */
    private fun setUpHeldFund() {
        val held = Fund(fundId = "HELD", name = "Innehav", isin = "SE_HELD")
        funds.value = listOf(held)
        transactions.value = listOf(buy(held.fundId, 100.0, 100.0))
        cachedPrices[held.fundId] = FundPrice(fundId = held.fundId, epochDay = LocalDate.now().toEpochDay(), nav = 100.0)
        metadataByIsin["SE_HELD"] = heldMetadata("SE_HELD", risk = 5, totalFee = 1.0)
    }

    /** Ett innehav till på risknivå 5 med ISIN "SE_[fundId]", köpt till och värderat på kursen 100. */
    private fun addLevelFiveHolding(fundId: String, name: String, totalFee: Double, shares: Double, day: LocalDate) {
        val isin = "SE_$fundId"
        val fund = Fund(fundId = fundId, name = name, isin = isin)
        funds.value = funds.value + fund
        transactions.value = transactions.value + buy(fund.fundId, shares, 100.0)
        cachedPrices[fund.fundId] = FundPrice(fundId = fund.fundId, epochDay = day.toEpochDay(), nav = 100.0)
        metadataByIsin[isin] = heldMetadata(isin, risk = 5, totalFee = totalFee)
    }

    /**
     * En andra kandidat SE_CAND2 på nivå 3 (avgift 0,4 %). Med [priceDay] finns dess kurs 40 via
     * ISIN-kedjan; utan känner ingen källa den, och raden kan inte spelas in.
     */
    private fun addSecondCandidate(priceDay: LocalDate?) {
        switchCandidatesByLevel[3] = switchCandidatesByLevel.getValue(3) +
            SwitchPlanCalc.Candidate(candidateMetadata("SE_CAND2", risk = 3, totalFee = 0.4, developmentOneYear = 0.1), 0.1)
        if (priceDay == null) return
        fundByIsin["SE_CAND2"] = Fund(fundId = "SE_CAND2", name = "Kandidat SE_CAND2", isin = "SE_CAND2")
        isinChainPrices["SE_CAND2"] = FundPrice(fundId = "SE_CAND2", epochDay = priceDay.toEpochDay(), nav = 40.0)
    }

    /**
     * Ett innehav på 10 000 kr med ett billigare, likvärdigt alternativ. Kandidatens kurs finns
     * bara via ISIN-kedjan, precis som skarpt — den fonden ägs inte av appen och har inget
     * Handelsbanken-FundId.
     */
    private fun setUpHoldingWithCheaperAlternative(altNav: Double? = 50.0) {
        setUpHeldFund()
        cheaperAlternativesByIsin["SE_HELD"] = listOf(
            FeeComparisonCalc.Alternative(
                candidate = candidateMetadata("SE_BILLIG", risk = 5, totalFee = 0.2, developmentOneYear = 0.1),
                candidateFeePercent = 0.2,
                annualSavingsKr = 80.0
            ),
            FeeComparisonCalc.Alternative(
                candidate = candidateMetadata("SE_NASTBILLIG", risk = 5, totalFee = 0.4, developmentOneYear = 0.1),
                candidateFeePercent = 0.4,
                annualSavingsKr = 60.0
            )
        )
        fundByIsin["SE_BILLIG"] = Fund(fundId = "SE_BILLIG", name = "Billig", isin = "SE_BILLIG")
        fundByIsin["SE_NASTBILLIG"] = Fund(fundId = "SE_NASTBILLIG", name = "Näst billigast", isin = "SE_NASTBILLIG")
        altNav?.let { isinChainPrices["SE_BILLIG"] = FundPrice(fundId = "SE_BILLIG", epochDay = LocalDate.now().toEpochDay(), nav = it) }
        isinChainPrices["SE_NASTBILLIG"] = FundPrice(fundId = "SE_NASTBILLIG", epochDay = LocalDate.now().toEpochDay(), nav = 25.0)
    }

    @Test
    fun `scanComparisons spelar in varje visat alternativ med NAV-utgangslage`() = runTest {
        setUpHoldingWithCheaperAlternative()
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.scanComparisons(
            fakeTransactionRepo,
            fakeFundPriceRepo,
            fakeFundMetadataRepo,
            suggestionRepo,
            LocalDate.of(2026, 1, 1)
        )

        // Alla visade alternativ, inte bara det billigaste (issue #93): de är varandras
        // alternativ, och vilket som helst kan vara det användaren faktiskt byter till.
        assertEquals(listOf("SE_BILLIG", "SE_NASTBILLIG"), suggestionRepo.recorded.map { it.buyIsin })
        assertTrue(suggestionRepo.recorded.all { it.kind == SuggestionKind.FEE })
        assertTrue(suggestionRepo.recorded.all { it.sellIsin == "SE_HELD" })

        val best = suggestionRepo.recorded.first()
        assertEquals(100.0, best.sellNavAtSuggestion, 1e-9)
        assertEquals(50.0, best.buyNavAtSuggestion, 1e-9)
        // Ett avgiftsbyte avser hela positionen, till skillnad från planens gap-storlek.
        assertEquals(10_000.0, best.switchValueKr!!, 1e-9)
        assertEquals(LocalDate.of(2026, 1, 1).toEpochDay(), best.suggestedAtEpochDay)
        assertEquals(25.0, suggestionRepo.recorded[1].buyNavAtSuggestion, 1e-9)
    }

    @Test
    fun `scanComparisons spelar in aven for ett innehav vars jamforelse redan ar farsk`() = runTest {
        // Regression för issue #93: fondkortet kör samma jämförelse vid varje skärmöppning och
        // stämplar comparisonResolvedAtEpochDay, så hängde inspelningen på omskanningens urval
        // blev just de fonder användaren tittar på permanent överhoppade — kryssrutan dök
        // aldrig upp för dem. Ett råd spelas in för att det gavs, inte för att det räknades om.
        val today = LocalDate.of(2026, 1, 1)
        setUpHoldingWithCheaperAlternative()
        metadataByIsin["SE_HELD"] = heldMetadata("SE_HELD", risk = 5, totalFee = 1.0).copy(
            comparisonResolvedAtEpochDay = today.toEpochDay(),
            shownAlternativeIsins = listOf("SE_BILLIG", "SE_NASTBILLIG")
        )
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, suggestionRepo, today)

        // Ingen dyr omskanning — resultatet är färskt.
        assertTrue(suggestCheaperAlternativesCalls.isEmpty())
        // …men raderna spelas in ur den sparade listan.
        assertEquals(listOf("SE_BILLIG", "SE_NASTBILLIG"), suggestionRepo.recorded.map { it.buyIsin })
    }

    @Test
    fun `scanComparisons spelar in omskanningens farska kandidater, inte den sparade listan`() = runTest {
        // Den sparade listan lästes innan körningen skrev något. Hade den använts för ett
        // innehav som just skannats om hade gårdagens kandidater spelats in som dagens råd.
        setUpHoldingWithCheaperAlternative()
        metadataByIsin["SE_HELD"] = heldMetadata("SE_HELD", risk = 5, totalFee = 1.0).copy(
            shownAlternativeIsins = listOf("SE_GAMMAL")
        )
        fundByIsin["SE_GAMMAL"] = Fund(fundId = "SE_GAMMAL", name = "Gammal", isin = "SE_GAMMAL")
        isinChainPrices["SE_GAMMAL"] = FundPrice(fundId = "SE_GAMMAL", epochDay = LocalDate.now().toEpochDay(), nav = 10.0)
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, suggestionRepo)

        assertEquals(listOf("SE_BILLIG", "SE_NASTBILLIG"), suggestionRepo.recorded.map { it.buyIsin })
    }

    @Test
    fun `scanComparisons spelar inte in nagot nar jamforelsen inte kunde goras`() = runTest {
        // null = fonden saknar känd avgift eller källan svarade inte. Den sparade listan hör
        // till ett resultat körningen just försökte ersätta och får inte läsas som dagens råd.
        setUpHoldingWithCheaperAlternative()
        cheaperAlternativesByIsin["SE_HELD"] = null
        metadataByIsin["SE_HELD"] = heldMetadata("SE_HELD", risk = 5, totalFee = 1.0).copy(
            shownAlternativeIsins = listOf("SE_BILLIG")
        )
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, suggestionRepo)

        assertTrue(suggestionRepo.recorded.isEmpty())
    }

    @Test
    fun `alternativ utan hamtbar kurs hoppas over, ovriga spelas in`() = runTest {
        // Utfallet mäts mot just de två kurserna — en rad utan köpkursen kan aldrig utvärderas,
        // men skulle ändå räknas som ett givet råd och späda ut facit. Att en kandidat faller
        // bort får däremot inte ta med sig de andra.
        setUpHoldingWithCheaperAlternative(altNav = null)
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, suggestionRepo)

        assertEquals(listOf("SE_NASTBILLIG"), suggestionRepo.recorded.map { it.buyIsin })
    }

    @Test
    fun `scanComparisons spelar inte in nagot utan billigare alternativ`() = runTest {
        setUpHoldingWithCheaperAlternative()
        cheaperAlternativesByIsin["SE_HELD"] = emptyList()
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, suggestionRepo)

        assertTrue(suggestionRepo.recorded.isEmpty())
    }

    @Test
    fun `scanComparisons spelar in samma avgiftsbyte hogst en gang per dygn`() = runTest {
        setUpHoldingWithCheaperAlternative()
        val suggestionRepo = FakeSuggestionRecordRepository()
        val today = LocalDate.of(2026, 1, 1)

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, suggestionRepo, today)
        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, suggestionRepo, today)

        assertEquals(2, suggestionRepo.recorded.size)
    }

    /**
     * Nio innehav F1…F9 med fallande värde (F1 störst), vart och ett med ett alternativ ALT_F<n> i en
     * färsk jämförelse — ingen omskanning, bara inspelning ur den sparade listan.
     */
    private fun setUpNineHoldingsWithFreshComparisons(today: LocalDate) {
        val fundList = (1..9).map { Fund(fundId = "F$it", name = "F$it", isin = "SE_F$it") }
        funds.value = fundList
        transactions.value = fundList.mapIndexed { index, fund -> buy(fund.fundId, (100 - index).toDouble(), 100.0) }
        fundList.forEach { cachedPrices[it.fundId] = FundPrice(fundId = it.fundId, epochDay = today.toEpochDay(), nav = 100.0) }
        fundList.forEach { fund ->
            val isin = fund.isin!!
            val alt = "ALT_${fund.fundId}"
            metadataByIsin[isin] = heldMetadata(isin, risk = 5, totalFee = 1.0).copy(
                comparisonResolvedAtEpochDay = today.toEpochDay(),
                shownAlternativeIsins = listOf(alt)
            )
            fundByIsin[alt] = Fund(fundId = alt, name = alt, isin = alt)
            isinChainPrices[alt] = FundPrice(fundId = alt, epochDay = today.toEpochDay(), nav = 50.0)
        }
    }

    @Test
    fun `redan inspelade rader tar ingen plats i inspelningsbudgeten`() = runTest {
        // Sex innehav med ett alternativ vardera fyller budgeten exakt. Är de tre största redan
        // inspelade ska de tre minsta komma med i samma körning — annars hade ett innehav som
        // ligger långt ner i värdeordningen aldrig hunnit med, trots att budgeten var oanvänd.
        val today = LocalDate.of(2026, 1, 1)
        setUpNineHoldingsWithFreshComparisons(today)
        val suggestionRepo = FakeSuggestionRecordRepository()
        // De tre största är redan inspelade i dag.
        listOf("SE_F1", "SE_F2", "SE_F3").forEach { isin ->
            suggestionRepo.record(
                SuggestionRecord(
                    suggestedAtEpochDay = today.toEpochDay(),
                    planIndex = 0,
                    sellIsin = isin,
                    buyIsin = "ALT_${isin.removePrefix("SE_")}",
                    sellNavAtSuggestion = 100.0,
                    buyNavAtSuggestion = 50.0,
                    kind = SuggestionKind.FEE
                )
            )
        }

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, suggestionRepo, today)

        // Budgeten är 6 rader: F4…F9 spelas in, F1–F3 hoppas över utan att kosta något.
        assertEquals(
            (4..9).map { "SE_F$it" },
            suggestionRepo.recorded.drop(3).map { it.sellIsin }
        )
    }

    @Test
    fun `inspelningen ar budgeterad per korning`() = runTest {
        val today = LocalDate.of(2026, 1, 1)
        setUpNineHoldingsWithFreshComparisons(today)
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, suggestionRepo, today)

        // Störst innehavsvärde först — resten kommer med nästa körning.
        assertEquals((1..6).map { "SE_F$it" }, suggestionRepo.recorded.map { it.sellIsin })
    }

    @Test
    fun `ett inspelat riskplansbyte blockerar inte avgiftsbytet for samma fondpar`() = runTest {
        // Dedupspärren nycklas på sorten (issue #91). Utan den hade facit tyst tappat halva
        // historien för ett fondpar som legitimt föreslås av båda källorna.
        setUpHoldingWithCheaperAlternative()
        val suggestionRepo = FakeSuggestionRecordRepository()
        val today = LocalDate.of(2026, 1, 1)
        suggestionRepo.record(
            SuggestionRecord(
                suggestedAtEpochDay = today.toEpochDay(),
                planIndex = 0,
                sellIsin = "SE_HELD",
                buyIsin = "SE_BILLIG",
                sellNavAtSuggestion = 100.0,
                buyNavAtSuggestion = 50.0,
                kind = SuggestionKind.RISK_PLAN
            )
        )

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, suggestionRepo, today)

        assertEquals(
            listOf(SuggestionKind.RISK_PLAN, SuggestionKind.FEE, SuggestionKind.FEE),
            suggestionRepo.recorded.map { it.kind }
        )
        assertEquals(listOf("SE_BILLIG", "SE_NASTBILLIG"), suggestionRepo.recorded.drop(1).map { it.buyIsin })
    }

    // --- scanSwitchPlan: bytesplanens facit-inspelning (HEM-8, issue #70) ---

    private class FakeSuggestionRecordRepository : SuggestionRecordRepository {
        val recorded = mutableListOf<SuggestionRecord>()

        /**
         * Dedupnyckeln inkluderar [SuggestionKind] (issue #91) — precis som DAO:ns
         * `existsForDay`. Utan typen i nyckeln hade fakeen svarat "redan inspelad" för ett
         * avgiftsbyte bara för att samma fondpar fanns som riskplansbyte, och testet hade
         * bevisat en spärr produktionen inte har.
         */
        private val recordedDays = mutableSetOf<List<Any>>()

        /** Som DAO:n: senaste dygnets senaste körning, i planordning — skanningen jämför sin plan mot den. */
        override fun observeLatestBatch(): Flow<List<SuggestionRecord>> {
            val plan = recorded.filter { it.kind == SuggestionKind.RISK_PLAN }
            if (plan.isEmpty()) return flowOf(emptyList())
            val latestDay = plan.maxOf { it.suggestedAtEpochDay }
            val sameDay = plan.filter { it.suggestedAtEpochDay == latestDay }
            val latestBatch = sameDay.maxOf { it.batchEpochMillis }
            return flowOf(sameDay.filter { it.batchEpochMillis == latestBatch }.sortedBy { it.planIndex })
        }

        var prunedAt: LocalDate? = null
        override suspend fun prune(today: LocalDate) {
            prunedAt = today
        }
        override suspend fun hasRecordedToday(sellIsin: String, buyIsin: String, epochDay: Long, kind: SuggestionKind): Boolean = listOf(sellIsin, buyIsin, epochDay, kind) in recordedDays
        override suspend fun record(record: SuggestionRecord) {
            // Löpande id som Room — flytten mellan körningar (moveToBatch) nycklar på det.
            recorded += record.copy(id = (recorded.maxOfOrNull { it.id } ?: 0L) + 1)
            recordedDays += listOf(record.sellIsin, record.buyIsin, record.suggestedAtEpochDay, record.kind)
        }
        override suspend fun commitBatch(moves: List<SuggestionRecordRepository.BatchMove>, records: List<SuggestionRecord>) {
            moves.forEach { move ->
                val index = recorded.indexOfFirst { it.id == move.id }
                recorded[index] = recorded[index].copy(batchEpochMillis = move.batchEpochMillis, planIndex = move.planIndex, switchValueKr = move.switchValueKr)
            }
            records.forEach { record(it) }
        }
        override fun observeHistory(): Flow<List<SuggestionRecord>> = flowOf(
            recorded.sortedWith(compareByDescending<SuggestionRecord> { it.suggestedAtEpochDay }.thenByDescending { it.batchEpochMillis }.thenBy { it.planIndex })
        )

        /** Workern skriver aldrig markeringen — den ägs av användaren (SET-5, issue #80). */
        override suspend fun setFollowed(id: Long, followed: Boolean) = Unit
    }

    private val switchWatchRepo = FakeSwitchWatchRepository()
    private val dismissalRepo = FakeSuggestionDismissalRepository()

    /** [FundPriceUpdateWorker.scanSwitchPlan] med testets fakes — avståenden riggas på [dismissalRepo]. */
    private suspend fun scanSwitchPlan(
        suggestionRepo: SuggestionRecordRepository,
        today: LocalDate = LocalDate.now(),
        batchEpochMillis: Long = System.currentTimeMillis()
    ) = FundPriceUpdateWorker.scanSwitchPlan(
        fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository, suggestionRepo,
        dismissalRepo, switchWatchRepo, calendar = { today }, clock = { batchEpochMillis }
    )

    private fun heldMetadata(isin: String, risk: Int, totalFee: Double) = FundMetadata(
        isin = isin, name = isin, orderbookId = isin, totalFee = totalFee, managementFee = totalFee,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList()
    )

    private fun candidateMetadata(isin: String, risk: Int, totalFee: Double, developmentOneYear: Double) = FundMetadata(
        isin = isin, name = "Kandidat $isin", orderbookId = isin, totalFee = totalFee, managementFee = totalFee,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(), developmentOneYear = developmentOneYear
    )

    private fun setUpOverweightedPortfolio() {
        setUpHeldFund()
        // Målet är 100 % nivå 3 — hela innehavet är alltså överviktat på nivå 5.
        switchCandidatesByLevel[3] = listOf(SwitchPlanCalc.Candidate(candidateMetadata("SE_CAND", risk = 3, totalFee = 0.3, developmentOneYear = 0.1), 0.1))
        fundByIsin["SE_CAND"] = Fund(fundId = "SE_CAND", name = "Kandidat SE_CAND", isin = "SE_CAND")
        // Kandidatens kurs finns bara via ISIN-kedjan — precis som skarpt, där fonden saknar
        // Handelsbanken-FundId. Förseedas den i cachen döljs buggen i issue #75, punkt 2.
        isinChainPrices["SE_CAND"] = FundPrice(fundId = "SE_CAND", epochDay = LocalDate.now().toEpochDay(), nav = 50.0)
    }

    @Test
    fun `scanSwitchPlan gor inget utan vald kontotyp`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        scanSwitchPlan(suggestionRepo)

        assertTrue(suggestionRepo.recorded.isEmpty())
    }

    @Test
    fun `scanSwitchPlan gor inget i depa-AF`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.DEPA_AF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        scanSwitchPlan(suggestionRepo)

        assertTrue(suggestionRepo.recorded.isEmpty())
    }

    @Test
    fun `scanSwitchPlan gor inget utan sparad riskprofil`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        val suggestionRepo = FakeSuggestionRecordRepository()

        scanSwitchPlan(suggestionRepo)

        assertTrue(suggestionRepo.recorded.isEmpty())
    }

    @Test
    fun `scanSwitchPlan spelar in ett byte med NAV-utgangslage i ISK-KF`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        scanSwitchPlan(suggestionRepo, LocalDate.of(2026, 1, 1))

        val record = suggestionRepo.recorded.single()
        assertEquals("SE_HELD", record.sellIsin)
        assertEquals("SE_CAND", record.buyIsin)
        assertEquals(0, record.planIndex)
        assertEquals(100.0, record.sellNavAtSuggestion, 1e-9)
        assertEquals(50.0, record.buyNavAtSuggestion, 1e-9)
        assertEquals(LocalDate.of(2026, 1, 1).toEpochDay(), record.suggestedAtEpochDay)
        // Hela innehavet är överviktat (mål 100 % nivå 3), så bytet omfattar hela 10 000 kr.
        assertEquals(10_000.0, record.switchValueKr ?: -1.0, 1e-9)
    }

    @Test
    fun `runScans hoppar over bada skanningarna nar den forcerade kursuppdateringen misslyckades`() = runTest {
        // Regression (issue #75, fynd E): skanningarna kördes villkorslöst efter refreshAll, även
        // när körningen var på väg att returnera Result.retry(). scanSwitchPlan skrev då en
        // SuggestionRecord med ett NAV-utgångsläge ur en cache som just visat sig inaktuell —
        // ett korrumperat facit som inte går att rätta i efterhand.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        runScans(
            refreshSucceeded = false,
            refreshForced = true,
            scanComparisons = true,
            scanSwitchPlan = true,
            suggestionRepo = suggestionRepo
        )

        assertTrue("inget förslag får spelas in på inaktuella kurser", suggestionRepo.recorded.isEmpty())
        assertTrue("ingen jämförelse får köras heller", suggestCheaperAlternativesCalls.isEmpty())
    }

    @Test
    fun `planen raknas pa cachen nar en icke-forcerad uppdatering misslyckades - ovriga skanningar vantar`() = runTest {
        // En avregistrerad fond är inaktuell för alltid, så den icke-forcerade hämtningen "misslyckas"
        // vid varje försök — hade det stoppat planen räknades den aldrig om (retry för evigt).
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        runScans(
            refreshSucceeded = false,
            refreshForced = false,
            scanComparisons = true,
            scanSwitchPlan = true,
            suggestionRepo = suggestionRepo
        )

        assertEquals(1, suggestionRepo.recorded.size)
        assertTrue(suggestCheaperAlternativesCalls.isEmpty())
    }

    /**
     * [FundPriceUpdateWorker.runScans] mot testets fakes. Bara det testet styr anges; resten har
     * produktionens standardvärden, och inspelningarna går till [suggestionRepo].
     */
    private suspend fun runScans(
        refreshSucceeded: Boolean,
        refreshForced: Boolean = false,
        scanComparisons: Boolean,
        scanSwitchPlan: Boolean,
        scanBenchmark: Boolean = false,
        suggestionRepo: SuggestionRecordRepository = FakeSuggestionRecordRepository(),
        rescanIfRequested: Boolean = false,
        now: () -> Long = System::currentTimeMillis,
        startedAt: Long = now(),
        switchPlanScanRequestedSince: (epochMillis: Long) -> SwitchPlanScanRequest? = { null },
        enqueueSwitchPlanFollowUp: (force: Boolean) -> Unit = {}
    ) = FundPriceUpdateWorker.runScans(
        refreshSucceeded = refreshSucceeded,
        refreshForced = refreshForced,
        scanComparisons = scanComparisons,
        scanSwitchPlan = scanSwitchPlan,
        scanBenchmark = scanBenchmark,
        transactionRepository = fakeTransactionRepo,
        fundPriceRepository = fakeFundPriceRepo,
        fundMetadataRepository = fakeFundMetadataRepo,
        preferencesRepository = preferencesRepository,
        suggestionRecordRepository = suggestionRepo,
        switchWatchRepository = switchWatchRepo,
        suggestionDismissalRepository = dismissalRepo,
        rescanIfRequested = rescanIfRequested,
        now = now,
        startedAt = startedAt,
        switchPlanScanRequestedSince = switchPlanScanRequestedSince,
        enqueueSwitchPlanFollowUp = enqueueSwitchPlanFollowUp
    )

    @Test
    fun `runScans kor de begarda skanningarna efter en lyckad uppdatering`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        runScans(
            refreshSucceeded = true,
            scanComparisons = false,
            scanSwitchPlan = true,
            suggestionRepo = suggestionRepo
        )

        assertEquals(1, suggestionRepo.recorded.size)
    }

    @Test
    fun `en begaran under skanningen raknar om planen en gang till - inte fler`() = runTest {
        // KEEP tappar en begäran som görs medan jobbet kör (transaktion, radering, avstående).
        // Workern frågar schemaläggaren efteråt och räknar om en gång — aldrig en kedja.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()
        var clock = 1_000L

        runScans(
            refreshSucceeded = true,
            scanComparisons = false,
            scanSwitchPlan = true,
            suggestionRepo = suggestionRepo,
            rescanIfRequested = true,
            // En begäran "kommer in" efter att skanningen började — men ingen efter sista kollen.
            switchPlanScanRequestedSince = { since -> SwitchPlanScanRequest(1_500, force = false).takeIf { since < 1_500 } },
            now = { clock++ }
        )

        // Två skanningar (varje skanning frågar källan om nivå 3 en gång), inte tre — och en rad.
        assertEquals(listOf(3, 3), switchCandidateLevelsQueried)
        assertEquals(1, suggestionRepo.recorded.size)
        assertTrue("ingen begäran efter sista kollen — ingen uppföljare", followUps.isEmpty())
    }

    @Test
    fun `en begaran under kursuppdateringen fangas ocksa - startedAt tas fore refreshAll`() = runTest {
        // doWork tar startedAt innan kurserna hämtas; en begäran som KEEP tappade medan refreshAll
        // körde (här: vid 700, före runScans-klockans 1 000) ska ge en omräkning.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()
        var clock = 1_000L

        runScans(
            refreshSucceeded = true,
            scanComparisons = false,
            scanSwitchPlan = true,
            suggestionRepo = suggestionRepo,
            rescanIfRequested = true,
            startedAt = 500,
            switchPlanScanRequestedSince = { since -> SwitchPlanScanRequest(700, force = false).takeIf { since < 700 } },
            now = { clock++ }
        )

        assertEquals(listOf(3, 3), switchCandidateLevelsQueried)
        assertEquals(1, suggestionRepo.recorded.size)
        assertTrue(followUps.isEmpty())
    }

    @Test
    fun `en forcerad begaran under skanningen hamtar kurserna innan omrakningen`() = runTest {
        // KEEP nedgraderar annars "Räkna om" till den redan köade, icke-forcerade skanningen.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()
        var clock = 1_000L

        runScans(
            refreshSucceeded = true,
            scanComparisons = false,
            scanSwitchPlan = true,
            suggestionRepo = suggestionRepo,
            rescanIfRequested = true,
            switchPlanScanRequestedSince = { since -> SwitchPlanScanRequest(1_500, force = true).takeIf { since < 1_500 } },
            now = { clock++ }
        )

        // HELD har färsk kurs och hade aldrig hämtats utan force (ISIN-fond → refreshSince).
        assertTrue(refreshSinceCalls.any { it.first == "HELD" })
        assertEquals(listOf(3, 3), switchCandidateLevelsQueried)
    }

    @Test
    fun `en forcerad begaran vars hamtning misslyckas raknas inte om - en forcerad uppfoljare koas`() = runTest {
        // Samma vakt som första nivån: facit får inte spelas in mot kurser som just inte gick att hämta.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()
        refreshResult = false
        refreshSinceResult = false
        var clock = 1_000L

        runScans(
            refreshSucceeded = true,
            scanComparisons = false,
            scanSwitchPlan = true,
            suggestionRepo = suggestionRepo,
            rescanIfRequested = true,
            switchPlanScanRequestedSince = { since -> SwitchPlanScanRequest(1_500, force = true).takeIf { since < 1_500 } },
            enqueueSwitchPlanFollowUp = { force -> followUps += force },
            now = { clock++ }
        )

        assertEquals(listOf(3), switchCandidateLevelsQueried)
        assertEquals(listOf(true), followUps)
    }

    @Test
    fun `en begaran efter sista kollen koar en uppfoljare med sin force-flagga`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        var clock = 1_000L

        runScans(
            refreshSucceeded = true,
            scanComparisons = false,
            scanSwitchPlan = true,
            rescanIfRequested = true,
            // Begäran kommer först efter sista kollen (klockan står då på 1 001).
            switchPlanScanRequestedSince = { since -> SwitchPlanScanRequest(2_000, force = true).takeIf { since >= 1_001 } },
            enqueueSwitchPlanFollowUp = { force -> followUps += force },
            now = { clock++ }
        )

        assertEquals(listOf(3), switchCandidateLevelsQueried)
        assertEquals(listOf(true), followUps)
    }

    @Test
    fun `en begaran som kommer under de efterfoljande skanningarna raknas ocksa om`() = runTest {
        // Kontrollen görs sist i körningen: en begäran under scanOutcomeNavs/scanSwitchWatches
        // tappas annars. Begäran "kommer" här först när facit-ifyllnaden hämtat kandidatens kurs
        // en andra gång, alltså efter planskanningen.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        runScans(
            refreshSucceeded = true,
            scanComparisons = false,
            scanSwitchPlan = true,
            suggestionRepo = suggestionRepo,
            rescanIfRequested = true,
            switchPlanScanRequestedSince = { since -> SwitchPlanScanRequest(since + 1, force = false).takeIf { refreshSinceCalls.count { it.second == "SE_CAND" } >= 2 } }
        )

        assertEquals(listOf(3, 3), switchCandidateLevelsQueried)
        assertEquals(1, suggestionRepo.recorded.size)
    }

    @Test
    fun `tva samtidiga planskanningar spelar inte in samma par tva ganger`() = runTest {
        // Backstopen och en begärd skanning kör under olika unika namn och kan överlappa; utan
        // serialisering läser båda dagens inspelning tom och spelar in samma par.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        switchCandidateDelayMillis = 100
        val suggestionRepo = FakeSuggestionRecordRepository()
        val today = LocalDate.of(2026, 1, 1)

        val first = launch { scanSwitchPlan(suggestionRepo, today, batchEpochMillis = 1_000) }
        val second = launch { scanSwitchPlan(suggestionRepo, today, batchEpochMillis = 2_000) }
        first.join()
        second.join()

        assertEquals(1, suggestionRepo.recorded.size)
        assertEquals(2_000L, suggestionRepo.recorded.single().batchEpochMillis)
    }

    @Test
    fun `kornings-id och dag lases forst nar skanningen fatt laset`() = runTest {
        // En skanning som väntar på en annan ska skriva med ett nyare id än den som kör — läses
        // id:t vid anropet får den väntande ett äldre id och den nyaste planen ser äldst ut.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        switchCandidateDelayMillis = 100
        val suggestionRepo = FakeSuggestionRecordRepository()
        val reads = mutableListOf<String>()
        fun scan(name: String, batch: Long) = launch {
            FundPriceUpdateWorker.scanSwitchPlan(
                fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository, suggestionRepo, dismissalRepo, switchWatchRepo,
                calendar = {
                    reads += "dag-$name"
                    LocalDate.of(2026, 1, 1)
                },
                clock = {
                    reads += name
                    batch
                }
            )
        }

        val first = scan("A", 1_000)
        val second = scan("B", 2_000)
        testScheduler.advanceTimeBy(50)
        assertEquals("B väntar på låset och har inte läst klockan", listOf("dag-A", "A"), reads)

        first.join()
        second.join()
        assertEquals(listOf("dag-A", "A", "dag-B", "B"), reads)
        assertEquals(2_000L, suggestionRepo.recorded.single().batchEpochMillis)
    }

    @Test
    fun `backstopen raknar inte om pa en begaran - den blockerar ingen`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))

        runScans(
            refreshSucceeded = true,
            scanComparisons = false,
            scanSwitchPlan = true,
            rescanIfRequested = false,
            switchPlanScanRequestedSince = { since -> SwitchPlanScanRequest(since + 1, force = false) }
        )

        assertEquals(listOf(3), switchCandidateLevelsQueried)
    }

    @Test
    fun `scanSwitchPlan hamtar kandidater bara for underviktade nivaer`() = runTest {
        // Regression (issue #75, punkt 4): kandidater hämtades för *varje* nivå i
        // målfördelningen. Varje nivå kostar en källfråga plus upp till tio
        // köpbarhetsuppslag, var 12:e timme — för nivåer planen ändå aldrig köper på.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        // Fem nivåer i målet, men innehavet ligger på nivå 5 och bara nivå 3 är underviktad
        // med marginal; nivå 1, 2 och 4 har målvikter under MIN_GAP_PP.
        preferencesRepository.setRiskProfile(
            RiskProfile(targetAllocation = mapOf(1 to 0.01, 2 to 0.01, 3 to 0.96, 4 to 0.01, 5 to 0.01))
        )
        val suggestionRepo = FakeSuggestionRecordRepository()

        scanSwitchPlan(suggestionRepo)

        assertEquals(listOf(3), switchCandidateLevelsQueried)
    }

    @Test
    fun `scanSwitchPlan fragar inte kallan alls nar ingen niva ar underviktad`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        // Portföljen ligger redan på målet — ingen nivå att fylla, alltså inget att hämta.
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(5 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        scanSwitchPlan(suggestionRepo)

        assertTrue(switchCandidateLevelsQueried.isEmpty())
        assertTrue(suggestionRepo.recorded.isEmpty())
    }

    @Test
    fun `scanSwitchPlan gallrar bort forslag aldre an retentionsfonstret`() = runTest {
        // Regression (issue #75, punkt 6): tabellen växte med varje backstop-körning och
        // rensades aldrig — och den ingår i backup-kontraktet, så payloaden växte med den.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        scanSwitchPlan(suggestionRepo, LocalDate.of(2026, 1, 1))

        // Gallringen körs efter inspelningen, så dagens rader aldrig faller offer för den.
        assertEquals(LocalDate.of(2026, 1, 1), suggestionRepo.prunedAt)
        assertEquals(1, suggestionRepo.recorded.size)
    }

    @Test
    fun `scanSwitchPlan markerar radernas korning sa tva korningar samma dygn kan skiljas at`() = runTest {
        // Regression (issue #75, fynd B): backstopen kör var 12:e timme, så två körningar landar
        // samma dygn. Utan körnings-id lästes de som en enda plan på Hem.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        scanSwitchPlan(suggestionRepo, LocalDate.of(2026, 1, 1), batchEpochMillis = 1_767_222_000_000)

        assertEquals(1_767_222_000_000L, suggestionRepo.recorded.single().batchEpochMillis)
    }

    @Test
    fun `scanSwitchPlan loser kopkandidatens NAV via ISIN-kedjan, inte via fondlista-refresh`() = runTest {
        // Regression, issue #75 punkt 2: köpkandidaten saknar Handelsbanken-FundId (identiteten
        // är ISIN:et), så refresh() skulle fråga fondlista med ISIN:et som fondnyckel och aldrig
        // ge någon kurs — facit-inspelningen föll då tyst och HEM-8 blev en no-op skarpt.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        scanSwitchPlan(suggestionRepo)

        assertEquals(1, suggestionRepo.recorded.size)
        assertEquals(
            "kandidatens NAV ska hämtas via ISIN-grenen",
            listOf("SE_CAND" to "SE_CAND"),
            refreshSinceCalls.map { it.first to it.second }
        )
        assertFalse("refresh() når aldrig en fond utan plattformskod", refreshedFundIds.contains("SE_CAND"))
    }

    @Test
    fun `scanSwitchPlan spelar inte in nar kandidatens NAV inte gar att losa upp`() = runTest {
        setUpOverweightedPortfolio()
        isinChainPrices.remove("SE_CAND") // ingen källa i kedjan känner kandidaten
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        scanSwitchPlan(suggestionRepo)

        assertTrue(suggestionRepo.recorded.isEmpty())
    }

    /**
     * Ett sälj på 10 000 kr i en annan fond, helt avyttrad, vars bevakning väntar på köp mot nivå 3
     * — med [setUpOverweightedPortfolio]s 10 000 kr på nivå 5 och mål 50/50 är fördelningen då
     * redan på målet.
     */
    private suspend fun setUpPendingProceeds(today: LocalDate, sellTransactionId: Long?) {
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 0.5, 5 to 0.5)))
        val sold = Fund(fundId = "SOLD", name = "Såld", isin = "SE_SOLD")
        funds.value = funds.value + sold
        transactions.value = transactions.value + buy(sold.fundId, 100.0, 100.0) +
            Transaction(id = 42, fundId = sold.fundId, type = TransactionType.SALJ, epochDay = today.minusDays(1).toEpochDay(), shares = 100.0, pricePerShare = 100.0)
        switchWatchRepo.start(
            SwitchWatch(
                sellIsin = "SE_SOLD",
                sellFundName = "Såld",
                soldAtEpochDay = today.minusDays(1).toEpochDay(),
                proceedsKr = 10_000.0,
                targetLevel = 3,
                sellTransactionId = sellTransactionId,
                candidates = listOf(SwitchWatchCandidate(isin = "SE_CAND", name = "Kandidat"))
            )
        )
    }

    @Test
    fun `likvid som vantar pa kop fyller gapet - ingen ny plan och tomheten ar bevislig`() = runTest {
        // BYT-3: utan likviden hade planen föreslagit att sälja 5 000 kr till ur HELD för ett gap
        // som säljet redan finansierat. Ingen källfråga görs när ingen nivå är underviktad.
        val today = LocalDate.of(2026, 1, 1)
        setUpPendingProceeds(today, sellTransactionId = 42)
        val suggestionRepo = FakeSuggestionRecordRepository()

        scanSwitchPlan(suggestionRepo, today, batchEpochMillis = 5_000)

        assertTrue(suggestionRepo.recorded.isEmpty())
        assertTrue(switchCandidateLevelsQueried.isEmpty())
        assertEquals(5_000L, preferencesRepository.switchPlanEmptiedAtEpochMillis.first())
    }

    @Test
    fun `en bevakning utan saljtransaktion raknas inte som likvid - planen ges som forut`() = runTest {
        // "Bevaka alternativ" ur ett förslag säger inte att pengarna är fria.
        val today = LocalDate.of(2026, 1, 1)
        setUpPendingProceeds(today, sellTransactionId = null)
        val suggestionRepo = FakeSuggestionRecordRepository()

        scanSwitchPlan(suggestionRepo, today)

        assertEquals(5_000.0, suggestionRepo.recorded.single().switchValueKr ?: -1.0, 1e-9)
        assertNull(preferencesRepository.switchPlanEmptiedAtEpochMillis.first())
    }

    @Test
    fun `ett kop i kandidaten sedan saljdagen forbrukar likviden - gapet ar oppet igen`() = runTest {
        // Köpet gjordes utan att bevakningen stängdes ("Inget byte" i väljaren): pengarna är
        // placerade på nivå 3 som innehav, och likviden får inte räknas en gång till.
        val today = LocalDate.of(2026, 1, 1)
        setUpPendingProceeds(today, sellTransactionId = 42)
        val cand = fundByIsin.getValue("SE_CAND")
        funds.value = funds.value + cand
        transactions.value = transactions.value +
            Transaction(id = 43, fundId = cand.fundId, type = TransactionType.KOP, epochDay = today.toEpochDay(), shares = 200.0, pricePerShare = 50.0)
        cachedPrices[cand.fundId] = FundPrice(fundId = cand.fundId, epochDay = today.toEpochDay(), nav = 50.0)
        metadataByIsin["SE_CAND"] = heldMetadata("SE_CAND", risk = 3, totalFee = 0.3)
        val suggestionRepo = FakeSuggestionRecordRepository()

        scanSwitchPlan(suggestionRepo, today)

        // 10 000 på nivå 5 och 10 000 på nivå 3 som innehav — på målet utan likviden, som nu är 0.
        assertTrue(suggestionRepo.recorded.isEmpty())
        assertTrue(switchCandidateLevelsQueried.isEmpty())
    }

    @Test
    fun `scanSwitchPlan spelar inte in samma plan tva ganger samma dag`() = runTest {
        // Skanningen körs numera efter varje transaktion och avstående — facit ska inte räkna
        // samma råd två gånger bara för att planen räknades om och blev densamma.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()
        val today = LocalDate.of(2026, 1, 1)

        scanSwitchPlan(suggestionRepo, today, batchEpochMillis = 1_000)
        scanSwitchPlan(suggestionRepo, today, batchEpochMillis = 2_000)

        // Raden flyttas till den nya körningen — en rad, samma id, så facit räknar rådet en gång.
        assertEquals(1, suggestionRepo.recorded.size)
        assertEquals(2_000L, suggestionRepo.recorded.single().batchEpochMillis)
        assertEquals(1L, suggestionRepo.recorded.single().id)
    }

    @Test
    fun `en andrad plan samma dag flyttar befintliga par och spelar bara in de nya`() = runTest {
        // Två körningar samma dygn med olika plan: den andra ska vara en komplett körning med
        // planIndex 0 — Beslut läser den senaste körningen som en enhet och kapar vid första luckan
        // — men ett par som redan spelats in i dag får inte spelas in igen: facit (SET-5) räknar
        // varje råd en gång. Raden flyttas i stället, med sitt id och sin kvittering.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()
        val today = LocalDate.of(2026, 1, 1)
        scanSwitchPlan(suggestionRepo, today, batchEpochMillis = 1_000)

        // En andra fond på nivå 5 kommer till — planen får två steg.
        addLevelFiveHolding(fundId = "EXTRA", name = "Extra", totalFee = 0.5, shares = 50.0, day = today)
        addSecondCandidate(priceDay = today)
        scanSwitchPlan(suggestionRepo, today, batchEpochMillis = 2_000)

        val latest = suggestionRepo.observeLatestBatch().first()
        assertEquals(2_000L, latest.first().batchEpochMillis)
        assertEquals(listOf(0, 1), latest.map { it.planIndex })
        assertEquals(listOf("SE_HELD", "SE_EXTRA"), latest.map { it.sellIsin })
        // Den flyttade raden behåller sitt id (bevakningar och avståenden pekar på det); bara EXTRA är ny.
        assertEquals(listOf(1L, 2L), latest.map { it.id })
        assertEquals(2, suggestionRepo.recorded.size)
        // Bara den nya raden kostade ett NAV-uppslag.
        assertEquals(listOf("SE_CAND", "SE_CAND2"), refreshSinceCalls.map { it.second })
    }

    @Test
    fun `en rad utan hamtbar kurs far inte de andra att spelas in igen`() = runTest {
        // Jämförelsen får inte vara "hela planen mot det inspelade": en rad vars kandidat-NAV inte
        // går att lösa upp spelas inte in, och det ska inte göra att resten spelas in på nytt varje
        // skanning.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        addLevelFiveHolding(fundId = "EXTRA", name = "Extra", totalFee = 0.5, shares = 50.0, day = LocalDate.now())
        addSecondCandidate(priceDay = null)
        // Ingen källa i kedjan känner SE_CAND2 — raden kan inte spelas in.
        val suggestionRepo = FakeSuggestionRecordRepository()
        val today = LocalDate.of(2026, 1, 1)

        scanSwitchPlan(suggestionRepo, today, batchEpochMillis = 1_000)
        scanSwitchPlan(suggestionRepo, today, batchEpochMillis = 2_000)

        val record = suggestionRepo.recorded.single()
        assertEquals("SE_HELD", record.sellIsin)
        assertEquals(2_000L, record.batchEpochMillis)
    }

    @Test
    fun `en tom omrakning gor den inspelade planen inaktuell`() = runTest {
        // Bytet är gjort: ingen nivå avviker längre. En tom plan kan inte spelas in som rader, så
        // markören ska sättas — annars står "Sälj X" kvar i upp till PLAN_TTL_DAYS.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()
        scanSwitchPlan(suggestionRepo, batchEpochMillis = 1_000)
        assertNull(preferencesRepository.switchPlanEmptiedAtEpochMillis.first())

        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(5 to 1.0)))
        scanSwitchPlan(suggestionRepo, batchEpochMillis = 2_000)

        assertEquals(2_000L, preferencesRepository.switchPlanEmptiedAtEpochMillis.first())
        // Historiken rörs inte — rådet gavs, facit mäter det fortfarande.
        assertEquals(1, suggestionRepo.recorded.size)
        assertEquals(1_000L, suggestionRepo.recorded.single().batchEpochMillis)

        // En ny icke-tom körning nollställer markören — den får inte dölja planen på en jämförelse
        // mellan två väggklocke-id:n (en klocka som ställts bakåt hade gömt den nya planen).
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        scanSwitchPlan(suggestionRepo, batchEpochMillis = 3_000)
        assertNull(preferencesRepository.switchPlanEmptiedAtEpochMillis.first())
    }

    @Test
    fun `saknad metadata eller kandidater satter inte tom-markoren`() = runTest {
        // Källan nere är inte "ingen plan": en giltig inspelad plan får inte döljas av ett uteblivet svar.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()
        scanSwitchPlan(suggestionRepo, batchEpochMillis = 1_000)

        // Inga kandidater för nivå 3 (källan svarade inte) → planen blir tom, men inte bevisligen.
        switchCandidatesByLevel.remove(3)
        scanSwitchPlan(suggestionRepo, batchEpochMillis = 2_000)
        assertNull(preferencesRepository.switchPlanEmptiedAtEpochMillis.first())

        // Metadata saknas för innehavet → oklassificerat, inga gap att räkna — inte heller bevisligen tomt.
        switchCandidatesByLevel[3] = listOf(SwitchPlanCalc.Candidate(candidateMetadata("SE_CAND", risk = 3, totalFee = 0.3, developmentOneYear = 0.1), 0.1))
        metadataByIsin.remove("SE_HELD")
        scanSwitchPlan(suggestionRepo, batchEpochMillis = 3_000)
        assertNull(preferencesRepository.switchPlanEmptiedAtEpochMillis.first())
        assertEquals(1, suggestionRepo.recorded.size)
    }

    @Test
    fun `innehav utan kand riskniva satter inte tom-markoren`() = runTest {
        // Metadataraden finns men saknar risknivån — innehavet är oklassificerat, gapen är räknade
        // på en halv portfölj och en tom plan är då inget svar.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()
        scanSwitchPlan(suggestionRepo, batchEpochMillis = 1_000)

        metadataByIsin["SE_HELD"] = heldMetadata("SE_HELD", risk = 5, totalFee = 1.0).copy(risk = null)
        scanSwitchPlan(suggestionRepo, batchEpochMillis = 2_000)

        assertNull(preferencesRepository.switchPlanEmptiedAtEpochMillis.first())
    }

    @Test
    fun `innehav utan avgift ger en bevisligen tom plan och satter tom-markoren`() = runTest {
        // Utan avgift väger innehavet ändå på sin nivå (gapen är riktiga) men kan inte säljas, så
        // planen blir tom — ett svar ur känd data, inte ett uteblivet svar. Avgiften saknas
        // bestående i källan för vissa fonder; hade den hållit markören tillbaka stod en gammal
        // plan kvar för alltid.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()
        scanSwitchPlan(suggestionRepo, batchEpochMillis = 1_000)
        assertEquals(1, suggestionRepo.recorded.size)

        metadataByIsin["SE_HELD"] = heldMetadata("SE_HELD", risk = 5, totalFee = 1.0).copy(totalFee = null)
        scanSwitchPlan(suggestionRepo, batchEpochMillis = 2_000)

        assertEquals(2_000L, preferencesRepository.switchPlanEmptiedAtEpochMillis.first())
    }

    @Test
    fun `innehav utan kurs satter inte tom-markoren`() = runTest {
        // Ett innehav utan känt värde faller ur fördelningen — en "tom" plan på en halv portfölj
        // är inget svar.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()
        scanSwitchPlan(suggestionRepo, batchEpochMillis = 1_000)
        cachedPrices.remove("HELD")

        scanSwitchPlan(suggestionRepo, batchEpochMillis = 2_000)

        assertNull(preferencesRepository.switchPlanEmptiedAtEpochMillis.first())
    }

    @Test
    fun `kan steg 0 inte spelas in rors den gamla korningen inte alls`() = runTest {
        // Flytt och inspelning är ett prefix från steg 0, atomärt: hade de befintliga paren flyttats
        // till en körning utan steg 0 hade Beslut visat ingen plan alls.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()
        val today = LocalDate.of(2026, 1, 1)
        scanSwitchPlan(suggestionRepo, today, batchEpochMillis = 1_000)

        // En dyrare fond på nivå 5 tar steg 0 (Dyr → CAND), HELD får CAND2 som steg 1 — men
        // CAND:s NAV går inte längre att lösa upp, så steg 0 kan inte spelas in.
        addLevelFiveHolding(fundId = "DYR", name = "Dyr", totalFee = 2.0, shares = 50.0, day = today)
        addSecondCandidate(priceDay = today)
        isinChainPrices.remove("SE_CAND")
        cachedPrices.remove("SE_CAND")
        scanSwitchPlan(suggestionRepo, today, batchEpochMillis = 2_000)

        val record = suggestionRepo.recorded.single()
        assertEquals("SE_HELD", record.sellIsin)
        assertEquals(1_000L, record.batchEpochMillis)
        assertEquals(0, record.planIndex)
        assertEquals(1, suggestionRepo.observeLatestBatch().first().size)
    }

    @Test
    fun `en plan inspelad efter en tom omrakning nollstaller markoren`() = runTest {
        // Markören pekar på en tom körning; nästa icke-tomma körning tar bort den i stället för att
        // låta resolvern jämföra två väggklocke-id:n.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(5 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()
        scanSwitchPlan(suggestionRepo, batchEpochMillis = 1_000)
        assertEquals(1_000L, preferencesRepository.switchPlanEmptiedAtEpochMillis.first())

        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        scanSwitchPlan(suggestionRepo, batchEpochMillis = 2_000)

        assertNull(preferencesRepository.switchPlanEmptiedAtEpochMillis.first())
        assertEquals(2_000L, suggestionRepo.recorded.single().batchEpochMillis)
    }

    @Test
    fun `scanSwitchPlan tar aktiva avstaenden ur kandidatmangden`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        addSecondCandidate(priceDay = LocalDate.now())
        dismissalRepo.dismiss("SE_HELD", "SE_CAND", SuggestionKind.RISK_PLAN, LocalDate.now())
        // Ett avgiftsbyte för det andra paret rör inte planen — bara planens egna avståenden räknas.
        dismissalRepo.dismiss("SE_HELD", "SE_CAND2", SuggestionKind.FEE, LocalDate.now())
        val suggestionRepo = FakeSuggestionRecordRepository()

        scanSwitchPlan(suggestionRepo)

        val record = suggestionRepo.recorded.single()
        assertEquals("SE_CAND2", record.buyIsin)
        assertEquals(0, record.planIndex)
    }

    @Test
    fun `scanSwitchPlan gor inget utan bevakade fonder`() = runTest {
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        scanSwitchPlan(suggestionRepo)

        assertTrue(suggestionRepo.recorded.isEmpty())
    }

    // --- scanOutcomeNavs: håller köpsidans kurser färska för facit (SET-5, issue #80) ---

    private fun outcomeRecord(id: Long, buyIsin: String, suggestedAtEpochDay: Long) = SuggestionRecord(
        id = id,
        suggestedAtEpochDay = suggestedAtEpochDay,
        planIndex = 0,
        sellIsin = "SE_HELD",
        buyIsin = buyIsin,
        sellNavAtSuggestion = 100.0,
        buyNavAtSuggestion = 50.0
    )

    @Test
    fun `scanSwitchWatches fyller kandidaternas kurser och hoppar over bevakade fonder`() = runTest {
        // Hems kort (HEM-11) ska kunna peka ut ledaren utan nätverk — därför cachas kandidaternas
        // senaste kurs här. Säljsidan är en bevakad fond och uppdateras redan av refreshAll.
        val today = LocalDate.now()
        funds.value = listOf(Fund(fundId = "SHB1", name = "Såld", isin = "SE_TRACKED"))
        listOf("SE_A", "SE_B").forEach { isin ->
            fundByIsin[isin] = Fund(fundId = isin, name = "Kandidat $isin", isin = isin)
        }
        fundByIsin["SE_TRACKED"] = Fund(fundId = "SE_TRACKED", name = "Såld", isin = "SE_TRACKED")
        val watchRepo = FakeSwitchWatchRepository()
        watchRepo.start(
            SwitchWatch(
                sellIsin = "SE_TRACKED",
                sellFundName = "Såld",
                soldAtEpochDay = today.minusDays(1).toEpochDay(),
                candidates = listOf(
                    SwitchWatchCandidate(isin = "SE_A", name = "Kandidat A"),
                    SwitchWatchCandidate(isin = "SE_B", name = "Kandidat B"),
                    SwitchWatchCandidate(isin = "SE_TRACKED", name = "Såld")
                )
            )
        )

        FundPriceUpdateWorker.scanSwitchWatches(fakeTransactionRepo, fakeFundPriceRepo, watchRepo, today)

        assertEquals(listOf("SE_A", "SE_B"), refreshSinceCalls.map { it.second })
        assertTrue(refreshCalls.isEmpty())
    }

    @Test
    fun `scanSwitchWatches stanger utgangna bevakningar utan att radera dem`() = runTest {
        val today = LocalDate.now()
        val watchRepo = FakeSwitchWatchRepository()
        watchRepo.start(
            SwitchWatch(
                sellIsin = "SE_GAMMAL",
                sellFundName = "Gammal",
                soldAtEpochDay = today.minusDays(SwitchWatchCalc.WATCH_TTL_DAYS + 1).toEpochDay()
            )
        )

        FundPriceUpdateWorker.scanSwitchWatches(fakeTransactionRepo, fakeFundPriceRepo, watchRepo, today)

        val watch = watchRepo.watches.value.single()
        assertFalse(watch.isOpen)
        assertEquals(SwitchWatchCloseReason.UTGANGEN, watch.closeReason)
        // Raden bär vad användaren gjorde — den stängs, den raderas aldrig.
        assertNull(watch.boughtIsin)
    }

    @Test
    fun `scanSwitchWatches gor inget utan oppna bevakningar`() = runTest {
        FundPriceUpdateWorker.scanSwitchWatches(
            fakeTransactionRepo,
            fakeFundPriceRepo,
            FakeSwitchWatchRepository(),
            LocalDate.now()
        )

        assertTrue(refreshSinceCalls.isEmpty())
        assertTrue(refreshCalls.isEmpty())
    }

    @Test
    fun `scanOutcomeNavs hamtar kurs for kopkandidaten via ISIN-kedjan`() = runTest {
        val today = LocalDate.now()
        fundByIsin["SE_CAND"] = Fund(fundId = "SE_CAND", name = "Kandidat", isin = "SE_CAND")
        val suggestionRepo = FakeSuggestionRecordRepository()
        suggestionRepo.record(outcomeRecord(1, "SE_CAND", today.toEpochDay()))

        FundPriceUpdateWorker.scanOutcomeNavs(fakeTransactionRepo, fakeFundPriceRepo, suggestionRepo, today)

        // refreshSince, inte refresh: en köpkandidat är per definition en fond appen aldrig ägt
        // och saknar Handelsbanken-FundId (samma skäl som scanSwitchPlans resolveBuyNav).
        assertEquals(listOf("SE_CAND"), refreshSinceCalls.map { it.second })
        assertTrue(refreshCalls.isEmpty())
    }

    @Test
    fun `scanOutcomeNavs hoppar over redan bevakade fonder`() = runTest {
        // Säljsidan (och varje annan bevakad fond) uppdateras redan av refreshAll under fondens
        // egen fundId — att hämta den igen via ISIN-vägen hade kostat ett anrop till och lagt en
        // dubblettrad i cachen under en annan nyckel.
        val today = LocalDate.now()
        funds.value = listOf(Fund(fundId = "SHB1", name = "Bevakad", isin = "SE_TRACKED"))
        fundByIsin["SE_TRACKED"] = Fund(fundId = "SE_TRACKED", name = "Bevakad", isin = "SE_TRACKED")
        val suggestionRepo = FakeSuggestionRecordRepository()
        suggestionRepo.record(outcomeRecord(1, "SE_TRACKED", today.toEpochDay()))

        FundPriceUpdateWorker.scanOutcomeNavs(fakeTransactionRepo, fakeFundPriceRepo, suggestionRepo, today)

        assertTrue(refreshSinceCalls.isEmpty())
    }

    @Test
    fun `scanOutcomeNavs ar budgeterad och tar de nyaste forslagen forst`() = runTest {
        val today = LocalDate.now()
        val suggestionRepo = FakeSuggestionRecordRepository()
        // Sex distinkta köpkandidater, äldst inspelad först — budgeten är fyra per körning.
        (1..6).forEach { n ->
            val isin = "SE_C$n"
            fundByIsin[isin] = Fund(fundId = isin, name = "Kandidat $n", isin = isin)
            suggestionRepo.record(outcomeRecord(n.toLong(), isin, today.minusDays((6 - n).toLong()).toEpochDay()))
        }

        FundPriceUpdateWorker.scanOutcomeNavs(fakeTransactionRepo, fakeFundPriceRepo, suggestionRepo, today)

        assertEquals(listOf("SE_C6", "SE_C5", "SE_C4", "SE_C3"), refreshSinceCalls.map { it.second })
    }

    @Test
    fun `scanOutcomeNavs gor ingenting utan inspelad historik`() = runTest {
        FundPriceUpdateWorker.scanOutcomeNavs(fakeTransactionRepo, fakeFundPriceRepo, FakeSuggestionRecordRepository(), LocalDate.now())

        assertTrue(refreshSinceCalls.isEmpty())
        assertTrue(refreshCalls.isEmpty())
    }

    // --- Referensfonden för Hems indexjämförelse (HEM-10, issue #96) ---

    private fun indexCandidate(isin: String, totalFee: Double) = FundMetadata(
        isin = isin, name = "Index $isin", orderbookId = isin, totalFee = totalFee, managementFee = totalFee,
        category = null, fundType = IndexBenchmarkSelector.TAG_TYPE_EQUITY, companyName = null, risk = 5,
        indexFund = true, startDateEpochDay = null, minimumBuy = null,
        tags = listOf(
            FundTag(title = IndexBenchmarkSelector.TAG_TYPE_EQUITY, category = FundTag.CATEGORY_TYPE),
            FundTag(title = IndexBenchmarkSelector.TAG_REGION_GLOBAL, category = FundTag.CATEGORY_COMMON_REGION)
        )
    )

    /** En portfölj med ett köp 2020-01-01 — historikhorisonten skuggportföljen måste nå tillbaka till. */
    private fun setUpPortfolioForBenchmark() {
        funds.value = listOf(Fund(fundId = "SHB1", name = "Fond A"))
        transactions.value = listOf(buy("SHB1", shares = 10.0, pricePerShare = 100.0))
    }

    @Test
    fun `scanBenchmark valjer referensfond och cachar dess historik sedan forsta kopet`() = runTest {
        setUpPortfolioForBenchmark()
        queryResult = listOf(indexCandidate("SE_DYR", 0.40), indexCandidate("SE_BILLIG", 0.10))

        FundPriceUpdateWorker.scanBenchmark(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository)

        assertEquals(listOf("SE_BILLIG"), preferencesRepository.benchmark.first().map { it.isin })
        // Kurserna cachas under ISIN:et som fondnyckel, samma väg som scanOutcomeNavs — och
        // sedan första köpet, annars kan skuggportföljen inte spegla insättningen.
        assertEquals(
            listOf(Triple("SE_BILLIG", "SE_BILLIG", LocalDate.of(2020, 1, 1))),
            refreshSinceCalls
        )
    }

    @Test
    fun `scanBenchmark valjer inte om nar en referensfond redan ar vald`() = runTest {
        // En omvalsrunda per körning hade kostat en källfråga i onödan — och, värre, kunnat byta
        // referensfond bakom ryggen på användaren så jämförelsekurvan ritades om utan orsak.
        setUpPortfolioForBenchmark()
        preferencesRepository.setBenchmark(listOf(BenchmarkComponentRef("SE_REDAN_VALD", weight = 1.0)))
        queryResult = listOf(indexCandidate("SE_BILLIG", 0.10))

        FundPriceUpdateWorker.scanBenchmark(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository)

        assertTrue("ingen källfråga ska behövas", queriesRun.isEmpty())
        assertEquals(listOf("SE_REDAN_VALD"), preferencesRepository.benchmark.first().map { it.isin })
        assertEquals(listOf("SE_REDAN_VALD"), refreshSinceCalls.map { it.second })
    }

    @Test
    fun `scanBenchmark gor ingenting utan transaktioner`() = runTest {
        // Ingen portfölj att jämföra och ingen historikhorisont att hämta mot — då ska inte ens
        // valet göras, eftersom det kostar en källfråga.
        queryResult = listOf(indexCandidate("SE_BILLIG", 0.10))

        FundPriceUpdateWorker.scanBenchmark(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository)

        assertTrue(queriesRun.isEmpty())
        assertTrue(refreshSinceCalls.isEmpty())
        assertTrue(preferencesRepository.benchmark.first().isEmpty())
    }

    @Test
    fun `scanBenchmark valjer ingen referensfond nar katalogen saknar en global indexfond`() = runTest {
        setUpPortfolioForBenchmark()
        queryResult = listOf(neverScanned("SE_AKTIV")) // varken indexfond eller globalt taggad

        FundPriceUpdateWorker.scanBenchmark(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository)

        assertTrue(preferencesRepository.benchmark.first().isEmpty())
        assertTrue(refreshSinceCalls.isEmpty())
    }

    @Test
    fun `runScans kor referensfondsskanningen bara med sin nyckel`() = runTest {
        // Launch-gaten och den manuella knappen sätter aldrig nyckeln: valet kostar en källfråga
        // och hämtningen är en full backfill sedan första köpet.
        setUpPortfolioForBenchmark()
        queryResult = listOf(indexCandidate("SE_BILLIG", 0.10))

        runScans(
            refreshSucceeded = true,
            scanComparisons = false,
            scanSwitchPlan = false,
            scanBenchmark = false
        )
        assertTrue(queriesRun.isEmpty())
        assertTrue(refreshSinceCalls.isEmpty())

        runScans(
            refreshSucceeded = true,
            scanComparisons = false,
            scanSwitchPlan = false,
            scanBenchmark = true
        )
        assertEquals(listOf("SE_BILLIG"), preferencesRepository.benchmark.first().map { it.isin })
        assertEquals(listOf("SE_BILLIG"), refreshSinceCalls.map { it.second })
    }

    private fun bondCandidate(isin: String, totalFee: Double) = FundMetadata(
        isin = isin, name = "Ränta $isin", orderbookId = isin, totalFee = totalFee, managementFee = totalFee,
        category = null, fundType = IndexBenchmarkSelector.TAG_TYPE_BOND, companyName = null, risk = 2,
        indexFund = true, startDateEpochDay = null, minimumBuy = null,
        tags = listOf(FundTag(title = IndexBenchmarkSelector.TAG_TYPE_BOND, category = FundTag.CATEGORY_TYPE))
    )

    /** Metadata för ett *innehav* — bara fondtypstaggen behövs, det är den exponeringen läser. */
    private fun holdingMetadata(isin: String, type: String) = FundMetadata(
        isin = isin, name = "Innehav $isin", orderbookId = isin, totalFee = 0.5, managementFee = 0.5,
        category = null, fundType = type, companyName = null, risk = 4, indexFund = false,
        startDateEpochDay = null, minimumBuy = null,
        tags = listOf(FundTag(title = type, category = FundTag.CATEGORY_TYPE))
    )

    @Test
    fun `scanBenchmark speglar portfoljens aktieandel i en viktad blandning`() = runTest {
        // Hälften aktier, hälften räntor → en 50/50-referens, inte 100 % aktier. Utan
        // viktningen hade jämförelsen mätt tillgångsfördelning i stället för fondval.
        funds.value = listOf(
            Fund(fundId = "AKT", name = "Aktiefond", isin = "SE_H_AKT"),
            Fund(fundId = "RNT", name = "Räntefond", isin = "SE_H_RNT")
        )
        transactions.value = listOf(
            buy("AKT", shares = 10.0, pricePerShare = 100.0),
            buy("RNT", shares = 10.0, pricePerShare = 100.0)
        )
        cachedPrices["AKT"] = FundPrice("AKT", LocalDate.of(2020, 1, 1).toEpochDay(), 100.0)
        cachedPrices["RNT"] = FundPrice("RNT", LocalDate.of(2020, 1, 1).toEpochDay(), 100.0)
        metadataByIsin["SE_H_AKT"] = holdingMetadata("SE_H_AKT", IndexBenchmarkSelector.TAG_TYPE_EQUITY)
        metadataByIsin["SE_H_RNT"] = holdingMetadata("SE_H_RNT", IndexBenchmarkSelector.TAG_TYPE_BOND)
        queryResult = listOf(indexCandidate("SE_AKTIE", 0.10), bondCandidate("SE_RANTA", 0.08))

        FundPriceUpdateWorker.scanBenchmark(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository)

        val components = preferencesRepository.benchmark.first()
        assertEquals(listOf("SE_AKTIE", "SE_RANTA"), components.map { it.isin })
        assertEquals(0.5, components[0].weight, 1e-9)
        assertEquals(0.5, components[1].weight, 1e-9)
        // Båda komponenterna backfillas i samma skanning — annars ger skuggportföljen ingen kurva.
        assertEquals(listOf("SE_AKTIE", "SE_RANTA"), refreshSinceCalls.map { it.second })
    }

    @Test
    fun `en ren aktieportfolj fragar aldrig kallan om rantekandidater`() = runTest {
        // Normalfallet ska kosta en källfråga, inte två.
        setUpPortfolioForBenchmark()
        queryResult = listOf(indexCandidate("SE_BILLIG", 0.10))

        FundPriceUpdateWorker.scanBenchmark(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository)

        assertEquals(1, queriesRun.size)
        assertEquals(IndexBenchmarkSelector.EQUITY_QUERY, queriesRun.single())
    }
}
