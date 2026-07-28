package se.partee71.fonder.data.repository

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.partee71.fonder.data.network.FondlistaHtmlSource
import se.partee71.fonder.data.network.FxRatePoint
import se.partee71.fonder.data.network.FxRateSource
import se.partee71.fonder.data.network.IsinPriceHistorySource
import se.partee71.fonder.data.room.daos.FundDao
import se.partee71.fonder.data.room.daos.FundPriceDao
import se.partee71.fonder.data.room.daos.FxRateDao
import se.partee71.fonder.data.room.entities.FundEntity
import se.partee71.fonder.data.room.entities.FundPriceEntity
import se.partee71.fonder.data.room.entities.FxRateEntity
import se.partee71.fonder.domain.model.IsinFundInfo
import se.partee71.fonder.domain.model.IsinPricePoint
import se.partee71.fonder.domain.usecase.CurrencyConverter
import java.io.IOException
import java.time.LocalDate

private class FakeFundPriceDao : FundPriceDao {
    val stored = mutableListOf<FundPriceEntity>()

    override suspend fun getLatest(fundId: String): FundPriceEntity? =
        stored.filter { it.fundId == fundId }.maxByOrNull { it.epochDay }

    override suspend fun getOldest(fundId: String): FundPriceEntity? =
        stored.filter { it.fundId == fundId }.minByOrNull { it.epochDay }

    override fun observeLatest(fundIds: List<String>): Flow<List<FundPriceEntity>> =
        flowOf(
            stored.filter { it.fundId in fundIds }
                .groupBy { it.fundId }
                .mapNotNull { (_, prices) -> prices.maxByOrNull { it.epochDay } },
        )

