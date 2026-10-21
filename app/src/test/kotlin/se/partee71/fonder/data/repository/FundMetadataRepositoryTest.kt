package se.partee71.fonder.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.network.AvanzaSource
import se.partee71.fonder.data.room.daos.FundMetadataDao
import se.partee71.fonder.data.room.daos.FundNameRisk
import se.partee71.fonder.data.room.entities.FundMetadataEntity
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.usecase.FundMetadataFreshness
import se.partee71.fonder.domain.usecase.FundNameKey

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
    override suspend fun getByIsins(isins: List<String>): List<FundMetadataEntity> = isins.mapNotNull { stored[it] }
    override suspend fun getKnownRisks(): List<FundNameRisk> = stored.values.mapNotNull { row -> row.risk?.let { FundNameRisk(row.name, it) } }
    override suspend fun upsert(row: FundMetadataEntity) {
        stored[row.isin] = row
    }
    override suspend fun upsertAll(rows: List<FundMetadataEntity>) {
        rows.forEach { stored[it.isin] = it }
    }
    override suspend fun deleteAll() {
        stored.clear()
    }
}

/**
 * En katalog som hämtades men inte innehåller testets fonder — ett riktigt "finns inte". En **tom**
 * katalog är sedan issue #153 ett svar som inte kom fram och ger null, inte false.
 */
private val UNRELATED_CATALOG = catalogOf(Fund(fundId = "XZ", name = "Orelaterad Räntefond Kort", currency = "SEK"))

/** Fondkatalogen med bara [funds] (inga fondbolag). */
private fun catalogOf(vararg funds: Fund) = FundCatalog(companies = emptyList(), funds = funds.toList())

/** Katalogfonden med samma namn som metadataraden SE1 — uppslaget verifierar den mot ISIN:et. */
private val LF_SVERIGE_INDEX_X1 = Fund(fundId = "X1", name = "Länsförsäkringar Sverige Index", currency = "SEK")

