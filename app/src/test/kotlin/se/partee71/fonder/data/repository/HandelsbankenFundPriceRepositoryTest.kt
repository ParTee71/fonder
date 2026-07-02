package se.partee71.fonder.data.repository

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import se.partee71.fonder.data.network.FondlistaHtmlSource
import se.partee71.fonder.data.room.daos.FundPriceDao
import se.partee71.fonder.data.room.entities.FundPriceEntity
import java.io.IOException
import java.time.LocalDate

private class FakeFundPriceDao : FundPriceDao {
    val stored = mutableListOf<FundPriceEntity>()

    override suspend fun getLatest(fundId: String): FundPriceEntity? =
        stored.filter { it.fundId == fundId }.maxByOrNull { it.epochDay }

    override suspend fun getRange(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPriceEntity> =
        stored.filter { it.fundId == fundId && it.epochDay in fromEpochDay..toEpochDay }.sortedBy { it.epochDay }

    override suspend fun upsertAll(prices: List<FundPriceEntity>) {
        prices.forEach { new ->
            stored.removeAll { it.fundId == new.fundId && it.epochDay == new.epochDay }
            stored.add(new)
        }
    }
}

class HandelsbankenFundPriceRepositoryTest {

    private val dao = FakeFundPriceDao()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    @Test
    fun `refresh parsar och cachar kurser fran kallan`() = runTest {
        val html = historyHtml(fundId = "SHB0000442", nav = "150,00", currency = "SEK", date = "2026-07-01")
        val repo = HandelsbankenFundPriceRepository(client = FondlistaHtmlSource { _, _, _ -> html }, dao = dao)

        repo.refresh("SHB0000442")

        val latest = repo.latestPrice("SHB0000442")
        assertEquals(150.0, latest?.nav ?: -1.0, 1e-9)
        assertEquals("SEK", latest?.currency)
    }

    @Test
    fun `refresh vid natverksfel behaller cachad data`() = runTest {
        dao.stored.add(FundPriceEntity(fundId = "SHB0000442", epochDay = LocalDate.of(2026, 6, 30).toEpochDay(), nav = 140.0, currency = "SEK"))
        val failingClient = FondlistaHtmlSource { _, _, _ -> throw IOException("nätverksfel") }
        val repo = HandelsbankenFundPriceRepository(client = failingClient, dao = dao)

        repo.refresh("SHB0000442")

        val latest = repo.latestPrice("SHB0000442")
        assertEquals(140.0, latest?.nav ?: -1.0, 1e-9)
    }

    @Test
    fun `latestPrice for okand fond ar null`() = runTest {
        val repo = HandelsbankenFundPriceRepository(client = FondlistaHtmlSource { _, _, _ -> "" }, dao = dao)
        assertNull(repo.latestPrice("OKAND"))
    }

    @Test
    fun `fetchFundCatalog filtrerar Handelsbankens fonder`() = runTest {
        val html = """
            <select id="FundId" name="FundId"><option value="">Välj fond</option>
            <option value="0P000083RV">AstraZeneca Allemansfond</option>
            <option value="SHB0000442">Handelsbanken Amerika Småbolag Tema</option>
            </select>
        """.trimIndent()
        val repo = HandelsbankenFundPriceRepository(client = FondlistaHtmlSource { _, _, _ -> html }, dao = dao)

        val catalog = repo.fetchFundCatalog()

        assertEquals(1, catalog.size)
        assertEquals("SHB0000442", catalog.first().fundId)
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