    override suspend fun getRange(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPriceEntity> =
        stored.filter { it.fundId == fundId && it.epochDay in fromEpochDay..toEpochDay }.sortedBy { it.epochDay }

    override fun observeRange(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPriceEntity>> =
        flowOf(stored.filter { it.fundId == fundId && it.epochDay in fromEpochDay..toEpochDay }.sortedBy { it.epochDay })

    override suspend fun upsertAll(prices: List<FundPriceEntity>) {
        prices.forEach { new ->
            stored.removeAll { it.fundId == new.fundId && it.epochDay == new.epochDay }
            stored.add(new)
        }
    }

    override suspend fun deleteAll() {
        stored.clear()
    }
}

private class FakeFundDao : FundDao {
    val stored = mutableMapOf<String, FundEntity>()

    override fun observeAll(): Flow<List<FundEntity>> = flowOf(stored.values.toList())
    override suspend fun getByFundId(fundId: String): FundEntity? = stored[fundId]
    override suspend fun upsert(fund: FundEntity) { stored[fund.fundId] = fund }
    override suspend fun deleteByFundId(fundId: String) { stored.remove(fundId) }
    override suspend fun getAll(): List<FundEntity> = stored.values.toList()
    override suspend fun deleteAll() { stored.clear() }
}

private class FakeFxRateDao : FxRateDao {
    val stored = mutableListOf<FxRateEntity>()

    override suspend fun getRange(currency: String, fromEpochDay: Long, toEpochDay: Long): List<FxRateEntity> =
        stored.filter { it.currency == currency && it.epochDay in fromEpochDay..toEpochDay }.sortedBy { it.epochDay }

    override suspend fun getOldest(currency: String): FxRateEntity? =
        stored.filter { it.currency == currency }.minByOrNull { it.epochDay }

    override suspend fun getLatest(currency: String): FxRateEntity? =
        stored.filter { it.currency == currency }.maxByOrNull { it.epochDay }

    override suspend fun upsertAll(rates: List<FxRateEntity>) {
        rates.forEach { new ->
            stored.removeAll { it.currency == new.currency && it.epochDay == new.epochDay }
            stored.add(new)
        }
    }

    override suspend fun deleteAll() { stored.clear() }
}

/** Registrerar varje anrop mot valutakällan: (currency, from, to). */
private class RecordingFxRateSource(
    private val rates: (String, LocalDate, LocalDate) -> List<FxRatePoint> = { _, _, _ -> emptyList() },
) : FxRateSource {
    val calls = mutableListOf<Triple<String, LocalDate, LocalDate>>()
    override suspend fun fetchRates(currency: String, from: LocalDate, to: LocalDate): List<FxRatePoint> {
        calls.add(Triple(currency, from, to))
        return rates(currency, from, to)
    }
}

class HandelsbankenFundPriceRepositoryTest {

    private val dao = FakeFundPriceDao()
    private val fundDao = FakeFundDao()
    private val fxRateDao = FakeFxRateDao()
    private val fxRateSource = RecordingFxRateSource()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    @Test
    fun `refresh parsar och cachar kurser fran kallan`() = runTest {
        val html = historyHtml(fundId = "SHB0000442", nav = "150,00", currency = "SEK", date = "2026-07-01")
        val repo = HandelsbankenFundPriceRepository(client = FondlistaHtmlSource { _, _, _, _ -> html }, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = emptyList())

        val success = repo.refresh("SHB0000442")

        assertTrue(success)
        val latest = repo.latestPrice("SHB0000442")
        assertEquals(150.0, latest?.nav ?: -1.0, 1e-9)
        assertEquals("SEK", latest?.currency)
    }

    /** Registrerar varje anrop mot fondlista-källan: (fundId, company, from, to). */
    private class RecordingSource(private val html: String = "") : FondlistaHtmlSource {
        val calls = mutableListOf<Call>()
        data class Call(val fundId: String?, val company: String?, val from: LocalDate, val to: LocalDate)
        override suspend fun fetchHistoryPage(fundId: String?, company: String?, from: LocalDate, to: LocalDate): String {
            calls.add(Call(fundId, company, from, to))
            return html
        }
    }

    @Test
    fun `refresh utan känd historikhorisont hamtar bara ett kort farskt fonster`() = runTest {
        // En bevakad men aldrig köpt fond behöver bara en färsk kurs — inte hela historiken.
        val client = RecordingSource()
        val repo = HandelsbankenFundPriceRepository(client = client, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = emptyList())

        repo.refresh("SHB0000442", since = null)

        val today = LocalDate.now()
        assertEquals(1, client.calls.size)
        assertEquals(today.minusDays(60), client.calls.first().from)
        assertEquals(today, client.calls.first().to)
    }

    @Test
    fun `refresh backfillar hela historiken nar cachen ar tom`() = runTest {
        // Källan har inget femårstak (TP-18) — hela spannet sedan första köpet hämtas i ETT
        // anrop, till skillnad från tidigare `minusYears(5)` + ett extra förtätningsanrop.
        val since = LocalDate.of(1998, 3, 2)
        val client = RecordingSource()
        val repo = HandelsbankenFundPriceRepository(client = client, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = emptyList())

        repo.refresh("SHB0000442", since = since)

        assertEquals(1, client.calls.size)
        assertEquals(since, client.calls.first().from)
        assertEquals(LocalDate.now(), client.calls.first().to)
    }

    @Test
    fun `refresh hamtar bara det korta fonstret nar cachen redan nar tillbaka till since`() = runTest {
        // Rutinuppdateringen får inte dra hem 30 års historik (~3,6 MB) varje gång — det är
        // hela poängen med backfill-gaten.
        val since = LocalDate.of(2020, 1, 1)
        dao.stored.add(FundPriceEntity(fundId = "SHB0000442", epochDay = since.minusDays(1).toEpochDay(), nav = 100.0, currency = "SEK"))
        val client = RecordingSource()
        val repo = HandelsbankenFundPriceRepository(client = client, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = emptyList())

        repo.refresh("SHB0000442", since = since)

        assertEquals(1, client.calls.size)
        assertEquals(LocalDate.now().minusDays(60), client.calls.first().from)
    }

    @Test
    fun `refresh backfillar nar cachen inte nar hela vagen tillbaka till since`() = runTest {
        val since = LocalDate.of(2010, 1, 1)
        dao.stored.add(FundPriceEntity(fundId = "SHB0000442", epochDay = LocalDate.of(2020, 1, 1).toEpochDay(), nav = 100.0, currency = "SEK"))
        val client = RecordingSource()
        val repo = HandelsbankenFundPriceRepository(client = client, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = emptyList())

        repo.refresh("SHB0000442", since = since)

        assertEquals(1, client.calls.size)
        assertEquals(since, client.calls.first().from)
    }

    @Test
    fun `refresh skickar inget fondbolag — kurstabellen ar bolagsoberoende`() = runTest {
        val client = RecordingSource()
        val repo = HandelsbankenFundPriceRepository(client = client, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = emptyList())

        repo.refresh("0P0001KRE7", since = LocalDate.of(2020, 1, 1))

        assertNull(client.calls.first().company)
    }

    @Test
    fun `refresh vid natverksfel behaller cachad data`() = runTest {
        dao.stored.add(FundPriceEntity(fundId = "SHB0000442", epochDay = LocalDate.of(2026, 6, 30).toEpochDay(), nav = 140.0, currency = "SEK"))
        val failingClient = FondlistaHtmlSource { _, _, _, _ -> throw IOException("nätverksfel") }
        val repo = HandelsbankenFundPriceRepository(client = failingClient, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = emptyList())

        val success = repo.refresh("SHB0000442")

        assertFalse(success)
        val latest = repo.latestPrice("SHB0000442")
        assertEquals(140.0, latest?.nav ?: -1.0, 1e-9)
    }

    @Test
    fun `latestPrice for okand fond ar null`() = runTest {
        val repo = HandelsbankenFundPriceRepository(client = FondlistaHtmlSource { _, _, _, _ -> "" }, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = emptyList())
        assertNull(repo.latestPrice("OKAND"))
    }

    @Test
    fun `fetchFundCatalog hamtar bolag och ofiltrerad fondkatalog i ett anrop`() = runTest {
        val html = """
            <select id="company" name="company"><option value="">Välj fondbolag</option>
            <option selected="selected" value="1">Handelsbanken</option>
            <option value="1101">Aberdeen Global Services S.A.</option>
            </select>
            <select id="FundId" name="FundId"><option value="">Välj fond</option>
            <option value="0P000083RV">AstraZeneca Allemansfond</option>
            <option value="SHB0000442">Handelsbanken Amerika Småbolag Tema</option>
            </select>
        """.trimIndent()
        val client = RecordingSource(html)
        val repo = HandelsbankenFundPriceRepository(client = client, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = emptyList())

        val catalog = repo.fetchFundCatalog()

        // Inget fondbolag i anropet — annars kapas katalogen till det bolagets fonder (TP-18).
        assertNull(client.calls.first().company)
        assertNull(client.calls.first().fundId)
        assertEquals(2, catalog.companies.size)
        assertEquals("Handelsbanken", catalog.companies.first { it.id == "1" }.name)
        assertEquals(2, catalog.funds.size)
    }

    @Test
    fun `fetchFundsForCompany skickar bolagets id och ger bolagets fonder`() = runTest {
        val html = """
            <select id="FundId" name="FundId"><option value="">Välj fond</option>
            <option value="0P0001KRE7">CPR Invest Global Gold Mines A USD Acc</option>
            </select>
        """.trimIndent()
        val client = RecordingSource(html)
        val repo = HandelsbankenFundPriceRepository(client = client, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = emptyList())

        val funds = repo.fetchFundsForCompany("1372")

        assertEquals("1372", client.calls.first().company)
        assertEquals(listOf("0P0001KRE7"), funds?.map { it.fundId })
    }

    @Test
    fun `fetchFundsForCompany ar null vid natverksfel sa anroparen kan behalla sin lista`() = runTest {
        val failingClient = FondlistaHtmlSource { _, _, _, _ -> throw IOException("nätverksfel") }
        val repo = HandelsbankenFundPriceRepository(client = failingClient, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = emptyList())

        assertNull(repo.fetchFundsForCompany("1372"))
    }

    @Test
    fun `lookupIsin laser fondens isin fran fondsidan`() = runTest {
        val fundPage = """<a href="/x?IdentifierType=1&Identifier=LU1989766289&Country=SE">Faktablad</a>"""
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _, _ -> "" },
            fundPageClient = { fundPage },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = emptyList(),
        )

        assertEquals("LU1989766289", repo.lookupIsin("0P0001KRE7"))
    }