private class FakeFundPriceRepository(
    /** Null = katalogen kunde inte hämtas (nätverksfel), skilt från en tom katalog. */
    var catalog: FundCatalog? = UNRELATED_CATALOG,
    private val isinByFundId: Map<String, String> = emptyMap()
) : FundPriceRepository by FakeCachedPriceRepository() {
    var fetchFundCatalogCallCount = 0
    var lookupIsinCallCount = 0

    // Ingen kurs hämtas här; metadatalagret frågar bara katalogen och ISIN-uppslaget.
    override suspend fun refresh(fundId: String, since: LocalDate?): Boolean = false
    override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate): Boolean = false

    override suspend fun lookupIsin(fundId: String): String? {
        lookupIsinCallCount++
        return isinByFundId[fundId]
    }

    override suspend fun fetchFundCatalog(): FundCatalog? {
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

    private fun repo(source: AvanzaSource, fundPriceRepository: FundPriceRepository = FakeFundPriceRepository()) = AvanzaFundMetadataRepository(source, dao, preferencesRepository, fundPriceRepository)

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

    /** Taggarna för en svensk aktiefond — innehavet och dess kandidater i jämförelsetesterna (ANA-9). */
    private val swedishEquityTags = listOf("Aktiefond" to "TYPE", "Sverige" to "COMMON_REGION")

    /** Innehavet SE_HELD: en aktiv svensk aktiefond med avgiften [totalFee]. */
    private fun heldSwedishEquityView(totalFee: Double) = fundView("SE_HELD", "Innehavet", totalFee = totalFee, indexFund = false, tags = swedishEquityTags)

    /** Full kontroll över en enskild rad (avgift, indexstatus, godtyckliga taggar) — issue #59. Risknivå/12-månadersavkastning tillagda i issue #70 (bytesplanen). */
    private fun fundView(
        isin: String,
        name: String,
        totalFee: Double?,
        indexFund: Boolean,
        tags: List<Pair<String, String>>,
        risk: Int? = null,
        developmentOneYear: Double? = null
    ): String {
        val feeField = if (totalFee != null) "\"totalFee\":$totalFee," else ""
        val riskField = if (risk != null) "\"risk\":$risk," else ""
        val developmentField = if (developmentOneYear != null) "\"developmentOneYear\":$developmentOneYear," else ""
        val tagList = tags.joinToString(",") { (title, category) -> """{"title":"$title","fundTagCategory":"$category"}""" }
        return """{"isin":"$isin","name":"$name","orderbookId":"$isin",$feeField$riskField$developmentField"indexFund":$indexFund,"tagList":[$tagList]}"""
    }

    /** En redan cachad metadatarad — för cache-bara läsvägar (SET-5, issue #80). */
    private fun cachedEntity(isin: String, name: String, fetchedAtEpochDay: Long = LocalDate.now().toEpochDay()) = FundMetadataEntity(
        isin = isin, name = name, orderbookId = isin, totalFee = 0.2, managementFee = 0.2,
        category = null, fundType = null, companyName = null, risk = 4, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tagsJson = "[]",
        availableAtHandelsbanken = null, availabilityResolvedAtEpochDay = null,
        fetchedAtEpochDay = fetchedAtEpochDay
    )

    private fun fullListJson(totalNoFunds: Int, views: List<String>): String = """{"fundListViews":[${views.joinToString(",")}],"totalNoFunds":$totalNoFunds,"filterCounts":{}}"""

    // --- cachedMetadataFor: cache-bara uppslag för facit (SET-5, issue #80) ---

    @Test
    fun `cachedMetadataFor laser ur cachen utan att rora kallan`() = runTest {
        // Facit slår upp fondnamn för varje inspelat förslag, och historiken kan innehålla
        // hundratals ISIN — en cache-först-läsning hade blivit en burst av nätverksuppslag
        // varje gång skärmen öppnas. Källan här kastar vid varje anrop: skulle metoden gå dit
        // fallerar testet i stället för att tyst degradera.
        val source = FailingAvanzaSource()
        dao.upsert(cachedEntity("SE1", "Fond Ett"))
        dao.upsert(cachedEntity("SE2", "Fond Två"))

        val result = repo(source).cachedMetadataFor(listOf("SE1", "SE2"))

        assertEquals(setOf("SE1", "SE2"), result.keys)
        assertEquals("Fond Ett", result["SE1"]?.name)
    }

    @Test
    fun `cachedMetadataFor utelamnar okanda isin i stallet for att hamta dem`() = runTest {
        val source = FailingAvanzaSource()
        dao.upsert(cachedEntity("SE1", "Fond Ett"))

        val result = repo(source).cachedMetadataFor(listOf("SE1", "SAKNAS"))

        assertEquals(setOf("SE1"), result.keys)
    }

    @Test
    fun `cachedMetadataFor hamtar aldrig om en inaktuell rad`() = runTest {
        // Till skillnad från metadataFor, som har en färskhetsgate: ett inaktuellt *namn* är
        // fortfarande rätt namn, och namnet är allt facit behöver.
        val source = FailingAvanzaSource()
        dao.upsert(cachedEntity("SE1", "Fond Ett", fetchedAtEpochDay = 0))

        val result = repo(source).cachedMetadataFor(listOf("SE1"))

        assertEquals("Fond Ett", result["SE1"]?.name)
    }

    @Test
    fun `cachedMetadataFor med tom lista ger tom karta`() = runTest {
        assertTrue(repo(FailingAvanzaSource()).cachedMetadataFor(emptyList()).isEmpty())
    }

    // --- cachedRiskByFundName: risknivå för fondsök (UI-10, issue #85) ---

    @Test
    fun `cachedRiskByFundName nycklar pa normaliserat namn utan att rora kallan`() = runTest {
        // Fondsök visar ~1500 katalogfonder utan ISIN — uppslaget måste gå via namnet och får
        // aldrig kosta nätverk. Källan kastar vid varje anrop: går metoden dit fallerar testet.
        dao.upsert(cachedEntity("SE1", "Handelsbanken Sverige Index (A1 SEK)"))

        val result = repo(FailingAvanzaSource()).cachedRiskByFundName()

        assertEquals(4, result[FundNameKey.of("handelsbanken sverige index a1 sek")])
    }

    @Test
    fun `cachedRiskByFundName utelamnar fonder utan kand risknniva`() = runTest {
        dao.upsert(cachedEntity("SE1", "Fond Ett").copy(risk = null))

        assertTrue(repo(FailingAvanzaSource()).cachedRiskByFundName().isEmpty())
    }

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
            availableAtHandelsbanken = null, availabilityResolvedAtEpochDay = null, fetchedAtEpochDay = 0
        )
        val repository = repo(FailingAvanzaSource())

        val result = repository.query(FundScreenQuery(region = listOf("Sverige")))

        assertEquals(listOf("SE1"), result.map { it.isin })
    }

    @Test
    fun `query bevarar tidigare uppslagen kopbarhet nar samma fond dyker upp i ett nytt livesvar`() = runTest {
        dao.stored["SE1"] = fundMetadataEntity(
            isin = "SE1",
            name = "Fond Ett",
            availableAtHandelsbanken = true,
            availabilityResolvedAtEpochDay = LocalDate.now().toEpochDay()
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
            catalog = catalogOf(LF_SVERIGE_INDEX_X1),
            isinByFundId = mapOf("X1" to "SE1")
        )
        val repository = repo(FakeAvanzaSource(), fundPriceRepo)

        val available = repository.resolveHandelsbankenAvailability("SE1")

        assertEquals(true, available)
        assertEquals(true, dao.stored["SE1"]?.availableAtHandelsbanken)
        assertTrue((dao.stored["SE1"]?.availabilityResolvedAtEpochDay ?: -1) > 0)
    }

    @Test
    fun `resolveHandelsbankenAvailability ingen verifierad traff ger false och cachar missen`() = runTest {
        // Katalogen hämtades men innehöll ingen fond som ISIN-verifierade — ett riktigt svar,
        // som därför får cachas. Jämför testet nedan, där katalogen inte gick att hämta alls.
        dao.stored["SE1"] = fundMetadataEntity(isin = "SE1", name = "Länsförsäkringar Sverige Index")
        val fundPriceRepo = FakeFundPriceRepository(
            catalog = FundCatalog(
                companies = emptyList(),
                funds = listOf(
                    LF_SVERIGE_INDEX_X1,
                    Fund(fundId = "X2", name = "Helt Annan Fond", currency = "SEK")
                )
            ),
            isinByFundId = mapOf("X1" to "SE_SYSKON", "X2" to "SE2")
        )
        val repository = repo(FakeAvanzaSource(), fundPriceRepo)

        val available = repository.resolveHandelsbankenAvailability("SE1")

        assertEquals(false, available)
        assertEquals(false, dao.stored["SE1"]?.availableAtHandelsbanken)
    }

    @Test
    fun `resolveHandelsbankenAvailability tom katalog ger null och cachar inget`() = runTest {
        // Issue #153: källans brandväggskontroll gav en tom katalog, som lästes som "går inte att
        // köpa" och cachades i 30 dygn. Källan listar ~1500 fonder — tom är ett svar som inte kom fram.
        dao.stored["SE1"] = fundMetadataEntity(isin = "SE1", name = "Helt Okänd Fond")
        val fundPriceRepo = FakeFundPriceRepository(catalog = FundCatalog(companies = emptyList(), funds = emptyList()))
        val repository = repo(FakeAvanzaSource(), fundPriceRepo)

        assertNull(repository.resolveHandelsbankenAvailability("SE1"))
        assertNull(dao.stored["SE1"]?.availableAtHandelsbanken)
        assertNull(dao.stored["SE1"]?.availabilityResolvedAtEpochDay)
    }

    @Test
    fun `resolveHandelsbankenAvailability olast fondsida ger null i stallet for false`() = runTest {
        // En namnträff vars sida inte gick att läsa (lookupIsin null) är okänd, inte en miss.
        dao.stored["SE1"] = fundMetadataEntity(isin = "SE1", name = "Länsförsäkringar Sverige Index")
        val fundPriceRepo = FakeFundPriceRepository(
            catalog = catalogOf(LF_SVERIGE_INDEX_X1),
            isinByFundId = emptyMap()
        )
        val repository = repo(FakeAvanzaSource(), fundPriceRepo)

        assertNull(repository.resolveHandelsbankenAvailability("SE1"))
        assertNull(dao.stored["SE1"]?.availableAtHandelsbanken)
    }

    @Test
    fun `resolveHandelsbankenAvailability utan katalog ger null, cachar inget och forsoker igen`() = runTest {
        // Regression: en katalog som inte kunde hämtas gav tidigare ett `false` som såg
        // auktoritativt ut och cachades i AVAILABILITY_TTL_DAYS dygn — en enda offline-körning
        // kunde därmed tyst slå ut både ANA-9 och hela bytesplanen i en månad.
        dao.stored["SE1"] = fundMetadataEntity(isin = "SE1", name = "Länsförsäkringar Sverige Index")
        val fundPriceRepo = FakeFundPriceRepository(catalog = null, isinByFundId = mapOf("X1" to "SE1"))
        val repository = repo(FakeAvanzaSource(), fundPriceRepo)

        assertNull(repository.resolveHandelsbankenAvailability("SE1"))
        assertNull(dao.stored["SE1"]?.availableAtHandelsbanken)
        assertNull(dao.stored["SE1"]?.availabilityResolvedAtEpochDay)

        // Ingen cachad miss står i vägen när katalogen är tillgänglig igen.
        fundPriceRepo.catalog = catalogOf(LF_SVERIGE_INDEX_X1)

        assertEquals(true, repository.resolveHandelsbankenAvailability("SE1"))
        assertEquals(true, dao.stored["SE1"]?.availableAtHandelsbanken)
    }

    @Test
    fun `resolveHandelsbankenAvailability redan farsk cache anvands utan ny uppslagning`() = runTest {
        dao.stored["SE1"] = fundMetadataEntity(
            isin = "SE1",
            name = "Fond",
            availableAtHandelsbanken = true,
            availabilityResolvedAtEpochDay = LocalDate.now().toEpochDay()
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
            isin = "SE1",
            name = "Länsförsäkringar Sverige Index",
            availableAtHandelsbanken = false,
            availabilityResolvedAtEpochDay = LocalDate.now().minusDays(40).toEpochDay()
        )
        val fundPriceRepo = FakeFundPriceRepository(
            catalog = catalogOf(LF_SVERIGE_INDEX_X1),
            isinByFundId = mapOf("X1" to "SE1")
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
            catalog = catalogOf(LF_SVERIGE_INDEX_X1),
            isinByFundId = mapOf("X1" to "SE_CAND")
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
        // Hela den visade listan sparas (issue #93), inte bara det billigaste — facit ska kunna
        // spela in varje *givet* råd, och alternativen är varandras alternativ.
        assertEquals(listOf("SE_CAND"), stored?.toDomain()?.shownAlternativeIsins)
    }

    @Test
    fun `suggestCheaperAlternatives sparar sokt-utan-traff, skilt fran aldrig sokt`() = runTest {
        val heldView = heldSwedishEquityView(totalFee = 0.5)
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
        assertEquals(emptyList<String>(), stored?.toDomain()?.shownAlternativeIsins)
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
        val heldView = heldSwedishEquityView(totalFee = 0.5)
        val source = FakeAvanzaSource()
        source.enqueue(fullListJson(1, listOf(heldView)), fullListJson(0, emptyList()))
        val repository = repo(source)

        val result = repository.suggestCheaperAlternatives("SE_HELD", 300_000.0)

        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `suggestCheaperAlternatives stannar efter tre bekraftade alternativ`() = runTest {
        val heldView = heldSwedishEquityView(totalFee = 0.73)
        val candidateFees = listOf(0.10, 0.15, 0.20, 0.25, 0.30)
        val candidateViews = candidateFees.mapIndexed { i, fee ->
            fundView("SE_C$i", "Kandidat $i", totalFee = fee, indexFund = false, tags = swedishEquityTags)
        }
        val source = FakeAvanzaSource()
        source.enqueue(fullListJson(1, listOf(heldView)), fullListJson(candidateViews.size, candidateViews))
        val catalogFunds = candidateFees.indices.map { i -> Fund(fundId = "F$i", name = "Kandidat $i", currency = "SEK") }
        val isinByFundId = candidateFees.indices.associate { i -> "F$i" to "SE_C$i" }
        val fundPriceRepo = FakeFundPriceRepository(
            catalog = FundCatalog(companies = emptyList(), funds = catalogFunds),
            isinByFundId = isinByFundId
        )
        val repository = repo(source, fundPriceRepo)

        val result = repository.suggestCheaperAlternatives("SE_HELD", 300_000.0)

        // Alla 5 kandidater hade varit köpbara — budgeten (högst 3 visade) stoppar tidigt.
        assertEquals(3, result?.size)
        assertEquals(3, fundPriceRepo.fetchFundCatalogCallCount)
    }

    @Test
    fun `suggestCheaperAlternatives stannar efter tio provade aven om inga bekraftas`() = runTest {
        val heldView = heldSwedishEquityView(totalFee = 0.73)
        val candidateViews = (1..12).map { i ->
            fundView("SE_C$i", "Kandidat $i", totalFee = 0.73 - i * 0.01, indexFund = false, tags = swedishEquityTags)
        }
        val source = FakeAvanzaSource()
        source.enqueue(fullListJson(1, listOf(heldView)), fullListJson(candidateViews.size, candidateViews))
        // Katalog utan kandidaterna — ingen kan någonsin ISIN-verifieras som köpbar.
        val fundPriceRepo = FakeFundPriceRepository(catalog = UNRELATED_CATALOG)
        val repository = repo(source, fundPriceRepo)

        val result = repository.suggestCheaperAlternatives("SE_HELD", 300_000.0)

        assertEquals(emptyList<Any>(), result)
        // 12 kvalificerade kandidater fanns — budgeten (högst 10 prövade) stoppar innan alla testats.
        assertEquals(10, fundPriceRepo.fetchFundCatalogCallCount)
    }

    /**
     * Bevisar att baslinjen fortfarande är 1499. Cachen skiljer sig medvetet från källans (påstått
     * tystade) live-svar — och live-svarets fond har en ANNAN region än filtret (matchar alltså
     * inte "region=Sverige") så att den, även om den skrivs igenom till cachen (query() cachar
     * alltid det som kommer tillbaka), inte kan råka dyka upp i ett offline-filtrerat resultat och
     * maskera en förorenad baslinje. Ett live-svar med samma totalNoFunds som baslinjen tolkas som
     * "källan ignorerar filtret" och besvaras ur cachen: med baslinjen förorenad används live-svaret
     * SE_LIVE rakt av, med den intakt matchar bara SE_CACHE.
     */
    private suspend fun assertBaselineIntact(source: FakeAvanzaSource, repository: AvanzaFundMetadataRepository) {
        dao.stored["SE_CACHE"] = fundMetadataEntity(isin = "SE_CACHE", name = "Cache Sverige")
            .copy(tagsJson = """[{"title":"Sverige","category":"COMMON_REGION"}]""")
        source.response = fundListJson(1499, listOf(Triple("SE_LIVE", "Live-svar", "Global")))

        val result = repository.query(FundScreenQuery(region = listOf("Sverige")))

        assertEquals(listOf("SE_CACHE"), result.map { it.isin })
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

        // 3) Baslinjen är fortfarande 1499.
        assertBaselineIntact(source, repository)
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
    fun `metadataFor behaller sparad jamforelse nar avgiftsdatan hamtas om`() = runTest {
        // Regression: omhämtningen returnerade livesvaret rakt av, och källan känner inte till
        // appens härledda fält — jämförelsen fanns kvar i databasen men försvann på vägen ut,
        // så HEM-6 rapporterade "0 av N jämförda" och besparingen nollades trots färsk data.
        val today = LocalDate.now()
        dao.stored["SE1"] = fundMetadataEntity(isin = "SE1", name = "Fond Ett").copy(
            totalFee = 0.73,
            fetchedAtEpochDay = today.minusDays(FundMetadataFreshness.FEE_TTL_DAYS + 1).toEpochDay(),
            availableAtHandelsbanken = true,
            availabilityResolvedAtEpochDay = today.minusDays(3).toEpochDay(),
            cheapestAlternativeIsin = "SE2",
            cheapestAlternativeFee = 0.21,
            shownAlternativeIsinsJson = """["SE2","SE3"]""",
            comparisonResolvedAtEpochDay = today.minusDays(3).toEpochDay()
        )
        val source = FakeAvanzaSource(fullListJson(1, listOf(fundView("SE1", "Fond Ett", totalFee = 0.5, indexFund = false, tags = emptyList()))))
        val repository = repo(source)

        val metadata = repository.metadataFor(listOf("SE1"))["SE1"]

        // Färsk avgift från källan …
        assertEquals(0.5, metadata?.totalFee ?: -1.0, 1e-9)
        // … men det härledda tillståndet är kvar hela vägen ut till anroparen.
        assertEquals("SE2", metadata?.cheapestAlternativeIsin)
        assertEquals(0.21, metadata?.cheapestAlternativeFee ?: -1.0, 1e-9)
        assertEquals(today.minusDays(3).toEpochDay(), metadata?.comparisonResolvedAtEpochDay)
        assertEquals(listOf("SE2", "SE3"), metadata?.shownAlternativeIsins)
        assertEquals(true, metadata?.availableAtHandelsbanken)
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
        assertBaselineIntact(source, repository)
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
            catalog = catalogOf(Fund(fundId = "X1", name = "Kandidatfond", currency = "SEK")),
            isinByFundId = mapOf("X1" to "SE_CAND")
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
            catalog = catalogOf(Fund(fundId = "X1", name = "Utan avkastning", currency = "SEK")),
            isinByFundId = mapOf("X1" to "SE_CAND")
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
        val fundPriceRepo = FakeFundPriceRepository(catalog = UNRELATED_CATALOG)
        val repository = repo(source, fundPriceRepo)

        val result = repository.findSwitchCandidates(level = 3, excludeIsins = emptySet())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `searchSwitchCandidates ger inga kopbara nar katalogen hamtades men fonden saknas`() = runTest {
        // Issue #149: en verifierad miss och ett okänt svar ger båda tom lista — men olika besked.
        val candidateView = fundView("SE_CAND", "Kandidatfond", totalFee = 0.3, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.12)
        val repository = repo(
            FakeAvanzaSource(fullListJson(1, listOf(candidateView))),
            FakeFundPriceRepository(catalog = UNRELATED_CATALOG)
        )

        val result = repository.searchSwitchCandidates(level = 3, excludeIsins = emptySet())

        assertTrue(result.candidates.isEmpty())
        assertFalse(result.lookupFailed)
    }

    @Test
    fun `searchSwitchCandidates sager att uppslaget misslyckades nar katalogen inte kunde hamtas`() = runTest {
        val candidateView = fundView("SE_CAND", "Kandidatfond", totalFee = 0.3, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.12)
        val repository = repo(
            FakeAvanzaSource(fullListJson(1, listOf(candidateView))),
            FakeFundPriceRepository(catalog = null)
        )

        val result = repository.searchSwitchCandidates(level = 3, excludeIsins = emptySet())

        assertTrue(result.candidates.isEmpty())
        assertTrue(result.lookupFailed)
        // findSwitchCandidates (bytesplanen, HEM-8) ser samma tomma lista som förut.
        assertTrue(repository.findSwitchCandidates(level = 3, excludeIsins = emptySet()).isEmpty())
    }

    @Test
    fun `searchSwitchCandidates med indexfilter ger bara indexfonder och utan filter alla`() = runTest {
        // Issue #150: bevakningens filter "bara indexfonder" sållar poolen före rangordningen — den
        // aktiva fonden med högre avkastning får inte tränga undan indexfonden, och den ges aldrig.
        val active = fundView("SE_AKTIV", "Aktiv fond", totalFee = 1.2, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.20)
        val index = fundView("SE_INDEX", "Indexfond", totalFee = 0.2, indexFund = true, tags = emptyList(), risk = 3, developmentOneYear = 0.10)
        val repository = repo(
            FakeAvanzaSource(fullListJson(2, listOf(active, index))),
            FakeFundPriceRepository(
                catalog = catalogOf(Fund(fundId = "X1", name = "Aktiv fond", currency = "SEK"), Fund(fundId = "X2", name = "Indexfond", currency = "SEK")),
                isinByFundId = mapOf("X1" to "SE_AKTIV", "X2" to "SE_INDEX")
            )
        )

        val onlyIndex = repository.searchSwitchCandidates(level = 3, excludeIsins = emptySet(), indexOnly = true)
        val all = repository.searchSwitchCandidates(level = 3, excludeIsins = emptySet(), indexOnly = false)

        assertEquals(listOf("SE_INDEX"), onlyIndex.candidates.map { it.metadata.isin })
        assertFalse(onlyIndex.lookupFailed)
        assertEquals(listOf("SE_AKTIV", "SE_INDEX"), all.candidates.map { it.metadata.isin })
        // Bytesplanen (HEM-8) har inget filter.
        assertEquals(listOf("SE_AKTIV", "SE_INDEX"), repository.findSwitchCandidates(level = 3, excludeIsins = emptySet()).map { it.metadata.isin })
    }

    @Test
    fun `searchSwitchCandidates med indexfilter blaeddrar forbi en forsta sida utan indexfonder`() = runTest {
        // Granskning #150: källans enda sida är nivåns 20 bästa på 1-årsavkastning — där finns ofta
        // ingen indexfond, och tomläget hade sagt "inga köpbara indexfonder" fast de fanns på sida 2.
        val firstPage = (1..20).map { i ->
            fundView("SE_A$i", "Aktiv $i", totalFee = 1.0, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.30 - i * 0.001)
        }
        val index = fundView("SE_INDEX", "Indexfond", totalFee = 0.2, indexFund = true, tags = emptyList(), risk = 3, developmentOneYear = 0.10)
        val secondPage = listOf(fundView("SE_A21", "Aktiv 21", totalFee = 1.0, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.11), index)
        val source = FakeAvanzaSource(fullListJson(0, emptyList()))
        source.enqueue(fullListJson(22, firstPage), fullListJson(22, secondPage))
        val repository = repo(
            source,
            FakeFundPriceRepository(
                catalog = catalogOf(Fund(fundId = "X1", name = "Indexfond", currency = "SEK")),
                isinByFundId = mapOf("X1" to "SE_INDEX")
            )
        )

        val result = repository.searchSwitchCandidates(level = 3, excludeIsins = emptySet(), indexOnly = true)

        assertEquals(listOf("SE_INDEX"), result.candidates.map { it.metadata.isin })
        // Andra sidan (kort) var den sista — ingen tredje fråga.
        assertEquals(2, source.fetchFundListCallCount)
        assertTrue(source.lastRequestBody!!.contains("\"startIndex\":20"))
    }

    @Test
    fun `searchSwitchCandidates med indexfilter slutar blaeddra nar kallan ger samma sida igen`() = runTest {
        // En källa som ignorerar startIndex ger sida 1 om och om igen — det får inte bli fem frågor.
        val page = (1..20).map { i ->
            fundView("SE_A$i", "Aktiv $i", totalFee = 1.0, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.2)
        }
        val source = FakeAvanzaSource(fullListJson(40, page))
        val repository = repo(source)

        val result = repository.searchSwitchCandidates(level = 3, excludeIsins = emptySet(), indexOnly = true)

        assertTrue(result.candidates.isEmpty())
        assertEquals(2, source.fetchFundListCallCount)
    }

    @Test
    fun `searchSwitchCandidates med indexfilter sager att uppslaget misslyckades nar en foljdsida inte kom fran kallan`() = runTest {
        // Sista granskningen, punkt 1: sida 2 gick inte att hämta — "inga köpbara indexfonder" vore
        // ett påstående om fonder appen aldrig sett.
        val page = (1..20).map { i ->
            fundView("SE_A$i", "Aktiv $i", totalFee = 1.0, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.2)
        }
        val source = FakeAvanzaSource("{")
        source.enqueue(fullListJson(40, page))

        val result = repo(source).searchSwitchCandidates(level = 3, excludeIsins = emptySet(), indexOnly = true)

        assertTrue(result.candidates.isEmpty())
        assertTrue(result.lookupFailed)
        assertEquals(2, source.fetchFundListCallCount)
    }

    @Test
    fun `searchSwitchCandidates utan indexfilter fragar en sida`() = runTest {
        val page = (1..20).map { i ->
            fundView("SE_A$i", "Aktiv $i", totalFee = 1.0, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.2)
        }
        val source = FakeAvanzaSource(fullListJson(40, page))

        repo(source).searchSwitchCandidates(level = 3, excludeIsins = emptySet(), indexOnly = false)

        assertEquals(1, source.fetchFundListCallCount)
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
            isinByFundId = (1..8).associate { i -> "X$i" to "SE_C$i" }
        )
        val repository = repo(source, fundPriceRepo)

        val result = repository.findSwitchCandidates(level = 3, excludeIsins = emptySet())

        assertEquals(5, result.size)
    }

    @Test
    fun `findSwitchCandidates fragar kallan sorterad pa hogst 12-manadersavkastning`() = runTest {
        // Källans sida rymmer bara 20 träffar (TP-21), så sorteringen avgör vilken ände av
        // risknivån som ens blir synlig. Hämtades den billigaste änden kunde SwitchPlanCalcs
        // kvartilregel bara särskilja de billigaste fonderna inbördes (issue #75, punkt 3).
        val candidateView = fundView("SE_CAND", "Kandidatfond", totalFee = 0.3, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.12)
        val source = FakeAvanzaSource(fullListJson(1, listOf(candidateView)))
        val repository = repo(source)

        repository.findSwitchCandidates(level = 3, excludeIsins = emptySet())

        val body = source.lastRequestBody.orEmpty()
        assertTrue("ska sortera på developmentOneYear", body.contains("\"sortField\":\"developmentOneYear\""))
        assertTrue("ska sortera fallande", body.contains("\"sortDirection\":\"DESCENDING\""))
    }

    @Test
    fun `findSwitchCandidates rangordnar pa avkastning aven om kallan ignorerar sorteringen`() = runTest {
        // Ett okänt sortField ignoreras tyst av källan ("fail open", samma verifierade beteende
        // som filtren) — utan lokal omrangordning vore urvalet då sorterat på något annat.
        // Källan svarar här medvetet i stigande avkastningsordning.
        val views = listOf(
            fundView("SE_LAG", "Låg avkastning", totalFee = 0.1, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.02),
            fundView("SE_MELLAN", "Mellan", totalFee = 0.2, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.10),
            fundView("SE_HOG", "Hög avkastning", totalFee = 0.9, indexFund = false, tags = emptyList(), risk = 3, developmentOneYear = 0.30)
        )
        val source = FakeAvanzaSource(fullListJson(3, views))
        val catalogFunds = listOf(
            Fund(fundId = "X1", name = "Låg avkastning", currency = "SEK"),
            Fund(fundId = "X2", name = "Mellan", currency = "SEK"),
            Fund(fundId = "X3", name = "Hög avkastning", currency = "SEK")
        )
        val fundPriceRepo = FakeFundPriceRepository(
            catalog = FundCatalog(companies = emptyList(), funds = catalogFunds),
            isinByFundId = mapOf("X1" to "SE_LAG", "X2" to "SE_MELLAN", "X3" to "SE_HOG")
        )
        val repository = repo(source, fundPriceRepo)

        val result = repository.findSwitchCandidates(level = 3, excludeIsins = emptySet())

        assertEquals(listOf("SE_HOG", "SE_MELLAN", "SE_LAG"), result.map { it.metadata.isin })
    }

    private fun fundMetadataEntity(
        isin: String,
        name: String,
        availableAtHandelsbanken: Boolean? = null,
        availabilityResolvedAtEpochDay: Long? = null
    ) = FundMetadataEntity(
        isin = isin, name = name, orderbookId = isin, totalFee = 0.2, managementFee = 0.2,
        category = null, fundType = null, companyName = null, risk = null, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tagsJson = "[]",
        availableAtHandelsbanken = availableAtHandelsbanken,
        availabilityResolvedAtEpochDay = availabilityResolvedAtEpochDay,
        fetchedAtEpochDay = 0
    )
}
