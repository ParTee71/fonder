package se.partee71.fonder.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Fixturerna nedan är trimmade utdrag av verkliga JSON-svar från avanza.se:s odokumenterade
 * fond-API (verifierat live 2026-07-05 mot `/_api/fund-guide/search`, `/_api/fund-guide/guide`
 * och `/_api/fund-guide/chart` — se KRAVLISTA TP-14 för risknotis).
 */
class AvanzaJsonParserTest {

    private val searchResponse = """
        {"fundSearchViews":[
            {"isin":"SE0004297927","name":"Spiltan Aktiefond Investmentbolag","orderbookId":"325406","rating":3,"risk":4,"managementFee":0.2,"totalFee":0.22,"minimumBuy":50.0,"foreignExchange":false,"buyable":true},
            {"isin":"SE0002566349","name":"Spiltan Aktiefond Småland","orderbookId":"132510","rating":3,"risk":4,"managementFee":0.4,"totalFee":0.42,"minimumBuy":100.0,"foreignExchange":false,"buyable":true}
        ]}
    """.trimIndent()

    @Test
    fun `findByIsin hittar exakt trafft skiftlagesokansligt`() {
        val match = AvanzaJsonParser.findByIsin(searchResponse, "se0004297927")

        assertEquals("SE0004297927", match?.isin)
        assertEquals("325406", match?.orderbookId)
        assertEquals("Spiltan Aktiefond Investmentbolag", match?.name)
    }

    @Test
    fun `findByIsin returnerar null om isin saknas bland traffarna`() {
        assertNull(AvanzaJsonParser.findByIsin(searchResponse, "SE9999999999"))
    }

    @Test
    fun `bestMatch tar forsta (mest relevanta) traffen for namnsokning`() {
        val match = AvanzaJsonParser.bestMatch(searchResponse)

        assertEquals("SE0004297927", match?.isin)
    }

    @Test
    fun `bestMatch returnerar null vid tomt traffbatch`() {
        assertNull(AvanzaJsonParser.bestMatch("""{"fundSearchViews":[]}"""))
    }

    @Test
    fun `parseCurrency laser valuta fran guide-svaret`() {
        val guideResponse = """{"isin":"SE0004297927","name":"Spiltan Aktiefond Investmentbolag","nav":985.84,"currency":"SEK","rating":3}"""

        assertEquals("SEK", AvanzaJsonParser.parseCurrency(guideResponse))
    }

    @Test
    fun `parseCurrency ar null om svaret inte gar att tolka`() {
        assertNull(AvanzaJsonParser.parseCurrency("inte json"))
    }

    @Test
    fun `parseChart laser rakurser i angiven valuta`() {
        // Rått utdrag från /_api/fund-guide/chart/325406/2020-01-01/2020-06-01?raw=true.
        val chartResponse = """
            {"id":"325406","dataSerie":[
                {"x":1577919600000,"y":429.25},
                {"x":1578006000000,"y":426.13},
                {"x":1578351600000,"y":426.69}
            ]}
        """.trimIndent()

        val prices = AvanzaJsonParser.parseChart(chartResponse, currency = "SEK")

        assertEquals(3, prices.size)
        assertEquals(429.25, prices.first().nav, 1e-9)
        assertEquals("SEK", prices.first().currency)
        // Stämplarna är lokal midnatt i Stockholm (23:00:00Z i januari), inte UTC-midnatt: en
        // UTC-tolkning gav 2020-01-01 (nyårsdagen — ingen svensk fond sätter NAV då) och sköt
        // hela serien en dag fel. 2020-01-06 är trettondedag jul, därav hoppet till 01-07.
        assertEquals(
            listOf(LocalDate.of(2020, 1, 2), LocalDate.of(2020, 1, 3), LocalDate.of(2020, 1, 7)),
            prices.map { LocalDate.ofEpochDay(it.epochDay) },
        )
    }

    @Test
    fun `parseChart tappar inte mandagskurser`() {
        // Måndag 2026-07-27 kommer som söndag 2026-07-26 22:00Z (CEST). Tolkas stämpeln som UTC
        // dateras kursen på söndagen och kastas sedan av helgfiltret — varje måndag försvann.
        val chartResponse = """{"dataSerie":[{"x":1785103200000,"y":103.0}]}"""

        val prices = AvanzaJsonParser.parseChart(chartResponse, currency = "SEK")

        assertEquals(listOf(LocalDate.of(2026, 7, 27)), prices.map { LocalDate.ofEpochDay(it.epochDay) })
    }

    @Test
    fun `parseChart filtrerar bort punkter med null-varde`() {
        // Den allra första punkten i en "infinity"-serie saknar ofta värde (innan fondens start).
        val chartResponse = """{"id":"325406","dataSerie":[{"x":1322521200000,"y":null},{"x":1322694000000,"y":102.7}]}"""

        val prices = AvanzaJsonParser.parseChart(chartResponse, currency = "SEK")

        assertEquals(1, prices.size)
        assertEquals(102.7, prices.first().nav, 1e-9)
    }

    @Test
    fun `parseChart forkastar helgdaterade punkter`() {
        // Verkligt beteende hos källan (verifierat 2026-07-27 för AMF Aktiefond Småbolag):
        // serien hoppar över fredagen och ger en söndagsdaterad punkt i stället. Någon NAV den
        // dagen finns inte, och eftersom söndagen är NYARE än senaste handelsdag gjorde raden
        // `isPriceStale` (TP-17) falskt negativ — fonden ansågs färsk och slutade uppdateras
        // (issue #39).
        val chartResponse = """
            {"dataSerie":[
                {"x":1784764800000,"y":100.0},
                {"x":1784937600000,"y":101.0},
                {"x":1785024000000,"y":102.0},
                {"x":1785110400000,"y":103.0}
            ]}
        """.trimIndent()

        val prices = AvanzaJsonParser.parseChart(chartResponse, currency = "SEK")

        // Bara torsdagen och måndagen överlever.
        assertEquals(listOf(100.0, 103.0), prices.map { it.nav })
        assertEquals(
            listOf(java.time.LocalDate.of(2026, 7, 23), java.time.LocalDate.of(2026, 7, 27)),
            prices.map { java.time.LocalDate.ofEpochDay(it.epochDay) },
        )
    }

    @Test
    fun `parseChart ger tom lista om svaret inte gar att tolka`() {
        assertEquals(emptyList<Any>(), AvanzaJsonParser.parseChart("inte json", currency = "SEK"))
    }
}
