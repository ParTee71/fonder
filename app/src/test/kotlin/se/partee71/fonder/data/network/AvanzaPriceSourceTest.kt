package se.partee71.fonder.data.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

private class FakeAvanzaSource(
    private val searchResponse: (String) -> String = { """{"fundSearchViews":[]}""" },
    private val guideResponse: String = """{"currency":"SEK"}""",
    private val chartResponse: String = """{"dataSerie":[]}""",
) : AvanzaSource {
    var lastChartOrderbookId: String? = null

    override suspend fun search(query: String): String = searchResponse(query)
    override suspend fun fetchGuide(orderbookId: String): String = guideResponse
    override suspend fun fetchChart(orderbookId: String, from: LocalDate, to: LocalDate): String {
        lastChartOrderbookId = orderbookId
        return chartResponse
    }
    override suspend fun fetchFundList(requestBody: String): String = """{"fundListViews":[]}"""
}

class AvanzaPriceSourceTest {

    private val isin = "SE0004297927"
    private val searchHit = """{"fundSearchViews":[{"isin":"$isin","name":"Spiltan Aktiefond Investmentbolag","orderbookId":"325406"}]}"""

    @Test
    fun `fetchHistory markerar kurserna som kronor aven for en fond i annan valuta`() = runTest {
        // Chart-svaret är alltid värdet för en svensk sparare, i kronor — även när fondens
        // egen valuta (ur guide-anropet) är en annan. Verifierat 2026-07-28: CPR Invest hade
        // NAV 193,48 USD hos fondlista samma dag som Avanza gav 1878,75. Att märka punkterna
        // med guide-valutan var en felmärkning som gjorde det omöjligt att sålla bort kurser
        // i fel valuta (issue #41).
        val fakeSource = FakeAvanzaSource(
            searchResponse = { searchHit },
            guideResponse = """{"currency":"USD"}""",
            chartResponse = """{"dataSerie":[{"x":1577919600000,"y":429.25}]}""",
        )
        val source = AvanzaPriceSource(fakeSource)

        val prices = source.fetchHistory(isin, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 6, 1))

        assertEquals("325406", fakeSource.lastChartOrderbookId)
        assertEquals(1, prices.size)
        assertEquals("SEK", prices.first().currency)
        assertEquals(429.25, prices.first().nav, 1e-9)
    }

    @Test
    fun `fetchHistory ger tom lista om isin inte hittas`() = runTest {
        val source = AvanzaPriceSource(FakeAvanzaSource(searchResponse = { """{"fundSearchViews":[]}""" }))

        assertTrue(source.fetchHistory(isin, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 6, 1)).isEmpty())
    }

    @Test
    fun `fetchHistory antar SEK om guide-anropet saknar valuta`() = runTest {
        val fakeSource = FakeAvanzaSource(
            searchResponse = { searchHit },
            guideResponse = """{}""",
            chartResponse = """{"dataSerie":[{"x":1577919600000,"y":100.0}]}""",
        )
        val source = AvanzaPriceSource(fakeSource)

        val prices = source.fetchHistory(isin, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 6, 1))

        assertEquals("SEK", prices.first().currency)
    }

    @Test
    fun `fetchHistory hamtar kurser aven nar guide-anropet felar`() = runTest {
        // Regression: valutan ur `guide` används inte här (punkterna märks alltid VALUE_CURRENCY),
        // men anropet gjordes ändå — svarade källan 500 på /guide medan /chart var friskt
        // slutade fonden uppdateras på grund av en endpoint vars svar kastades bort.
        val guideFails = object : AvanzaSource {
            override suspend fun search(query: String): String = searchHit
            override suspend fun fetchGuide(orderbookId: String): String = throw IOException("500 från /guide")
            override suspend fun fetchChart(orderbookId: String, from: LocalDate, to: LocalDate): String =
                """{"dataSerie":[{"x":1577919600000,"y":429.25}]}"""
            override suspend fun fetchFundList(requestBody: String): String = """{"fundListViews":[]}"""
        }

        val prices = AvanzaPriceSource(guideFails).fetchHistory(isin, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 6, 1))

        assertEquals(1, prices.size)
        assertEquals(429.25, prices.first().nav, 1e-9)
        assertEquals("SEK", prices.first().currency)
    }

    @Test(expected = IOException::class)
    fun `fetchHistory kastar vidare natverksfel till anroparen`() = runTest {
        val failingSource = object : AvanzaSource {
            override suspend fun search(query: String): String = throw IOException("nätverksfel")
            override suspend fun fetchGuide(orderbookId: String): String = ""
            override suspend fun fetchChart(orderbookId: String, from: LocalDate, to: LocalDate): String = ""
            override suspend fun fetchFundList(requestBody: String): String = ""
        }
        AvanzaPriceSource(failingSource).fetchHistory(isin, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 6, 1))
    }

    @Test
    fun `suggestIsin returnerar basta traffens isin`() = runTest {
        val source = AvanzaPriceSource(FakeAvanzaSource(searchResponse = { searchHit }))

        assertEquals(isin, source.suggestIsin("Spiltan Aktiefond Investmentbolag"))
    }

    @Test
    fun `suggestIsin ar null utan traffar`() = runTest {
        val source = AvanzaPriceSource(FakeAvanzaSource())

        assertNull(source.suggestIsin("Okänd fond"))
    }

    @Test
    fun `findFund returnerar namn och valuta for ett kant isin`() = runTest {
        val source = AvanzaPriceSource(
            FakeAvanzaSource(searchResponse = { searchHit }, guideResponse = """{"currency":"USD"}"""),
        )

        val info = source.findFund(isin)

        assertEquals("Spiltan Aktiefond Investmentbolag", info?.name)
        assertEquals("USD", info?.currency)
    }

    @Test
    fun `findFund ar null om isin inte hittas`() = runTest {
        val source = AvanzaPriceSource(FakeAvanzaSource())

        assertNull(source.findFund(isin))
    }
}
