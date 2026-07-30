package se.partee71.fonder.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.model.FundScreenSortDirection

class AvanzaFundListRequestBuilderTest {

    @Test
    fun `tom fraga ger bara startIndex`() {
        val json = Json.parseToJsonElement(AvanzaFundListRequestBuilder.buildPayload(FundScreenQuery())).jsonObject

        assertEquals(setOf("startIndex"), json.keys)
        assertEquals(0, json["startIndex"]?.jsonPrimitive?.int)
    }

    @Test
    fun `kategoriska filter blir Filter-nycklar med varderna som JSON-array`() {
        val query = FundScreenQuery(
            fundType = listOf("Aktiefond"),
            region = listOf("Sverige", "Norden"),
            industry = listOf("Fastighetsbolag"),
            company = listOf("Länsförsäkringar"),
            risk = listOf("4"),
        )

        val json = Json.parseToJsonElement(AvanzaFundListRequestBuilder.buildPayload(query)).jsonObject

        assertEquals(listOf("Aktiefond"), json["fundTypeFilter"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(listOf("Sverige", "Norden"), json["commonRegionFilter"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(listOf("Fastighetsbolag"), json["industryFilter"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(listOf("Länsförsäkringar"), json["companyFilter"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(listOf("4"), json["riskFilter"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test
    fun `maxavgift fritext och sortering mappas`() {
        val query = FundScreenQuery(
            maxTotalFee = 0.4,
            nameContains = "index",
            sortField = "totalFee",
            sortDirection = FundScreenSortDirection.ASCENDING,
            startIndex = 20,
        )

        val json = Json.parseToJsonElement(AvanzaFundListRequestBuilder.buildPayload(query)).jsonObject

        assertEquals(0.4, json["maxTotalFee"]?.jsonPrimitive?.double ?: -1.0, 1e-9)
        assertEquals("index", json["name"]?.jsonPrimitive?.content)
        assertEquals("totalFee", json["sortField"]?.jsonPrimitive?.content)
        assertEquals("ASCENDING", json["sortDirection"]?.jsonPrimitive?.content)
        assertEquals(20, json["startIndex"]?.jsonPrimitive?.int)
    }

    @Test
    fun `descending sortering skickas rakt av`() {
        val query = FundScreenQuery(sortDirection = FundScreenSortDirection.DESCENDING)

        val json = Json.parseToJsonElement(AvanzaFundListRequestBuilder.buildPayload(query)).jsonObject

        assertEquals("DESCENDING", json["sortDirection"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tomma filterlistor utelamnas helt fran payloaden`() {
        val json = Json.parseToJsonElement(AvanzaFundListRequestBuilder.buildPayload(FundScreenQuery())).jsonObject

        assertFalse(json.containsKey("fundTypeFilter"))
        assertFalse(json.containsKey("commonRegionFilter"))
        assertFalse(json.containsKey("industryFilter"))
        assertFalse(json.containsKey("companyFilter"))
        assertFalse(json.containsKey("riskFilter"))
        assertTrue(!json.containsKey("maxTotalFee") && !json.containsKey("name") && !json.containsKey("sortField"))
    }
}
