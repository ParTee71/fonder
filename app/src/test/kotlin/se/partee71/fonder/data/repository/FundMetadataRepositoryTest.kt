package se.partee71.fonder.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.network.AvanzaSource
import se.partee71.fonder.data.room.daos.FundMetadataDao
import se.partee71.fonder.data.room.entities.FundMetadataEntity
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.usecase.FundMetadataFreshness
import java.io.IOException
import java.time.LocalDate

private class FakeAvanzaSource(var response: String = """{"fundListViews":[],"totalNoFunds":0,"filterCounts":{}}""") : AvanzaSource {
    var lastRequestBody: String? = null
    var fetchFundListCallCount = 0
    private val queue = ArrayDeque<String>()

    /** Köar svar som returneras i turordning, ett per anrop — [response] används när kön är tom. */
    fun enqueue(vararg responses: String) {
        queue.addAll(responses)
    }

    override suspend fun search(query: String): String = ""
    override suspend fun fetchGuide(orderbookId: String): String = ""
    override suspend fun fetchChart(orderbookId: String, from: LocalDate, to: LocalDate): String = ""
    override suspend fun fetchFundList(requestBody: String): String {
        lastRequestBody = requestBody
        fetchFundListCallCount++
        return if (queue.isNotEmpty()) queue.removeFirst() else response
    }
}

private class FailingAvanzaSource : AvanzaSource {
    override suspend fun search(query: String): String = ""
    override suspend fun fetchGuide(orderbookId: String): String = ""
    override suspend fun fetchChart(orderbookId: String, from: LocalDate, to: LocalDate): String = ""
    override suspend fun fetchFundList(requestBody: String): String = throw IOException("nätverksfel")
}

private class FakeFundMetadataDao : FundMetadataDao {
    val stored = mutableMapOf<String, FundMetadataEntity>()
    override suspend fun getAll(): List<FundMetadataEntity> = stored.values.toList()
    override suspend fun getByIsin(isin: String): FundMetadataEntity? = stored[isin]
    override suspend fun upsert(row: FundMetadataEntity) { stored[row.isin] = row }
    override suspend fun upsertAll(rows: List<FundMetadataEntity>) { rows.forEach { stored[it.isin] = it } }
    override suspend fun deleteAll() { stored.clear() }
}

private class FakeFundPriceRepository(
    private val catalog: FundCatalog = FundCatalog(companies = emptyList(), funds = emptyList()),
    private val isinByFundId: Map<String, String> = emptyMap(),
) : FundPriceRepository {
    var fetchFundCatalogCallCount = 0
    var lookupIsinCallCount = 0

    override suspend fun latestPrice(fundId: String): FundPrice? = null
    override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = flowOf(emptyMap())
    override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice> = emptyList()
    override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())
    override suspend fun refresh(fundId: String, since: LocalDate?): Boolean = false
    override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate): Boolean = false
    override suspend fun suggestIsin(fundName: String): String? = null
    override suspend fun findFundByIsin(isin: String): Fund? = null

    override suspend fun lookupIsin(fundId: String): String? {
        lookupIsinCallCount++
        return isinByFundId[fundId]
    }

    override suspend fun fetchFundCatalog(): FundCatalog {
        fetchFundCatalogCallCount++
        return catalog
    }

    override suspend fun fetchFundsForCompany(companyId: String): List<Fund>? = null
}

class FundMetadataRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferencesRepository: PreferencesRepository
    private val dao = FakeFundMetadataDao()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        dataStore = PreferenceDataStoreFactory.create(produceFile = { tempFolder.newFile("fund_metadata_test.preferences_pb") })
        preferencesRepository = PreferencesRepository(dataStore)
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    private fun repo(source: AvanzaSource, fundPriceRepository: FundPriceRepository = FakeFundPriceRepository()) =
        AvanzaFundMetadataRepository(source, dao, preferencesRepository, fundPriceRepository)

    private fun fundListJson(totalNoFunds: Int, funds: List<Triple<String, String, String>>): String {
        val views = funds.joinToString(",") { (isin, name, region) ->
            """{"isin":"$isin","name":"$name","orderbookId":"$isin","totalFee":0.2,
                "tagList":[{"title":"$region","fundTagCategory":"COMMON_REGION"}]}"""
        }
        return """
            {"fundListViews":[$views],"totalNoFunds":$totalNoFunds,
             "filterCounts":{"fundTypeCounts":[{"title":"Aktiefond","count":10,"type":"fundType","active":false,"group":0}]}}
        """.trimIndent()
    }

    /** Full kontroll över en enskild rad (avgift, indexstatus, godtyckliga taggar) — issue #59. Risknivå/12-månadersavkastning tillagda i issue #70 (bytesplanen). */
    private fun fundView(
        isin: String,
        name: String,
        totalFee: Double?,
        indexFund: Boolean,
        tags: List<Pair<String, String>>,
        risk: Int? = null,
        developmentOneYear: Double? = null,
    ): String {
        val feeField = if (totalFee != null) "\"totalFee\":$totalFee," else ""
        val riskField = if (risk != null) "\"risk\":$risk," else ""
        val developmentField = if (developmentOneYear != null) "\"developmentOneYear\":$developmentOneYear," else ""
        val tagList = tags.joinToString(",") { (title, category) -> """{"title":"$title","fundTagCategory":"$category"}""" }
        return """{"isin":"$isin","name":"$name","orderbookId":"$isin",$feeField$riskField$developmentField"indexFund":$indexFund,"tagList":[$tagList]}"""
    }

    private fun fullListJson(totalNoFunds: Int, views: List<String>): String =
        """{"fundListViews":[${views.joinToString(",")}],"totalNoFunds":$totalNoFunds,"filterCounts":{}}"""

    @Test
    fun `query utan filter cachar traffarna och persisterar vokabularen`() = runTest {
        val source = FakeAvanzaSource(fundListJson(1499, listOf(Triple("SE1", "Fond Ett", "Sverige"))))
        val repository = repo(source)

        val result = repository.query(FundScreenQuery())

        assertEquals(1, result.size)
        assertEquals("SE1", dao.stored.keys.first())
        assertEquals(listOf("Aktiefond"), preferencesRepository.fundFilterVocabulary.first().filters["fundType"])
    }

    @Test
    fun `query med filter och annan totalNoFunds an baslinjen anvander live-resultatet direkt`() = runTest {
        val source = FakeAvanzaSource()
        val repository = repo(source)
        source.response = fundListJson(1499, listOf(Triple("SE1", "Fond Ett", "Sverige"), Triple("SE2", "Fond Två", "Global")))
        repository.query(FundScreenQuery())

        source.response = fundListJson(28, listOf(Triple("SE3", "Fond Tre", "Sverige")))
        val result = repository.query(FundScreenQuery(region = listOf("Sverige")))

        assertEquals(listOf("SE3"), result.map { it.isin })
    }

    @Test
    fun `query dar kallan tyst ignorerar filtret filtrerar lokalt over cachen`() = runTest {
        val source = FakeAvanzaSource()
        val repository = repo(source)
        // Baslinje: obefiltrerad fråga ger 1499 träffar och cachar två fonder med olika region.
        source.response = fundListJson(1499, listOf(Triple("SE1", "Fond Ett", "Sverige"), Triple("SE2", "Fond Två", "Global")))
        repository.query(FundScreenQuery())

        // Källan "glömmer" att filtrera — ger tillbaka SAMMA totalNoFunds som baslinjen trots
        // ett satt filter (samma verifierade felläge som `trallallaFilter`, TP-21).
        source.response = fundListJson(1499, listOf(Triple("SE1", "Fond Ett", "Sverige"), Triple("SE2", "Fond Två", "Global")))
        val result = repository.query(FundScreenQuery(region = listOf("Sverige")))

        assertEquals(listOf("SE1"), result.map { it.isin })
    }

    @Test
    fun `query vid natverksfel faller tillbaka pa cachen`() = runTest {
        dao.stored["SE1"] = FundMetadataEntity(
            isin = "SE1", name = "Fond Ett", orderbookId = "SE1", totalFee = 0.2, managementFee = 0.2,
            category = null, fundType = null, companyName = null, risk = null, indexFund = false,
            startDateEpochDay = null, minimumBuy = null,
            tagsJson = """[{"title":"Sverige","category":"COMMON_REGION"}]""",
            availableAtHandelsbanken = null, availabilityResolvedAtEpochDay = null, fetchedAtEpochDay = 0,
        )
        val repository = repo(FailingAvanzaSource())

        val result = repository.query(FundScreenQuery(region = listOf("Sverige")))

        assertEquals(listOf("SE1"), result.map { it.isin })
    }

    @Test
    fun `query bevarar tidigare uppslagen kopbarhet nar samma fond dyker upp i ett nytt livesvar`() = runTest {
        dao.stored["SE1"] = fundMetadataEntity(
            isin = "SE1", name = "Fond Ett",
            availableAtHandelsbanken = true,
            availabilityResolvedAtEpochDay = LocalDate.now().toEpochDay(),
        )
        val source = FakeAvanzaSource(fundListJson(1499, listOf(Triple("SE1", "Fond Ett", "Sverige"))))
        val repository = repo(source)

        repository.query(FundScreenQuery())

        assertEquals(true, dao.stored["SE1"]?.availableAtHandelsbanken)
    }

    @Test
    fun `resolveHandelsbankenAvailability fond som inte finns i cachen ger null`() = runTest {
        val fundPriceRepo = FakeFundPriceRepository()
        val repository = repo(FakeAvanzaSource(), fundPriceRepo)

        assertNull(repository.resolveHandelsbankenAvailability("OKAND"))
        assertEquals(0, fundPriceRepo.fetchFundCatalogCallCount)
    }

    @Test
    fun `resolveHandelsbankenAvailability isin-verifierad traff satter true och cachar`() = runTest {
        dao.stored["SE1"] = fundMetadataEntity(isin = "SE1", name = "Länsförsäkringar Sverige Index")
        val fundPriceRepo = FakeFundPriceRepository(
            catalog = FundCatalog(
                companies = emptyList(),
                funds = listOf(Fund(fundId = "X1", name = "Länsförsäkringar Sverige Index", currency = "SEK")),
            ),
            isinByFundId = mapOf("X1" to "SE1"),
        )
        val repository = repo(FakeAvanzaSource(), fundPriceRepo)

        val available = repository.resolveHandelsbankenAvailability("SE1")

        assertEquals(true, available)
        assertEquals(true, dao.stored["SE1"]?.availableAtHandelsbanken)
        assertTrue((dao.stored["SE1"]?.availabilityResolvedAtEpochDay ?: -1) > 0)
    }

    @Test
    fun `resolveHandelsbankenAvailability ingen verifierad traff ger false och cachar missen`() = runTest {
        dao.stored["SE1"] = fundMetadataEntity(isin = "SE1", name = "Helt Okänd Fond")
        val fundPriceRepo = FakeFundPriceRepository(catalog = FundCatalog(companies = emptyList(), funds = emptyList()))
        val repository = repo(FakeAvanzaSource(), fundPriceRepo)

        val available = repository.resolveHandelsbankenAvailability("SE1")

        assertEquals(false, available)
        assertEquals(false, dao.stored["SE1"]?.availableAtHandelsbanken)
    }

    @Test
    fun `resolveHandelsbankenAvailability redan farsk cache anvands utan ny uppslagning`() = runTest {
        dao.stored["SE1"] = fundMetadataEntity(
            isin = "SE1", name = "Fond",
            availableAtHandelsbanken = true,
            availabilityResolvedAtEpochDay = LocalDate.now().toEpochDay(),
        )
        val fundPriceRepo = FakeFundPriceRepository()
        val repository = repo(FakeAvanzaSource(), fundPriceRepo)

        val available = repository.resolveHandelsbankenAvailability("SE1")

        assertEquals(true, available)
        assertEquals(0, fundPriceRepo.fetchFundCatalogCallCount)
    }

    @Test
    fun `resolveHandelsbankenAvailability inaktuell cache loser upp pa nytt`() = runTest {
        dao.stored["SE1"] = fundMetadataEntity(
            isin = "SE1", name = "Länsförsäkringar Sverige Index",
            availableAtHandelsbanken = false,
            availabilityResolvedAtEpochDay = LocalDate.now().minusDays(40).toEpochDay(),
        )
        val fundPriceRepo = FakeFundPriceRepository(
            catalog = FundCatalog(
                companies = emptyList(),
                funds = listOf(Fund(fundId = "X1", name = "Länsförsäkringar Sverige Index", currency = "SEK")),
            ),
            isinByFundId = mapOf("X1" to "SE1"),
        )
        val repository = repo(FakeAvanzaSource(), fundPriceRepo)

        val available = repository.resolveHandelsbankenAvailability("SE1")

        assertEquals(true, available)
        assertEquals(1, fundPriceRepo.fetchFundCatalogCallCount)
    }

    @Test
    fun `suggestCheaperAlternatives slar upp innehavet via isin, bygger kandidatfraga och verifierar kopbarhet`() = runTest {
        val heldTags = listOf("Aktiefond" to "TYPE", "Sverige" to "COMMON_REGION", "Index" to "INDEX")
        val heldView = fundView("SE_HELD", "Handelsbanken Sverige Index Criteria", totalFee = 0.73, indexFund = true, tags = heldTags)
        val candidateView = fundView("SE_CAND", "Länsförsäkringar Sverige Index", totalFee = 0.21, indexFund = true, tags = heldTags)
        val source = FakeAvanzaSource()
        source.enqueue(fullListJson(1, listOf(heldView)), fullListJson(1, listOf(candidateView)))
        val fundPriceRepo = FakeFundPriceRepository(
            catalog = FundCatalog(
                companies = emptyList(),
                funds = listOf(Fund(fundId = "X1", name = "Länsförsäkringar Sverige Index", currency = "SEK")),
            ),
            isinByFundId = mapOf("X1" to "SE_CAND"),
        )
        val repository = repo(source, fundPriceRepo)

        val result = repository.suggestCheaperAlternatives("SE_HELD", holdingValue = 300_000.0)

        assertEquals(1, result?.size)
        assertEquals("SE_CAND", result?.first()?.candidate?.isin)
        // (0,73 - 0,21) / 100 * 300 000 = 1 560 kr/år.
        assertEquals(1560.0, result?.first()?.annualSavingsKr ?: -1.0, 0.5)
        // Kandidatfrågan byggdes ur innehavets egna taggar (fundType/region), inte fritt.
        assertTrue(source.lastRequestBody?.contains("\"fundTypeFilter\":[\"Aktiefond\"]") == true)
        assertTrue(source.lastRequestBody?.contains("\"commonRegionFilter\":[\"Sverige\"]") == true)

        // Sparat för portföljens samlade besparingspotential (HEM-6, issue #61) — det
        // billigaste verifierade alternativets ISIN och avgift, plus dagens datum.
        val stored = dao.stored["SE_HELD"]
        assertEquals("SE_CAND", stored?.cheapestAlternativeIsin)
        assertEquals(0.21, stored?.cheapestAlternativeFee ?: -1.0, 1e-9)
        assertEquals(LocalDate.now().toEpochDay(), stored?.comparisonResolvedAtEpochDay)
    }

    @Test
    fun `suggestCheaperAlternatives sparar sokt-utan-traff, skilt fran aldrig sokt`() = runTest {
        val heldTags = listOf("Aktiefond" to "TYPE", "Sverige" to "COMMON_REGION")
        val heldView = fundView("SE_HELD", "Innehavet", totalFee = 0.5, indexFund = false, tags = heldTags)
        val source = FakeAvanzaSource()
        source.enqueue(fullListJson(1, listOf(heldView)), fullListJson(0, emptyList()))
        val repository = repo(source)

        val result = repository.suggestCheaperAlternatives("SE_HELD", 300_000.0)

        assertEquals(emptyList<Any>(), result)
        val stored = dao.stored["SE_HELD"]
        // Datumet är satt (sökningen gjordes) men isinet är null (inget billigare hittades) —
        // den distinktionen är precis vad som skiljer "genomsökt utan träff" från "aldrig sökt".
        assertNull(stored?.cheapestAlternativeIsin)
        assertEquals(LocalDate.now().toEpochDay(), stored?.comparisonResolvedAtEpochDay)
    }

    @Test
    fun `suggestCheaperAlternatives fond utan kand avgift ger null`() = runTest {
        val heldView = fundView("SE_HELD", "Fond utan avgift", totalFee = null, indexFund = false, tags = emptyList())
        val repository = repo(FakeAvanzaSource(fullListJson(1, listOf(heldView))))

        assertNull(repository.suggestCheaperAlternatives("SE_HELD", 300_000.0))
    }

    @Test
    fun `suggestCheaperAlternatives fond som inte finns i kallans universum ger null`() = runTest {
        val repository = repo(FakeAvanzaSource(fullListJson(0, emptyList())))

        assertNull(repository.suggestCheaperAlternatives("OKAND_ISIN", 300_000.0))
    }

    @Test
    fun `suggestCheaperAlternatives tom lista om inga kandidater kvalificerar`() = runTest {
        val heldTags = listOf("Aktiefond" to "TYPE", "Sverige" to "COMMON_REGION")
        val heldView = fundView("SE_HELD", "Innehavet", totalFee = 0.5, indexFund = false, tags = heldTags)
        val source = FakeAvanzaSource()
        source.enqueue(fullListJson(1, listOf(heldView)), fullListJson(0, emptyList()))
        val repository = repo(source)

        val result = repository.suggestCheaperAlternatives("SE_HELD", 300_000.0)

        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `suggestCheaperAlternatives stannar efter tre bekraftade alternativ`() = runTest {
        val heldTags = listOf("Aktiefond" to "TYPE", "Sverige" to "COMMON_REGION")
        val heldView = fundView("SE_HELD", "Innehavet", totalFee = 0.73, indexFund = false, tags = heldTags)
        val candidateFees = listOf(0.10, 0.15, 0.20, 0.25, 0.30)
        val candidateViews = candidateFees.mapIndexed { i, fee ->
            fundView("SE_C$i", "Kandidat $i", totalFee = fee, indexFund = false, tags = heldTags)
        }
        val source = FakeAvanzaSource()
        source.enqueue(fullListJson(1, listOf(heldView)), fullListJson(candidateViews.size, candidateViews))
        val catalogFunds = candidateFees.indices.map { i -> Fund(fundId = "F$i", name = "Kandidat $i", currency = "SEK") }
        val isinByFundId = candidateFees.indices.associate { i -> "F$i" to "SE_C$i" }
        val fundPriceRepo = FakeFundPriceRepository(
            catalog = FundCatalog(companies = emptyList(), funds = catalogFunds),
            isinByFundId = isinByFundId,
        )
        val repository = repo(source, fundPriceRepo)

        val result = repository.suggestCheaperAlternatives("SE_HELD", 300_000.0)

        // Alla 5 kandidater hade varit köpbara — budgeten (högst 3 visade) stoppar tidigt.
        assertEquals(3, result?.size)
        assertEquals(3, fundPriceRepo.fetchFundCatalogCallCount)
    }

    @Test
    fun `suggestCheaperAlternatives stannar efter tio provade aven om inga bekraftas`() = runTest {
        val heldTags = listOf("Aktiefond" to "TYPE", "Sverige" to "COMMON_REGION")
        val heldView = fundView("SE_HELD", "Innehavet", totalFee = 0.73, indexFund = false, tags = heldTags)
        val candidateViews = (1..12).map { i ->
            fundView("SE_C$i", "Kandidat $i", totalFee = 0.73 - i * 0.01, indexFund = false, tags = heldTags)
        }
        val source = FakeAvanzaSource()
        source.enqueue(fullListJson(1, listOf(heldView)), fullListJson(candidateViews.size, candidateViews))
        // Tom katalog — ingen kandidat kan någonsin ISIN-verifieras som köpbar.
        val fundPriceRepo = FakeFundPriceRepository(catalog = FundCatalog(companies = emptyList(), funds = emptyList()))
        val repository = repo(source, fundPriceRepo)

        val result = repository.suggestCheaperAlternatives("SE_HELD", 300_000.0)

        assertEquals(emptyList<Any>(), result)
        // 12 kvalificerade kandidater fanns — budgeten (högst 10 prövade) stoppar innan alla testats.
        assertEquals(10, fundPriceRepo.fetchFundCatalogCallCount)
    }

    @Test
    fun `suggestCheaperAlternatives isin-uppslagning fororenar inte baslinjen for senare kategoriska fragor`() = runTest {
        val source = FakeAvanzaSource()
        val repository = repo(source)

        // 1) En obefiltrerad fråga sätter den sanna baslinjen till 1499. Regionen på fonden i
        // svaret spelar ingen roll för baslinjen (bara totalNoFunds gör), men den skrivs
        // igenom till cachen av query() — "Global" i stället för "Sverige" så att den inte kan
        // råka matcha det Sverige-filtrerade steg 3 nedan och maskera resultatet.
        source.response = fundListJson(1499, listOf(Triple("SE_BASE", "Bas", "Global")))
        repository.query(FundScreenQuery())

        // 2) suggestCheaperAlternatives ISIN-slår upp innehavet (via findByIsin, som medvetet
        // inte går via query()) och kör sedan en kandidatfråga för ett innehav UTAN egna
        // dimension-taggar — den frågan har inget kategoriskt filter, bara maxTotalFee, och
        // GÅR via query(). Utan skyddet i query() (maxTotalFee/nameContains utesluter
        // baslinje-uppdatering) hade den kandidatfrågans totalNoFunds=0 förorenat baslinjen.
        val heldView = fundView("SE_HELD", "Innehavet", totalFee = 0.5, indexFund = false, tags = emptyList())
        source.enqueue(fullListJson(1, listOf(heldView)), fullListJson(0, emptyList()))
        repository.suggestCheaperAlternatives("SE_HELD", 300_000.0)

        // 3) Cachen skiljer sig medvetet från källans (påstått tystade) live-svar — och
        // live-svarets fond har en ANNAN region än filtret (matchar alltså inte
        // "region=Sverige") så att den, även om den skrivs igenom till cachen (query()
        // cachar alltid det som kommer tillbaka), inte kan råka dyka upp i ett offline-
        // filtrerat resultat och maskera en förorenad baslinje. Testet kan därför skilja
        // mellan "baslinjen förorenad" (live-svaret SE_LIVE används rakt av) och "baslinjen
        // intakt" (källan upptäcks ha ignorerat filtret, frågan besvaras ur cachen i stället,
        // bara SE_CACHE matchar).
        dao.stored["SE_CACHE"] = fundMetadataEntity(isin = "SE_CACHE", name = "Cache Sverige")
            .copy(tagsJson = """[{"title":"Sverige","category":"COMMON_REGION"}]""")
        source.response = fundListJson(1499, listOf(Triple("SE_LIVE", "Live-svar", "Global")))

        val result = repository.query(FundScreenQuery(region = listOf("Sverige")))

        assertEquals(listOf("SE_CACHE"), result.map { it.isin })
    }

    // --- metadataFor (HEM-5, issue #60) ---

    @Test
    fun `metadataFor svarar ur cachen for farska rader utan natanrop`() = runTest {
        val today = LocalDate.now()
        dao.stored["SE1"] = fundMetadataEntity(isin = "SE1", name = "Fond Ett")
            .copy(totalFee = 0.73, fetchedAtEpochDay = today.toEpochDay())
        val source = FakeAvanzaSource()
        val repository = repo(source)

        val result = repository.metadataFor(listOf("SE1"))

        assertEquals(0.73, result["SE1"]?.totalFee ?: -1.0, 1e-9)
        assertEquals(0, source.fetchFundListCallCount)
    }

    @Test
    fun `metadataFor hamtar om en rad som ar aldre an FEE_TTL_DAYS`() = runTest {
        val today = LocalDate.now()
        dao.stored["SE1"] = fundMetadataEntity(isin = "SE1", name = "Fond Ett")
            .copy(totalFee = 0.73, fetchedAtEpochDay = today.minusDays(FundMetadataFreshness.FEE_TTL_DAYS + 1).toEpochDay())
        val source = FakeAvanzaSource(fullListJson(1, listOf(fundView("SE1", "Fond Ett", totalFee = 0.21, indexFund = false, tags = emptyList()))))
        val repository = repo(source)

        val result = repository.metadataFor(listOf("SE1"))

        assertEquals(0.21, result["SE1"]?.totalFee ?: -1.0, 1e-9)
        assertEquals(1, source.fetchFundListCallCount)
    }

    @Test
    fun `metadataFor fyller en helt saknad isin via natverket`() = runTest {
        val source = FakeAvanzaSource(fullListJson(1, listOf(fundView("SE1", "Fond Ett", totalFee = 0.5, indexFund = false, tags = emptyList()))))
        val repository = repo(source)

        val result = repository.metadataFor(listOf("SE1"))

        assertEquals(0.5, result["SE1"]?.totalFee ?: -1.0, 1e-9)
    }

    @Test
    fun `metadataFor utelamnar en isin som inte finns i kallans universum`() = runTest {
        val source = FakeAvanzaSource(fullListJson(0, emptyList()))
        val repository = repo(source)

        val result = repository.metadataFor(listOf("OKAND_ISIN"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `metadataFor ror aldrig baslinjen for senare kategoriska fragor`() = runTest {
        val source = FakeAvanzaSource()
        val repository = repo(source)

        // Sann baslinje.
        source.response = fundListJson(1499, listOf(Triple("SE_BASE", "Bas", "Global")))
        repository.query(FundScreenQuery())

        // metadataFor slår upp en enda fond (totalNoFunds=1) för ett innehav utan cachad rad.
        source.enqueue(fullListJson(1, listOf(fundView("SE_NY", "Ny fond", totalFee = 0.4, indexFund = false, tags = emptyList()))))
        repository.metadataFor(listOf("SE_NY"))

        // Samma teknik som den redan existerande baslinjeregressionen ovan: ett live-svar med
        // samma totalNoFunds som baslinjen ska tolkas som "källan ignorerar filtret" och
        // besvaras ur cachen — bara möjligt om baslinjen fortfarande är 1499, inte 1.
        dao.stored["SE_CACHE"] = fundMetadataEntity(isin = "SE_CACHE", name = "Cache Sverige")
            .copy(tagsJson = """[{"title":"Sverige","category":"COMMON_REGION"}]""")
        source.response = fundListJson(1499, listOf(Triple("SE_LIVE", "Live-svar", "Global")))

        val result = repository.query(FundScreenQuery(region = listOf("Sverige")))

        assertEquals(listOf("SE_CACHE"), result.map { it.isin })
    }

    // --- knownRiskLevels (SET-3, issue #68) ---

    @Test
    fun `knownRiskLevels tom nar varken vokabular eller cache kanner till nagon risknivå`() = runTest {
        val repository = repo(FakeAvanzaSource())

        assertTrue(repository.knownRiskLevels().isEmpty())
    }

    @Test
    fun `knownRiskLevels laser fran senast kanda vokabular`() = runTest {
        preferencesRepository.setFundFilterVocabulary(FundFilterVocabulary(filters = mapOf("risk" to listOf("1", "3", "6"))))
        val repository = repo(FakeAvanzaSource())

        assertEquals(listOf(1, 3, 6), repository.knownRiskLevels())
    }

    @Test
    fun `knownRiskLevels laser fran redan cachade fonders egen risk aven utan vokabular`() = runTest {
        dao.stored["SE1"] = fundMetadataEntity(isin = "SE1", name = "Fond Ett").copy(risk = 4)
        dao.stored["SE2"] = fundMetadataEntity(isin = "SE2", name = "Fond Två").copy(risk = 2)
        val repository = repo(FakeAvanzaSource())

        assertEquals(listOf(2, 4), repository.knownRiskLevels())
    }

    @Test
    fun `knownRiskLevels slar ihop vokabular och cache, sorterat utan dubbletter`() = runTest {
        preferencesRepository.setFundFilterVocabulary(FundFilterVocabulary(filters = mapOf("risk" to listOf("1", "4"))))
        dao.stored["SE1"] = fundMetadataEntity(isin = "SE1", name = "Fond Ett").copy(risk = 4)
        dao.stored["SE2"] = fundMetadataEntity(isin = "SE2", name = "Fond Två").copy(risk = 6)
        val repository = repo(FakeAvanzaSource())

        assertEquals(listOf(1, 4, 6), repository.knownRiskLevels())
    }

    // --- findSwitchCandidates (HEM-8, issue #70) ---

    @Test
    fun `findSwitchCandidates fragar ratt risknivå och verifierar kopbarhet`() = runTest {
        val candidateView = fundView("SE_CAND", "Kandidatfond", totalFee = 0.3, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.12)
        val source = FakeAvanzaSource(fullListJson(1, listOf(candidateView)))
        val fundPriceRepo = FakeFundPriceRepository(
            catalog = FundCatalog(companies = emptyList(), funds = listOf(Fund(fundId = "X1", name = "Kandidatfond", currency = "SEK"))),
            isinByFundId = mapOf("X1" to "SE_CAND"),
        )
        val repository = repo(source, fundPriceRepo)

        val result = repository.findSwitchCandidates(level = 3, excludeIsins = emptySet())

        val candidate = result.single()
        assertEquals("SE_CAND", candidate.metadata.isin)
        assertEquals(0.12, candidate.twelveMonthReturn, 1e-9)
        assertTrue("frågan mot källan ska filtrera på risknivå 3", source.lastRequestBody?.contains("\"riskFilter\":[\"3\"]") == true)
    }

    @Test
    fun `findSwitchCandidates utesluter kandidat utan kand 12-manadersavkastning`() = runTest {
        val candidateView = fundView("SE_CAND", "Utan avkastning", totalFee = 0.3, indexFund = false, tags = emptyList(), risk = 3)
        val source = FakeAvanzaSource(fullListJson(1, listOf(candidateView)))
        val fundPriceRepo = FakeFundPriceRepository(
            catalog = FundCatalog(companies = emptyList(), funds = listOf(Fund(fundId = "X1", name = "Utan avkastning", currency = "SEK"))),
            isinByFundId = mapOf("X1" to "SE_CAND"),
        )
        val repository = repo(source, fundPriceRepo)

        val result = repository.findSwitchCandidates(level = 3, excludeIsins = emptySet())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findSwitchCandidates utesluter isin i excludeIsins`() = runTest {
        val candidateView = fundView("SE_CAND", "Kandidatfond", totalFee = 0.3, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.12)
        val source = FakeAvanzaSource(fullListJson(1, listOf(candidateView)))
        val repository = repo(source)

        val result = repository.findSwitchCandidates(level = 3, excludeIsins = setOf("SE_CAND"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findSwitchCandidates ger tom lista nar ingen kandidat ar kopbar`() = runTest {
        val candidateView = fundView("SE_CAND", "Ej köpbar", totalFee = 0.3, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.12)
        val source = FakeAvanzaSource(fullListJson(1, listOf(candidateView)))
        val fundPriceRepo = FakeFundPriceRepository(catalog = FundCatalog(companies = emptyList(), funds = emptyList()))
        val repository = repo(source, fundPriceRepo)

        val result = repository.findSwitchCandidates(level = 3, excludeIsins = emptySet())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `findSwitchCandidates stannar vid fem bekraftade kandidater`() = runTest {
        val views = (1..8).map { i ->
            fundView("SE_C$i", "Kandidat $i", totalFee = 0.2, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.1)
        }
        val source = FakeAvanzaSource(fullListJson(8, views))
        val catalogFunds = (1..8).map { i -> Fund(fundId = "X$i", name = "Kandidat $i", currency = "SEK") }
        val fundPriceRepo = FakeFundPriceRepository(
            catalog = FundCatalog(companies = emptyList(), funds = catalogFunds),
            isinByFundId = (1..8).associate { i -> "X$i" to "SE_C$i" },
        )
        val repository = repo(source, fundPriceRepo)

        val result = repository.findSwitchCandidates(level = 3, excludeIsins = emptySet())

        assertEquals(5, result.size)
    }

    private fun fundMetadataEntity(
        isin: String,
        name: String,
        availableAtHandelsbanken: Boolean? = null,
        availabilityResolvedAtEpochDay: Long? = null,
    ) = FundMetadataEntity(
        isin = isin, name = name, orderbookId = isin, totalFee = 0.2, managementFee = 0.2,
        category = null, fundType = null, companyName = null, risk = null, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tagsJson = "[]",
        availableAtHandelsbanken = availableAtHandelsbanken,
        availabilityResolvedAtEpochDay = availabilityResolvedAtEpochDay,
        fetchedAtEpochDay = 0,
    )
}
