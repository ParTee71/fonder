package se.partee71.fonder.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Fixturen nedan är ett trimmat utdrag av ett verkligt svar från avanza.se:s odokumenterade
 * `fund-guide/list`-API (verifierat live 2026-07-30, se KRAVLISTA TP-21).
 */
class AvanzaFundListParserTest {

    private val response = """
        {
          "fundListViews": [
            {
              "isin": "SE0001466368",
              "name": "Handelsbanken Sverige Index Criteria",
              "orderbookId": "20993",
              "totalFee": 0.73,
              "managementFee": 0.65,
              "category": "Sverige",
              "fundType": "EQUITY_FUND",
              "companyName": "Handelsbanken",
              "risk": 4,
              "sharpeRatio": 0.73,
              "developmentOneYear": 0.083,
              "indexFund": true,
              "startDate": "2005-06-28",
              "minimumBuy": 100.0,
              "tagList": [
                {"title": "Aktiefond", "fundTagCategory": "TYPE"},
                {"title": "Sverige", "fundTagCategory": "COMMON_REGION"}
              ]
            },
            {
              "isin": "SE0000581434",
              "name": "Länsförsäkringar Sverige Index",
              "orderbookId": "12345",
              "totalFee": 0.21,
              "managementFee": 0.2,
              "category": "Sverige",
              "fundType": "EQUITY_FUND",
              "companyName": "Länsförsäkringar",
              "indexFund": true,
              "tagList": [
                {"title": "Aktiefond", "fundTagCategory": "TYPE"},
                {"title": "Sverige", "fundTagCategory": "COMMON_REGION"}
              ]
            }
          ],
          "totalNoFunds": 1499,
          "filterCounts": {
            "fundTypeCounts": [
              {"title": "Aktiefond", "count": 1062, "type": "fundType", "active": false, "group": 0},
              {"title": "Räntefond", "count": 271, "type": "fundType", "active": false, "group": 0}
            ],
            "commonRegionCounts": [
              {"title": "Sverige", "count": 120, "type": "commonRegion", "active": false, "group": 0}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `parse laser alla falt for en fullstandig rad`() {
        val page = AvanzaFundListParser.parse(response)!!

        val fund = page.funds.first()
        assertEquals("SE0001466368", fund.isin)
        assertEquals("Handelsbanken Sverige Index Criteria", fund.name)
        assertEquals("20993", fund.orderbookId)
        assertEquals(0.73, fund.totalFee ?: -1.0, 1e-9)
        assertEquals(0.65, fund.managementFee ?: -1.0, 1e-9)
        assertEquals("Sverige", fund.category)
        assertEquals("EQUITY_FUND", fund.fundType)
        assertEquals("Handelsbanken", fund.companyName)
        assertEquals(4, fund.risk)
        assertEquals(0.083, fund.developmentOneYear ?: -1.0, 1e-9)
        assertTrue(fund.indexFund)
        assertEquals(LocalDate.of(2005, 6, 28).toEpochDay(), fund.startDateEpochDay)
        assertEquals(100.0, fund.minimumBuy ?: -1.0, 1e-9)
        assertEquals(2, fund.tags.size)
        assertEquals("Aktiefond", fund.tags.first { it.category == "TYPE" }.title)
        assertEquals("Sverige", fund.tags.first { it.category == "COMMON_REGION" }.title)
    }

    @Test
    fun `parse ger null-falt for en rad utan risk, startdatum och minimumBuy`() {
        val page = AvanzaFundListParser.parse(response)!!

        val fund = page.funds.first { it.isin == "SE0000581434" }
        assertNull(fund.risk)
        assertNull(fund.startDateEpochDay)
        assertNull(fund.minimumBuy)
        assertNull(fund.developmentOneYear)
    }

    @Test
    fun `parse tar med totalNoFunds`() {
        assertEquals(1499, AvanzaFundListParser.parse(response)?.totalNoFunds)
    }

    @Test
    fun `parse bygger vokabular enbart ur svarets egna type-falt, ingen hardkodning`() {
        val vocabulary = AvanzaFundListParser.parse(response)!!.vocabulary

        assertEquals(listOf("Aktiefond", "Räntefond"), vocabulary.filters["fundType"])
        assertEquals(listOf("Sverige"), vocabulary.filters["commonRegion"])
        assertNull("Okänd dimension ska inte finnas", vocabulary.filters["industry"])
    }

    @Test
    fun `parse okant nytt falt i svaret ignoreras utan att parsningen faller`() {
        val medOkantFalt = response.replace(
            "\"minimumBuy\": 100.0,",
            "\"minimumBuy\": 100.0, \"esgScore\": 19.09, \"nagotHeltNytt\": {\"x\": 1},",
        )

        val page = AvanzaFundListParser.parse(medOkantFalt)!!

        assertEquals(2, page.funds.size)
    }

    @Test
    fun `parse av trasig json ger null i stallet for en krasch eller en tom sida`() {
        // Null, inte en tom sida: ett otolkbart svar ska falla tillbaka på cachen hos
        // anroparen, inte se ut som ett giltigt svar med noll fonder (issue #75).
        assertNull(AvanzaFundListParser.parse("inte json"))
    }

    @Test
    fun `parse av ett svar dar en fondpost saknar obligatoriskt falt ger null`() {
        // FundListView.isin är obligatorisk, så en enda ofullständig post fäller avkodningen av
        // hela svaret — det får inte tolkas som "källan hittade inga fonder".
        val utanIsin = response.replace("\"isin\": \"SE0001466368\",", "")

        assertNull(AvanzaFundListParser.parse(utanIsin))
    }
}