    @Test
    fun `lookupIsin ar null vid natverksfel`() = runTest {
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _, _ -> "" },
            fundPageClient = { throw IOException("nätverksfel") },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = emptyList(),
        )

        assertNull(repo.lookupIsin("0P0001KRE7"))
    }

    @Test
    fun `fetchFundCatalog vid natverksfel returnerar tom katalog utan att krascha`() = runTest {
        val failingClient = FondlistaHtmlSource { _, _, _, _ -> throw IOException("nätverksfel") }
        val repo = HandelsbankenFundPriceRepository(client = failingClient, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = emptyList())

        val catalog = repo.fetchFundCatalog()

        assertEquals(0, catalog.companies.size)
        assertEquals(0, catalog.funds.size)
    }

    private class FakeIsinSource(
        private val history: (String, LocalDate, LocalDate) -> List<IsinPricePoint> = { _, _, _ -> emptyList() },
        private val suggestion: (String) -> String? = { null },
        private val fundInfo: (String) -> IsinFundInfo? = { null },
    ) : IsinPriceHistorySource {
        var lastHistoryCall: Triple<String, LocalDate, LocalDate>? = null
        override suspend fun fetchHistory(isin: String, from: LocalDate, to: LocalDate): List<IsinPricePoint> {
            lastHistoryCall = Triple(isin, from, to)
            return history(isin, from, to)
        }
        override suspend fun suggestIsin(fundName: String): String? = suggestion(fundName)
        override suspend fun findFund(isin: String): IsinFundInfo? = fundInfo(isin)
    }

    private class FailingIsinSource : IsinPriceHistorySource {
        override suspend fun fetchHistory(isin: String, from: LocalDate, to: LocalDate): List<IsinPricePoint> =
            throw IOException("nätverksfel")
        override suspend fun suggestIsin(fundName: String): String? =
            throw IOException("nätverksfel")
        override suspend fun findFund(isin: String): IsinFundInfo? =
            throw IOException("nätverksfel")
    }

    @Test
    fun `refreshSince provar fondlista fore ISIN-kedjan`() = runTest {
        // Fondlista ger daglig, luckfri historik utan datumtak (TP-18) — Avanza är reserv,
        // inte primär väg för gamla köp (TP-14).
        val since = LocalDate.of(2016, 4, 13)
        val html = historyHtml(fundId = "0P0001KRE7", nav = "193,53", currency = "SEK", date = "2026-07-24")
        val avanza = FakeIsinSource(history = { _, _, _ ->
            listOf(IsinPricePoint(epochDay = since.toEpochDay(), nav = 1.0, currency = "SEK"))
        })
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _, _ -> html },
            fundPageClient = { "" },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = listOf(avanza),
        )

        val success = repo.refreshSince("0P0001KRE7", "LU1989766289", since)

        assertTrue(success)
        assertNull("ISIN-kedjan ska inte anropas när fondlista levererat", avanza.lastHistoryCall)
        assertEquals(193.53, repo.latestPrice("0P0001KRE7")?.nav ?: -1.0, 1e-9)
    }

    @Test
    fun `refreshSince faller tillbaka till ISIN-kedjan nar fondlista ar tom`() = runTest {
        val since = LocalDate.of(2020, 1, 1)
        val avanza = FakeIsinSource(history = { _, _, _ ->
            listOf(IsinPricePoint(epochDay = since.toEpochDay(), nav = 55.0, currency = "SEK"))
        })
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _, _ -> "" },
            fundPageClient = { "" },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = listOf(avanza),
        )

        assertTrue(repo.refreshSince("SHB0000442", "SE0004297927", since))
        assertEquals("SE0004297927", avanza.lastHistoryCall?.first)
        assertEquals(55.0, repo.latestPrice("SHB0000442")?.nav ?: -1.0, 1e-9)
    }

    /**
     * Källa som skiljer på katalogsidan (fundId = null) och kurshistoriken för en viss fond —
     * det som krävs för att testa uppslaget ISIN → fondlista-id (issue #39).
     */
    private class CatalogAndHistorySource(
        private val catalogHtml: String,
        private val historyByFundId: Map<String, String>,
    ) : FondlistaHtmlSource {
        val historyCalls = mutableListOf<String>()
        var catalogCalls = 0
        override suspend fun fetchHistoryPage(fundId: String?, company: String?, from: LocalDate, to: LocalDate): String {
            if (fundId == null) {
                catalogCalls++
                return catalogHtml
            }
            historyCalls.add(fundId)
            return historyByFundId[fundId].orEmpty()
        }
    }

    private val katalogHtml = """
        <select id="FundId" name="FundId"><option value="">Välj fond</option>
        <option value="0P0000O30D">Franklin Gold and Prec Mtls A(acc)USD</option>
        <option value="SHB0000442">Handelsbanken Amerika Småbolag Tema</option>
        </select>
    """.trimIndent()

    /** En importmatchad fond: identiteten är ISIN:et (findFundByIsin, TP-13/TP-14). */
    private val isinFond = FundEntity(
        fundId = "LU0496367417",
        name = "Franklin Gold and Prec Mtls A(acc)USD",
        currency = "USD",
        isin = "LU0496367417",
    )

    private fun fundPageMedIsin(isin: String) = """<a href="/x?IdentifierType=1&Identifier=$isin&Country=SE">Faktablad</a>"""

    @Test
    fun `fondlista-kurs i USD raknas om till kronor via vaxelkurs`() = runTest {
        // Fondlista noterar en USD-fond i dollar. Appen räknar kronor, så kursen räknas om med
        // Riksbankens dagskurs i stället för att tolkas som kronor rakt av (issue #41/#43) —
        // 193,48 USD x 9,73973 ≈ 1884,44 kr, nära Avanzas 1878,75 samma dag.
        val html = historyHtml(fundId = "0P0001KRE7", nav = "193,48", currency = "USD", date = "2026-07-23")
        val fx = RecordingFxRateSource(rates = { _, _, _ ->
            listOf(FxRatePoint(epochDay = LocalDate.of(2026, 7, 23).toEpochDay(), rate = 9.73973))
        })
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _, _ -> html },
            fundPageClient = { "" },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fx,
            isinSources = emptyList(),
        )

        val success = repo.refresh("0P0001KRE7", since = LocalDate.of(2020, 1, 1))

        assertTrue(success)
        val latest = repo.latestPrice("0P0001KRE7")
        assertEquals("SEK", latest?.currency)
        assertEquals(1884.44, latest?.nav ?: -1.0, 0.01)
        // Växelkursen efterfrågas per valuta ("USD"), inte per fond.
        assertEquals("USD", fx.calls.first().first)
    }

    @Test
    fun `USD-fond faller tillbaka pa ISIN-kedjan om ingen vaxelkurs finns`() = runTest {
        // Går växelkurshämtningen inte att lita på (nätverksfel, okänd valuta) blir
        // konverteringen tom — hellre Avanzas kronor direkt än ett gissat värde (POR-3).
        fundDao.stored[isinFond.fundId] = isinFond.copy(fondlistaFundId = "0P0000O30D")
        val usdHtml = historyHtml(fundId = "0P0000O30D", nav = "16,93", currency = "USD", date = "2026-07-24")
        val avanza = FakeIsinSource(history = { _, _, _ ->
            listOf(IsinPricePoint(epochDay = LocalDate.of(2026, 7, 23).toEpochDay(), nav = 164.35, currency = "SEK"))
        })
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _, _ -> usdHtml },
            fundPageClient = { "" },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = listOf(avanza),
        )

        assertTrue(repo.refreshSince(isinFond.fundId, isinFond.isin!!, LocalDate.of(2020, 1, 1)))

        assertEquals(164.35, repo.latestPrice(isinFond.fundId)?.nav ?: -1.0, 1e-9)
        assertEquals("SEK", repo.latestPrice(isinFond.fundId)?.currency)
    }

    @Test
    fun `redan cachad vaxelkurs ateranvands utan nytt anrop`() = runTest {
        // Cachen behandlas som ett sammanhängande intervall (ensureRatesCached) — den måste
        // täcka HELA marginalfönstret före priskursens dag (MAX_RATE_AGE_DAYS), inte bara den
        // exakta dagen, annars ser koden det (korrekt) som en lucka att fylla. En riktig
        // tidigare hämtning fyller alltid ett sammanhängande spann, aldrig en enstaka dag.
        val priceDay = LocalDate.of(2026, 7, 23)
        for (day in priceDay.minusDays(CurrencyConverter.MAX_RATE_AGE_DAYS).toEpochDay()..priceDay.toEpochDay()) {
            fxRateDao.stored.add(FxRateEntity(currency = "USD", epochDay = day, rate = 9.73973))
        }
        val html = historyHtml(fundId = "0P0001KRE7", nav = "193,48", currency = "USD", date = "2026-07-23")
        val fx = RecordingFxRateSource()
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _, _ -> html },
            fundPageClient = { "" },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fx,
            isinSources = emptyList(),
        )

        repo.refresh("0P0001KRE7", since = LocalDate.of(2020, 1, 1))

        assertTrue("Kursen fanns redan cachad — inget nätverksanrop borde behövts", fx.calls.isEmpty())
        assertEquals(1884.44, repo.latestPrice("0P0001KRE7")?.nav ?: -1.0, 0.01)
    }

    @Test
    fun `vaxelkurscachen fylls bara pa med det som saknas`() = runTest {
        // Redan hämtat 2026-07-01..2026-07-20 — en ny fråga för 2026-07-15..2026-07-25 ska
        // bara hämta den nya svansen (07-21..07-25), inte hela spannet igen.
        for (day in LocalDate.of(2026, 7, 1).toEpochDay()..LocalDate.of(2026, 7, 20).toEpochDay()) {
            fxRateDao.stored.add(FxRateEntity(currency = "USD", epochDay = day, rate = 9.7))
        }
        val html = historyHtml(fundId = "0P0001KRE7", nav = "100,00", currency = "USD", date = "2026-07-25")
        val fx = RecordingFxRateSource(rates = { _, from, to ->
            (from.toEpochDay()..to.toEpochDay()).map { FxRatePoint(epochDay = it, rate = 9.7) }
        })
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _, _ -> html },
            fundPageClient = { "" },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fx,
            isinSources = emptyList(),
        )

        repo.refresh("0P0001KRE7", since = LocalDate.of(2020, 1, 1))

        assertEquals(1, fx.calls.size)
        assertEquals(LocalDate.of(2026, 7, 21), fx.calls.first().second)
        assertEquals(LocalDate.of(2026, 7, 25), fx.calls.first().third)
    }

    @Test
    fun `SEK-fond fran fondlista cachas som vanligt`() = runTest {
        val html = historyHtml(fundId = "0P00000L4S", nav = "323,07", currency = "SEK", date = "2026-07-24")
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _, _ -> html },
            fundPageClient = { "" },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = emptyList(),
        )

        repo.refresh("0P00000L4S", since = LocalDate.of(2020, 1, 1))

        assertEquals(323.07, repo.latestPrice("0P00000L4S")?.nav ?: -1.0, 1e-9)
    }

    @Test
    fun `refreshSince loser upp fondlista-id for en ISIN-identifierad fond och sparar det`() = runTest {
        // Utan uppslaget hamnar hela importerade portföljer permanent på Avanza-grenen, som
        // ligger en handelsdag efter (issue #39).
        fundDao.stored[isinFond.fundId] = isinFond
        val client = CatalogAndHistorySource(
            catalogHtml = katalogHtml,
            historyByFundId = mapOf(
                "0P0000O30D" to historyHtml(fundId = "0P0000O30D", nav = "193,53", currency = "SEK", date = "2026-07-24"),
            ),
        )
        val avanza = FakeIsinSource(history = { _, _, _ ->
            listOf(IsinPricePoint(epochDay = LocalDate.of(2026, 7, 23).toEpochDay(), nav = 1.0, currency = "SEK"))
        })
        val repo = HandelsbankenFundPriceRepository(
            client = client,
            fundPageClient = { fundPageMedIsin("LU0496367417") },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = listOf(avanza),
        )

        val success = repo.refreshSince(isinFond.fundId, isinFond.isin!!, LocalDate.of(2020, 1, 1))

        assertTrue(success)
        assertEquals(listOf("0P0000O30D"), client.historyCalls)
        assertNull("Avanza ska inte behöva anropas när fondlista levererade", avanza.lastHistoryCall)
        // Uppslaget sparas på fonden — identiteten (fundId) är oförändrad.
        assertEquals("0P0000O30D", fundDao.stored[isinFond.fundId]?.fondlistaFundId)
        assertEquals("LU0496367417", fundDao.stored[isinFond.fundId]?.fundId)
        // Kurserna cachas under APPENS fundId, inte det uppslagna.
        assertEquals(193.53, repo.latestPrice("LU0496367417")?.nav ?: -1.0, 1e-9)
        assertNull(repo.latestPrice("0P0000O30D"))
    }

    @Test
    fun `resolveFondlistaFundId provar flera rankade kandidater tills ISIN stammer`() = runTest {
        // Verklig andelsklasskollision (Handelsbanken Sverige-familjen): fondens namn delar
        // suffix-tokenet "sek" med tre felaktiga syskon, som Jaccard-rankar högre än den
        // suffixlösa basfonden — trots att basfonden (SE0000582033) är rätt träff. Bara den
        // högst rankade kandidaten verifierades tidigare; nu ska ISIN-verifieringen fortsätta
        // till nästa rankade kandidat tills en stämmer.
        val a1Fond = isinFond.copy(
            fundId = "SE0000582033",
            isin = "SE0000582033",
            name = "Handelsbanken Sverige (A1 SEK)",
            currency = "SEK",
        )
        fundDao.stored[a1Fond.fundId] = a1Fond
        val familjeKatalogHtml = """
            <select id="FundId" name="FundId"><option value="">Välj fond</option>
            <option value="SHB0000387">Handelsbanken Sverige (A10 SEK)</option>
            <option value="SHB0000541">Handelsbanken Sverige (A9 SEK)</option>
            <option value="SHB0000610">Handelsbanken Sverige (B1 SEK)</option>
            <option value="0P00000F8J">Handelsbanken Sverige</option>
            </select>
        """.trimIndent()
        val client = CatalogAndHistorySource(
            catalogHtml = familjeKatalogHtml,
            historyByFundId = mapOf(
                "0P00000F8J" to historyHtml(fundId = "0P00000F8J", nav = "193,53", currency = "SEK", date = "2026-07-24"),
            ),
        )
        val fundPageIsinByCandidate = mapOf(
            "SHB0000387" to "SE0000000001",
            "SHB0000541" to "SE0000000002",
            "SHB0000610" to "SE0000000003",
            "0P00000F8J" to "SE0000582033",
        )
        val repo = HandelsbankenFundPriceRepository(
            client = client,
            fundPageClient = { fundId -> fundPageMedIsin(requireNotNull(fundPageIsinByCandidate[fundId])) },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = emptyList(),
        )

        val success = repo.refreshSince(a1Fond.fundId, a1Fond.isin!!, LocalDate.of(2020, 1, 1))

        assertTrue(success)
        assertEquals("0P00000F8J", fundDao.stored[a1Fond.fundId]?.fondlistaFundId)
        assertEquals(193.53, repo.latestPrice(a1Fond.fundId)?.nav ?: -1.0, 1e-9)
    }

    @Test
    fun `redan uppslaget fondlista-id ateranvands utan ny kataloghamtning`() = runTest {
        fundDao.stored[isinFond.fundId] = isinFond.copy(fondlistaFundId = "0P0000O30D")
        val client = CatalogAndHistorySource(
            catalogHtml = katalogHtml,
            historyByFundId = mapOf(
                "0P0000O30D" to historyHtml(fundId = "0P0000O30D", nav = "193,53", currency = "SEK", date = "2026-07-24"),
            ),
        )
        val repo = HandelsbankenFundPriceRepository(
            client = client,
            fundPageClient = { throw IOException("ska inte behövas") },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = emptyList(),
        )

        assertTrue(repo.refreshSince(isinFond.fundId, isinFond.isin!!, LocalDate.of(2020, 1, 1)))

        assertEquals(0, client.catalogCalls)
        assertEquals(listOf("0P0000O30D"), client.historyCalls)
    }

    @Test
    fun `fel isin pa katalogkandidaten ger inget uppslag och faller tillbaka pa ISIN-kedjan`() = runTest {
        // Hellre Avanza än fel fond — ISIN måste stämma innan ett id sparas.
        fundDao.stored[isinFond.fundId] = isinFond
        val client = CatalogAndHistorySource(catalogHtml = katalogHtml, historyByFundId = emptyMap())
        val avanza = FakeIsinSource(history = { _, _, _ ->
            listOf(IsinPricePoint(epochDay = LocalDate.of(2026, 7, 23).toEpochDay(), nav = 55.0, currency = "SEK"))
        })
        val repo = HandelsbankenFundPriceRepository(
            client = client,
            fundPageClient = { fundPageMedIsin("SE0009999999") },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = listOf(avanza),
        )

        assertTrue(repo.refreshSince(isinFond.fundId, isinFond.isin!!, LocalDate.of(2020, 1, 1)))

        assertNull(fundDao.stored[isinFond.fundId]?.fondlistaFundId)
        assertTrue(client.historyCalls.isEmpty())
        assertEquals(55.0, repo.latestPrice(isinFond.fundId)?.nav ?: -1.0, 1e-9)
    }

    @Test
    fun `olosbar fond gor inte om uppslaget vid varje uppdatering`() = runTest {
        // En fond som saknas i katalogen (t.ex. en andelsklass som inte säljs där) får annars
        // ett katalog- och sidanrop per kursuppdatering, i all evighet.
        fundDao.stored[isinFond.fundId] = isinFond
        val client = CatalogAndHistorySource(catalogHtml = katalogHtml, historyByFundId = emptyMap())
        var sidanrop = 0
        val repo = HandelsbankenFundPriceRepository(
            client = client,
            fundPageClient = { sidanrop++; fundPageMedIsin("SE0009999999") },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = listOf(FakeIsinSource()),
        )

        repeat(3) { repo.refreshSince(isinFond.fundId, isinFond.isin!!, LocalDate.of(2020, 1, 1)) }

        assertEquals(1, client.catalogCalls)
        assertEquals(1, sidanrop)
    }

    @Test
    fun `refreshSince hoppar over fondlista for fonder som saknas i katalogen`() = runTest {
        // `findFundByIsin`-fonder har `fundId == isin` och finns per definition inte i
        // fondlista-katalogen — ett anrop dit vore bara ett bortkastat nätverksanrop.
        val since = LocalDate.of(2020, 1, 1)
        val client = RecordingSource()
        val avanza = FakeIsinSource(history = { _, _, _ ->
            listOf(IsinPricePoint(epochDay = since.toEpochDay(), nav = 12.0, currency = "SEK"))
        })
        val repo = HandelsbankenFundPriceRepository(client = client, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = listOf(avanza))

        repo.refreshSince("LU0496367417", "LU0496367417", since)

        assertTrue(client.calls.isEmpty())
        assertEquals(12.0, repo.latestPrice("LU0496367417")?.nav ?: -1.0, 1e-9)
    }

    @Test
    fun `refreshSince hamtar och cachar historik fran forsta kallan som ger traff`() = runTest {
        val since = LocalDate.of(2020, 1, 1)
        val source = FakeIsinSource(history = { _, _, _ ->
            listOf(IsinPricePoint(epochDay = since.toEpochDay(), nav = 123.45, currency = "SEK"))
        })
        val repo = HandelsbankenFundPriceRepository(client = FondlistaHtmlSource { _, _, _, _ -> "" }, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = listOf(source))

        val success = repo.refreshSince("SHB0000442", "SE0004297927", since)

        assertTrue(success)
        assertEquals("SE0004297927", source.lastHistoryCall?.first)
        assertEquals(123.45, repo.latestPrice("SHB0000442")?.nav ?: -1.0, 1e-9)
    }

    @Test
    fun `refreshSince provar nasta kalla om forsta ar tom eller ger fel`() = runTest {
        val since = LocalDate.of(2020, 1, 1)
        val failing = FailingIsinSource()
        val empty = FakeIsinSource()
        val working = FakeIsinSource(history = { _, _, _ ->
            listOf(IsinPricePoint(epochDay = since.toEpochDay(), nav = 99.0, currency = "SEK"))
        })
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _, _ -> "" },
            fundPageClient = { "" },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = listOf(failing, empty, working),
        )

        repo.refreshSince("SHB0000442", "SE0004297927", since)

        assertEquals(99.0, repo.latestPrice("SHB0000442")?.nav ?: -1.0, 1e-9)
    }

    @Test
    fun `refreshSince hamtar dessutom ett kort farskt fonster fran samma kalla`() = runTest {
        // issue #35: samma förtätningsprincip som för Handelsbanken-källan (se testet ovan),
        // men för ISIN-kedjan — bara relevant när `since` ligger långt tillbaka (annars är
        // det ursprungliga anropet redan ett kort fönster, se testet nedan).
        val since = LocalDate.of(2020, 1, 1)
        val calls = mutableListOf<Triple<LocalDate, LocalDate, LocalDate>>()
        val source = FakeIsinSource(history = { _, from, to ->
            calls.add(Triple(since, from, to))
            listOf(IsinPricePoint(epochDay = since.toEpochDay(), nav = 123.45, currency = "SEK"))
        })
        val repo = HandelsbankenFundPriceRepository(client = FondlistaHtmlSource { _, _, _, _ -> "" }, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = listOf(source))

        repo.refreshSince("SHB0000442", "SE0004297927", since)

        val today = LocalDate.now()
        assertEquals(2, calls.size)
        assertEquals(since, calls[0].second)
        assertEquals(today.minusDays(60), calls[1].second)
        assertEquals(today, calls[1].third)
    }

    @Test
    fun `refreshSince hoppar over det farska fonstret om since redan ligger inom det`() = runTest {
        val since = LocalDate.now().minusDays(10)
        var callCount = 0
        val source = FakeIsinSource(history = { _, _, _ ->
            callCount++
            listOf(IsinPricePoint(epochDay = since.toEpochDay(), nav = 100.0, currency = "SEK"))
        })
        val repo = HandelsbankenFundPriceRepository(client = FondlistaHtmlSource { _, _, _, _ -> "" }, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = listOf(source))

        repo.refreshSince("SHB0000442", "SE0004297927", since)

        assertEquals(1, callCount)
    }

    @Test
    fun `refreshSince behaller cache om ingen kalla ger traff`() = runTest {
        dao.stored.add(FundPriceEntity(fundId = "SHB0000442", epochDay = 100, nav = 140.0, currency = "SEK"))
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _, _ -> "" },
            fundPageClient = { "" },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = listOf(FailingIsinSource(), FakeIsinSource()),
        )

        val success = repo.refreshSince("SHB0000442", "SE0004297927", LocalDate.of(2020, 1, 1))

        assertFalse(success)
        assertEquals(140.0, repo.latestPrice("SHB0000442")?.nav ?: -1.0, 1e-9)
    }

    @Test
    fun `suggestIsin returnerar forsta kallans forslag`() = runTest {
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _, _ -> "" },
            fundPageClient = { "" },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = listOf(FailingIsinSource(), FakeIsinSource(suggestion = { "SE0004297927" })),
        )

        assertEquals("SE0004297927", repo.suggestIsin("Spiltan Aktiefond Investmentbolag"))
    }

    @Test
    fun `suggestIsin ar null om ingen kalla har ett forslag`() = runTest {
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _, _ -> "" },
            fundPageClient = { "" },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = listOf(FakeIsinSource()),
        )

        assertNull(repo.suggestIsin("Okänd fond"))
    }

    @Test
    fun `findFundByIsin bygger en Fund med isin som fundId fran forsta kallan som ger traff`() = runTest {
        val source = FakeIsinSource(fundInfo = { isin ->
            if (isin == "LU0496367417") IsinFundInfo(name = "Franklin Gold and Prec Mtls A(acc)USD", currency = "USD") else null
        })
        val repo = HandelsbankenFundPriceRepository(client = FondlistaHtmlSource { _, _, _, _ -> "" }, fundPageClient = { "" }, dao = dao, fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource, isinSources = listOf(source))

        val fund = repo.findFundByIsin("LU0496367417")

        assertEquals("LU0496367417", fund?.fundId)
        assertEquals("LU0496367417", fund?.isin)
        assertEquals("Franklin Gold and Prec Mtls A(acc)USD", fund?.name)
        assertEquals("USD", fund?.currency)
    }

    @Test
    fun `findFundByIsin provar nasta kalla om forsta ger fel`() = runTest {
        val working = FakeIsinSource(fundInfo = { IsinFundInfo(name = "Nordea Småbolagsfond Norden", currency = "SEK") })
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _, _ -> "" },
            fundPageClient = { "" },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = listOf(FailingIsinSource(), working),
        )

        val fund = repo.findFundByIsin("FI0008813365")

        assertEquals("Nordea Småbolagsfond Norden", fund?.name)
    }

    @Test
    fun `findFundByIsin ar null om ingen kalla kanner till isin`() = runTest {
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _, _ -> "" },
            fundPageClient = { "" },
            dao = dao,
            fundDao = fundDao, fxRateDao = fxRateDao, fxRateSource = fxRateSource,
            isinSources = listOf(FakeIsinSource()),
        )

        assertNull(repo.findFundByIsin("SE0000000000"))
    }

    private fun historyHtml(fundId: String, nav: String, currency: String, date: String) = """
        <table><tbody>
        <tr class="funds-data">
            <td class="name "><span class="arrow" id="$fundId"></span></td>
            <td class="positive">$nav</td>
            <td class="left">$currency</td>
            <td>$date</td>
        </tr>
        </tbody></table>
    """.trimIndent()
}
