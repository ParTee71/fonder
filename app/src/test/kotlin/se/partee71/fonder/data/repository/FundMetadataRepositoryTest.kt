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
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.FundScreenQuery
import java.io.IOException
import java.time.LocalDate

private class FakeAvanzaSource(var response: String = """{"fundListViews":[],"totalNoFunds":0,"filterCounts":{}}""") : AvanzaSource {
    var lastRequestBody: String? = null
    override suspend fun search(query: String): String = ""
    override suspend fun fetchGuide(orderbookId: String): String = ""
    override suspend fun fetchChart(orderbookId: String, from: LocalDate, to: LocalDate): String = ""
    override suspend fun fetchFundList(requestBody: String): String {
        lastRequestBody = requestBody
        return response
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
