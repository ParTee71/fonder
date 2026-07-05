package se.partee71.fonder.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
    fun `parseChart ger tom lista om svaret inte gar att tolka`() {
        assertEquals(emptyList<Any>(), AvanzaJsonParser.parseChart("inte json", currency = "SEK"))
    }
}
