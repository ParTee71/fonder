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
import se.partee71.fonder.data.network.IsinPriceHistorySource
import se.partee71.fonder.data.room.daos.FundPriceDao
import se.partee71.fonder.data.room.entities.FundPriceEntity
import se.partee71.fonder.domain.model.IsinFundInfo
import se.partee71.fonder.domain.model.IsinPricePoint
import java.io.IOException
import java.time.LocalDate

private class FakeFundPriceDao : FundPriceDao {
    val stored = mutableListOf<FundPriceEntity>()

    override suspend fun getLatest(fundId: String): FundPriceEntity? =
        stored.filter { it.fundId == fundId }.maxByOrNull { it.epochDay }

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

class HandelsbankenFundPriceRepositoryTest {

    private val dao = FakeFundPriceDao()

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
        val repo = HandelsbankenFundPriceRepository(client = FondlistaHtmlSource { _, _, _ -> html }, dao = dao, isinSources = emptyList())

        val success = repo.refresh("SHB0000442")

        assertTrue(success)
        val latest = repo.latestPrice("SHB0000442")
        assertEquals(150.0, latest?.nav ?: -1.0, 1e-9)
        assertEquals("SEK", latest?.currency)
    }

    @Test
    fun `refresh hamtar fem ars kurshistorik`() = runTest {
        var capturedFrom: LocalDate? = null
        var capturedTo: LocalDate? = null
        val client = FondlistaHtmlSource { _, from, to ->
            capturedFrom = from
            capturedTo = to
            ""
        }
        val repo = HandelsbankenFundPriceRepository(client = client, dao = dao, isinSources = emptyList())

        repo.refresh("SHB0000442")

        val today = LocalDate.now()
        assertEquals(today.minusYears(5), capturedFrom)
        assertEquals(today, capturedTo)
    }

    @Test
    fun `refresh vid natverksfel behaller cachad data`() = runTest {
        dao.stored.add(FundPriceEntity(fundId = "SHB0000442", epochDay = LocalDate.of(2026, 6, 30).toEpochDay(), nav = 140.0, currency = "SEK"))
        val failingClient = FondlistaHtmlSource { _, _, _ -> throw IOException("nätverksfel") }
        val repo = HandelsbankenFundPriceRepository(client = failingClient, dao = dao, isinSources = emptyList())

        val success = repo.refresh("SHB0000442")

        assertFalse(success)
        val latest = repo.latestPrice("SHB0000442")
        assertEquals(140.0, latest?.nav ?: -1.0, 1e-9)
    }

    @Test
    fun `latestPrice for okand fond ar null`() = runTest {
        val repo = HandelsbankenFundPriceRepository(client = FondlistaHtmlSource { _, _, _ -> "" }, dao = dao, isinSources = emptyList())
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
        val repo = HandelsbankenFundPriceRepository(client = FondlistaHtmlSource { _, _, _ -> html }, dao = dao, isinSources = emptyList())

        val catalog = repo.fetchFundCatalog()

        assertEquals(2, catalog.companies.size)
        assertEquals("Handelsbanken", catalog.companies.first { it.id == "1" }.name)
        // Ofiltrerad katalog — bägge fonderna med, oavsett fondbolag.
        assertEquals(2, catalog.funds.size)
    }

    @Test
    fun `fetchFundCatalog vid natverksfel returnerar tom katalog utan att krascha`() = runTest {
        val failingClient = FondlistaHtmlSource { _, _, _ -> throw IOException("nätverksfel") }
        val repo = HandelsbankenFundPriceRepository(client = failingClient, dao = dao, isinSources = emptyList())

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
    fun `refreshSince hamtar och cachar historik fran forsta kallan som ger traff`() = runTest {
        val since = LocalDate.of(2020, 1, 1)
        val source = FakeIsinSource(history = { _, _, _ ->
            listOf(IsinPricePoint(epochDay = since.toEpochDay(), nav = 123.45, currency = "SEK"))
        })
        val repo = HandelsbankenFundPriceRepository(client = FondlistaHtmlSource { _, _, _ -> "" }, dao = dao, isinSources = listOf(source))

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
            client = FondlistaHtmlSource { _, _, _ -> "" },
            dao = dao,
            isinSources = listOf(failing, empty, working),
        )

        repo.refreshSince("SHB0000442", "SE0004297927", since)

        assertEquals(99.0, repo.latestPrice("SHB0000442")?.nav ?: -1.0, 1e-9)
    }

    @Test
    fun `refreshSince behaller cache om ingen kalla ger traff`() = runTest {
        dao.stored.add(FundPriceEntity(fundId = "SHB0000442", epochDay = 100, nav = 140.0, currency = "SEK"))
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _ -> "" },
            dao = dao,
            isinSources = listOf(FailingIsinSource(), FakeIsinSource()),
        )

        val success = repo.refreshSince("SHB0000442", "SE0004297927", LocalDate.of(2020, 1, 1))

        assertFalse(success)
        assertEquals(140.0, repo.latestPrice("SHB0000442")?.nav ?: -1.0, 1e-9)
    }

    @Test
    fun `suggestIsin returnerar forsta kallans forslag`() = runTest {
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _ -> "" },
            dao = dao,
            isinSources = listOf(FailingIsinSource(), FakeIsinSource(suggestion = { "SE0004297927" })),
        )

        assertEquals("SE0004297927", repo.suggestIsin("Spiltan Aktiefond Investmentbolag"))
    }

    @Test
    fun `suggestIsin ar null om ingen kalla har ett forslag`() = runTest {
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _ -> "" },
            dao = dao,
            isinSources = listOf(FakeIsinSource()),
        )

        assertNull(repo.suggestIsin("Okänd fond"))
    }

    @Test
    fun `findFundByIsin bygger en Fund med isin som fundId fran forsta kallan som ger traff`() = runTest {
        val source = FakeIsinSource(fundInfo = { isin ->
            if (isin == "LU0496367417") IsinFundInfo(name = "Franklin Gold and Prec Mtls A(acc)USD", currency = "USD") else null
        })
        val repo = HandelsbankenFundPriceRepository(client = FondlistaHtmlSource { _, _, _ -> "" }, dao = dao, isinSources = listOf(source))

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
            client = FondlistaHtmlSource { _, _, _ -> "" },
            dao = dao,
            isinSources = listOf(FailingIsinSource(), working),
        )

        val fund = repo.findFundByIsin("FI0008813365")

        assertEquals("Nordea Småbolagsfond Norden", fund?.name)
    }

    @Test
    fun `findFundByIsin ar null om ingen kalla kanner till isin`() = runTest {
        val repo = HandelsbankenFundPriceRepository(
            client = FondlistaHtmlSource { _, _, _ -> "" },
            dao = dao,
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
