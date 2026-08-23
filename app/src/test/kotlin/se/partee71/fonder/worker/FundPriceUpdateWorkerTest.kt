package se.partee71.fonder.worker

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.IndexBenchmarkSelector
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
            Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = since.toEpochDay(), shares = 1.0, pricePerShare = 100.0),
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
        // Historikhorisonten är ett kort färskt fönster: en bevakad men aldrig köpt fond har
        // ingen historik att backfilla mot (TP-18, issue #37).
        val fond = Fund(fundId = "LU0496367417", name = "Franklin Gold", isin = "LU0496367417")
        funds.value = listOf(fond)
        // Ingen transaktion (fonden bara bevakad, aldrig köpt) — inget känt inköpsdatum.

        FundPriceUpdateWorker.refreshAll(fakeTransactionRepo, fakeFundPriceRepo)

        assertTrue(refreshCalls.isEmpty())
        val (fundId, isin, since) = refreshSinceCalls.single()
        assertEquals(fond.fundId, fundId)
        assertEquals(fond.isin, isin)
        assertTrue("kort fönster, inte en backfill", since.isAfter(LocalDate.now().minusDays(60)))
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

    /** Jämförelsens svar per ISIN — tom lista (inget billigare) om inget riggats, null = kunde inte jämföras. */
    private val cheaperAlternativesByIsin = mutableMapOf<String, List<FeeComparisonCalc.Alternative>?>()
    private val switchCandidatesByLevel = mutableMapOf<Int, List<SwitchPlanCalc.Candidate>>()

    /** Nivåerna källan faktiskt frågades om — budgeten i KEY_SCAN_SWITCH_PLAN mäts på dem. */
    private val switchCandidateLevelsQueried = mutableListOf<Int>()

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
            return switchCandidatesByLevel[level].orEmpty()
        }
        override suspend fun suggestCheaperAlternatives(isin: String, holdingValue: Double): List<FeeComparisonCalc.Alternative>? {
            suggestCheaperAlternativesCalls.add(isin to holdingValue)
            return if (isin in cheaperAlternativesByIsin) cheaperAlternativesByIsin[isin] else emptyList()
        }
        override suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata> =
            metadataByIsin.filterKeys { it in isins }
        override suspend fun cachedRiskByFundName(): Map<String, Int> = emptyMap()
        override suspend fun cachedMetadataFor(isins: List<String>): Map<String, FundMetadata> =
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
            comparisonResolvedAtEpochDay = today.minusDays(31).toEpochDay(),
        )
        // ALDRIG har ingen fund_metadata-rad alls — aldrig sökt.

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, FakeSuggestionRecordRepository(), today)

        assertEquals(setOf("SE_UTGANGEN", "SE_ALDRIG"), suggestCheaperAlternativesCalls.map { it.first }.toSet())
    }

    // --- scanComparisons: avgiftsbytets facit-inspelning (ANA-9/SET-5, issue #91) ---

    /**
     * Ett innehav på 10 000 kr med ett billigare, likvärdigt alternativ. Kandidatens kurs finns
     * bara via ISIN-kedjan, precis som skarpt — den fonden ägs inte av appen och har inget
     * Handelsbanken-FundId.
     */
    private fun setUpHoldingWithCheaperAlternative(altNav: Double? = 50.0) {
        val held = Fund(fundId = "HELD", name = "Innehav", isin = "SE_HELD")
        funds.value = listOf(held)
        transactions.value = listOf(buy(held.fundId, 100.0, 100.0))
        cachedPrices[held.fundId] = FundPrice(fundId = held.fundId, epochDay = LocalDate.now().toEpochDay(), nav = 100.0)
        metadataByIsin["SE_HELD"] = heldMetadata("SE_HELD", risk = 5, totalFee = 1.0)
        cheaperAlternativesByIsin["SE_HELD"] = listOf(
            FeeComparisonCalc.Alternative(
                candidate = candidateMetadata("SE_BILLIG", risk = 5, totalFee = 0.2, developmentOneYear = 0.1),
                candidateFeePercent = 0.2,
                annualSavingsKr = 80.0,
            ),
            FeeComparisonCalc.Alternative(
                candidate = candidateMetadata("SE_NASTBILLIG", risk = 5, totalFee = 0.4, developmentOneYear = 0.1),
                candidateFeePercent = 0.4,
                annualSavingsKr = 60.0,
            ),
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
            fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, suggestionRepo, LocalDate.of(2026, 1, 1),
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
            shownAlternativeIsins = listOf("SE_BILLIG", "SE_NASTBILLIG"),
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
            shownAlternativeIsins = listOf("SE_GAMMAL"),
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
            shownAlternativeIsins = listOf("SE_BILLIG"),
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

    @Test
    fun `redan inspelade rader tar ingen plats i inspelningsbudgeten`() = runTest {
        // Sex innehav med ett alternativ vardera fyller budgeten exakt. Är de tre största redan
        // inspelade ska de tre minsta komma med i samma körning — annars hade ett innehav som
        // ligger långt ner i värdeordningen aldrig hunnit med, trots att budgeten var oanvänd.
        val today = LocalDate.of(2026, 1, 1)
        val fundList = (1..9).map { Fund(fundId = "F$it", name = "F$it", isin = "SE_F$it") }
        funds.value = fundList
        // Fallande värde: F1 störst.
        transactions.value = fundList.mapIndexed { index, fund -> buy(fund.fundId, (100 - index).toDouble(), 100.0) }
        fundList.forEach { cachedPrices[it.fundId] = FundPrice(fundId = it.fundId, epochDay = today.toEpochDay(), nav = 100.0) }
        fundList.forEach { fund ->
            val isin = fund.isin!!
            val alt = "ALT_${fund.fundId}"
            // Färsk jämförelse: ingen omskanning, bara inspelning ur den sparade listan.
            metadataByIsin[isin] = heldMetadata(isin, risk = 5, totalFee = 1.0).copy(
                comparisonResolvedAtEpochDay = today.toEpochDay(),
                shownAlternativeIsins = listOf(alt),
            )
            fundByIsin[alt] = Fund(fundId = alt, name = alt, isin = alt)
            isinChainPrices[alt] = FundPrice(fundId = alt, epochDay = today.toEpochDay(), nav = 50.0)
        }
        val suggestionRepo = FakeSuggestionRecordRepository()
        // De tre största är redan inspelade i dag.
        listOf("SE_F1", "SE_F2", "SE_F3").forEach { isin ->
            suggestionRepo.record(
                SuggestionRecord(
                    suggestedAtEpochDay = today.toEpochDay(), planIndex = 0,
                    sellIsin = isin, buyIsin = "ALT_${isin.removePrefix("SE_")}",
                    sellNavAtSuggestion = 100.0, buyNavAtSuggestion = 50.0, kind = SuggestionKind.FEE,
                ),
            )
        }

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, suggestionRepo, today)

        // Budgeten är 6 rader: F4…F9 spelas in, F1–F3 hoppas över utan att kosta något.
        assertEquals(
            (4..9).map { "SE_F$it" },
            suggestionRepo.recorded.drop(3).map { it.sellIsin },
        )
    }

    @Test
    fun `inspelningen ar budgeterad per korning`() = runTest {
        val today = LocalDate.of(2026, 1, 1)
        val fundList = (1..9).map { Fund(fundId = "F$it", name = "F$it", isin = "SE_F$it") }
        funds.value = fundList
        transactions.value = fundList.mapIndexed { index, fund -> buy(fund.fundId, (100 - index).toDouble(), 100.0) }
        fundList.forEach { cachedPrices[it.fundId] = FundPrice(fundId = it.fundId, epochDay = today.toEpochDay(), nav = 100.0) }
        fundList.forEach { fund ->
            val isin = fund.isin!!
            val alt = "ALT_${fund.fundId}"
            metadataByIsin[isin] = heldMetadata(isin, risk = 5, totalFee = 1.0).copy(
                comparisonResolvedAtEpochDay = today.toEpochDay(),
                shownAlternativeIsins = listOf(alt),
            )
            fundByIsin[alt] = Fund(fundId = alt, name = alt, isin = alt)
            isinChainPrices[alt] = FundPrice(fundId = alt, epochDay = today.toEpochDay(), nav = 50.0)
        }
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
                kind = SuggestionKind.RISK_PLAN,
            ),
        )

        FundPriceUpdateWorker.scanComparisons(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, suggestionRepo, today)

        assertEquals(
            listOf(SuggestionKind.RISK_PLAN, SuggestionKind.FEE, SuggestionKind.FEE),
            suggestionRepo.recorded.map { it.kind },
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
        override fun observeLatestBatch(): Flow<List<SuggestionRecord>> =
            flowOf(recorded.filter { it.kind == SuggestionKind.RISK_PLAN })

        var prunedAt: LocalDate? = null
        override suspend fun prune(today: LocalDate) { prunedAt = today }
        override suspend fun hasRecordedToday(sellIsin: String, buyIsin: String, epochDay: Long, kind: SuggestionKind): Boolean =
            listOf(sellIsin, buyIsin, epochDay, kind) in recordedDays
        override suspend fun record(record: SuggestionRecord) {
            recorded += record
            recordedDays += listOf(record.sellIsin, record.buyIsin, record.suggestedAtEpochDay, record.kind)
        }
        override fun observeHistory(): Flow<List<SuggestionRecord>> = flowOf(
            recorded.sortedWith(compareByDescending<SuggestionRecord> { it.suggestedAtEpochDay }.thenByDescending { it.batchEpochMillis }.thenBy { it.planIndex }),
        )
        /** Workern skriver aldrig markeringen — den ägs av användaren (SET-5, issue #80). */
        override suspend fun setFollowed(id: Long, followed: Boolean) = Unit
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
        // Kandidatens kurs finns bara via ISIN-kedjan — precis som skarpt, där fonden saknar
        // Handelsbanken-FundId. Förseedas den i cachen döljs buggen i issue #75, punkt 2.
        isinChainPrices["SE_CAND"] = FundPrice(fundId = "SE_CAND", epochDay = LocalDate.now().toEpochDay(), nav = 50.0)
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
        // Hela innehavet är överviktat (mål 100 % nivå 3), så bytet omfattar hela 10 000 kr.
        assertEquals(10_000.0, record.switchValueKr ?: -1.0, 1e-9)
    }

    @Test
    fun `runScans hoppar over bada skanningarna nar kursuppdateringen misslyckades`() = runTest {
        // Regression (issue #75, fynd E): skanningarna kördes villkorslöst efter refreshAll, även
        // när körningen var på väg att returnera Result.retry(). scanSwitchPlan skrev då en
        // SuggestionRecord med ett NAV-utgångsläge ur en cache som just visat sig inaktuell —
        // ett korrumperat facit som inte går att rätta i efterhand.
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.runScans(
            refreshSucceeded = false,
            scanComparisons = true,
            scanSwitchPlan = true,
            transactionRepository = fakeTransactionRepo,
            fundPriceRepository = fakeFundPriceRepo,
            fundMetadataRepository = fakeFundMetadataRepo,
            preferencesRepository = preferencesRepository,
            suggestionRecordRepository = suggestionRepo,
        )

        assertTrue("inget förslag får spelas in på inaktuella kurser", suggestionRepo.recorded.isEmpty())
        assertTrue("ingen jämförelse får köras heller", suggestCheaperAlternativesCalls.isEmpty())
    }

    @Test
    fun `runScans kor de begarda skanningarna efter en lyckad uppdatering`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(3 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.runScans(
            refreshSucceeded = true,
            scanComparisons = false,
            scanSwitchPlan = true,
            transactionRepository = fakeTransactionRepo,
            fundPriceRepository = fakeFundPriceRepo,
            fundMetadataRepository = fakeFundMetadataRepo,
            preferencesRepository = preferencesRepository,
            suggestionRecordRepository = suggestionRepo,
        )

        assertEquals(1, suggestionRepo.recorded.size)
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
            RiskProfile(targetAllocation = mapOf(1 to 0.01, 2 to 0.01, 3 to 0.96, 4 to 0.01, 5 to 0.01)),
        )
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.scanSwitchPlan(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository, suggestionRepo)

        assertEquals(listOf(3), switchCandidateLevelsQueried)
    }

    @Test
    fun `scanSwitchPlan fragar inte kallan alls nar ingen niva ar underviktad`() = runTest {
        setUpOverweightedPortfolio()
        preferencesRepository.setAccountType(AccountType.ISK_KF)
        // Portföljen ligger redan på målet — ingen nivå att fylla, alltså inget att hämta.
        preferencesRepository.setRiskProfile(RiskProfile(targetAllocation = mapOf(5 to 1.0)))
        val suggestionRepo = FakeSuggestionRecordRepository()

        FundPriceUpdateWorker.scanSwitchPlan(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository, suggestionRepo)

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

        FundPriceUpdateWorker.scanSwitchPlan(
            fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository, suggestionRepo,
            LocalDate.of(2026, 1, 1),
        )

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

        FundPriceUpdateWorker.scanSwitchPlan(
            fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository, suggestionRepo,
            LocalDate.of(2026, 1, 1), batchEpochMillis = 1_767_222_000_000,
        )

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

        FundPriceUpdateWorker.scanSwitchPlan(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository, suggestionRepo)

        assertEquals(1, suggestionRepo.recorded.size)
        assertEquals(
            "kandidatens NAV ska hämtas via ISIN-grenen",
            listOf("SE_CAND" to "SE_CAND"),
            refreshSinceCalls.map { it.first to it.second },
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

        FundPriceUpdateWorker.scanSwitchPlan(fakeTransactionRepo, fakeFundPriceRepo, fakeFundMetadataRepo, preferencesRepository, suggestionRepo)

        assertTrue(suggestionRepo.recorded.isEmpty())
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

    // --- scanOutcomeNavs: håller köpsidans kurser färska för facit (SET-5, issue #80) ---

    private fun outcomeRecord(id: Long, buyIsin: String, suggestedAtEpochDay: Long) = SuggestionRecord(
        id = id,
        suggestedAtEpochDay = suggestedAtEpochDay,
        planIndex = 0,
        sellIsin = "SE_HELD",
        buyIsin = buyIsin,
        sellNavAtSuggestion = 100.0,
        buyNavAtSuggestion = 50.0,
    )

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
            FundTag(title = IndexBenchmarkSelector.TAG_REGION_GLOBAL, category = FundTag.CATEGORY_COMMON_REGION),
        ),
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
            refreshSinceCalls,
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

        FundPriceUpdateWorker.runScans(
            refreshSucceeded = true,
            scanComparisons = false,
            scanSwitchPlan = false,
            scanBenchmark = false,
            transactionRepository = fakeTransactionRepo,
            fundPriceRepository = fakeFundPriceRepo,
            fundMetadataRepository = fakeFundMetadataRepo,
            preferencesRepository = preferencesRepository,
            suggestionRecordRepository = FakeSuggestionRecordRepository(),
        )
        assertTrue(queriesRun.isEmpty())
        assertTrue(refreshSinceCalls.isEmpty())

        FundPriceUpdateWorker.runScans(
            refreshSucceeded = true,
            scanComparisons = false,
            scanSwitchPlan = false,
            scanBenchmark = true,
            transactionRepository = fakeTransactionRepo,
            fundPriceRepository = fakeFundPriceRepo,
            fundMetadataRepository = fakeFundMetadataRepo,
            preferencesRepository = preferencesRepository,
            suggestionRecordRepository = FakeSuggestionRecordRepository(),
        )
        assertEquals(listOf("SE_BILLIG"), preferencesRepository.benchmark.first().map { it.isin })
        assertEquals(listOf("SE_BILLIG"), refreshSinceCalls.map { it.second })
    }

    private fun bondCandidate(isin: String, totalFee: Double) = FundMetadata(
        isin = isin, name = "Ränta $isin", orderbookId = isin, totalFee = totalFee, managementFee = totalFee,
        category = null, fundType = IndexBenchmarkSelector.TAG_TYPE_BOND, companyName = null, risk = 2,
        indexFund = true, startDateEpochDay = null, minimumBuy = null,
        tags = listOf(FundTag(title = IndexBenchmarkSelector.TAG_TYPE_BOND, category = FundTag.CATEGORY_TYPE)),
    )

    /** Metadata för ett *innehav* — bara fondtypstaggen behövs, det är den exponeringen läser. */
    private fun holdingMetadata(isin: String, type: String) = FundMetadata(
        isin = isin, name = "Innehav $isin", orderbookId = isin, totalFee = 0.5, managementFee = 0.5,
        category = null, fundType = type, companyName = null, risk = 4, indexFund = false,
        startDateEpochDay = null, minimumBuy = null,
        tags = listOf(FundTag(title = type, category = FundTag.CATEGORY_TYPE)),
    )

    @Test
    fun `scanBenchmark speglar portfoljens aktieandel i en viktad blandning`() = runTest {
        // Hälften aktier, hälften räntor → en 50/50-referens, inte 100 % aktier. Utan
        // viktningen hade jämförelsen mätt tillgångsfördelning i stället för fondval.
        funds.value = listOf(
            Fund(fundId = "AKT", name = "Aktiefond", isin = "SE_H_AKT"),
            Fund(fundId = "RNT", name = "Räntefond", isin = "SE_H_RNT"),
        )
        transactions.value = listOf(
            buy("AKT", shares = 10.0, pricePerShare = 100.0),
            buy("RNT", shares = 10.0, pricePerShare = 100.0),
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
