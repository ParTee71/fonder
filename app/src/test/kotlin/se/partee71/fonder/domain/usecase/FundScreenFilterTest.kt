package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.model.FundScreenSortDirection
import se.partee71.fonder.domain.model.FundTag

/**
 * Speglar den live-verifierade semantiken (KRAVLISTA TP-21, 2026-07-30): OR mellan flera
 * värden inom samma dimension (`companyFilter` med två bolag gav facken summerade), AND
 * mellan olika dimensioner (`commonRegionFilter` + `industryFilter` gav snittet).
 */
class FundScreenFilterTest {

    private fun fund(
        isin: String,
        name: String = isin,
        totalFee: Double? = 0.2,
        companyName: String? = null,
        risk: Int? = null,
        tags: List<FundTag> = emptyList(),
    ) = FundMetadata(
        isin = isin,
        name = name,
        orderbookId = isin,
        totalFee = totalFee,
        managementFee = totalFee,
        category = null,
        fundType = null,
        companyName = companyName,
        risk = risk,
        indexFund = false,
        startDateEpochDay = null,
        minimumBuy = null,
        tags = tags,
    )

    private val sverigeAktie = fund(
        "SE1",
        tags = listOf(FundTag("Aktiefond", "TYPE"), FundTag("Sverige", "COMMON_REGION")),
    )
    private val globalAktie = fund(
        "SE2",
        tags = listOf(FundTag("Aktiefond", "TYPE"), FundTag("Global", "COMMON_REGION")),
    )
    private val sverigeRanta = fund(
        "SE3",
        tags = listOf(FundTag("Räntefond", "TYPE"), FundTag("Sverige", "COMMON_REGION")),
    )

    @Test
    fun `flera varden i samma dimension matchar som OR`() {
        val query = FundScreenQuery(company = listOf("Länsförsäkringar", "Storebrand"))
        val lf = fund("LF", companyName = "Länsförsäkringar")
        val storebrand = fund("SB", companyName = "Storebrand")
        val annat = fund("X", companyName = "SEB")

        assertTrue(FundScreenFilter.matches(lf, query))
        assertTrue(FundScreenFilter.matches(storebrand, query))
        assertFalse(FundScreenFilter.matches(annat, query))
    }

    @Test
    fun `flera varden i olika dimensioner matchar som AND`() {
        // Motsvarar den verifierade siffran: Sverige (120) + Fastighetsbolag (24) gav 0 —
        // ingen av testfonderna har båda taggarna samtidigt.
        val query = FundScreenQuery(region = listOf("Sverige"), industry = listOf("Fastighetsbolag"))

        assertFalse(FundScreenFilter.matches(sverigeAktie, query))
        assertFalse(FundScreenFilter.matches(globalAktie, query))

        val sverigeFastighet = fund(
            "SE4",
            tags = listOf(FundTag("Fastighetsbolag", "INDUSTRY"), FundTag("Sverige", "COMMON_REGION")),
        )
        assertTrue(FundScreenFilter.matches(sverigeFastighet, query))
    }

    @Test
    fun `en rad utan tagg i en filtrerad dimension matchar inte`() {
        val query = FundScreenQuery(industry = listOf("Fastighetsbolag"))

        assertFalse(FundScreenFilter.matches(sverigeAktie, query))
    }

    @Test
    fun `fundType och region kombineras som AND, bada matchar sverige-aktiefonden`() {
        val query = FundScreenQuery(fundType = listOf("Aktiefond"), region = listOf("Sverige"))

        assertTrue(FundScreenFilter.matches(sverigeAktie, query))
        assertFalse(FundScreenFilter.matches(globalAktie, query))
        assertFalse(FundScreenFilter.matches(sverigeRanta, query))
    }

    @Test
    fun `otherRegion alignment interestType och misc matchar mot respektive tagg`() {
        val taiwan = fund("TW", tags = listOf(FundTag("Taiwan", "OTHER_REGION")))
        val smabolag = fund("SB", tags = listOf(FundTag("Småbolag", "ALIGNMENT")))
        val sekRanta = fund("SEK", tags = listOf(FundTag("SEK", "INTEREST")))
        val ovrigt = fund("OV", tags = listOf(FundTag("Övriga", "MISC")))

        assertTrue(FundScreenFilter.matches(taiwan, FundScreenQuery(otherRegion = listOf("Taiwan"))))
        assertFalse(FundScreenFilter.matches(sverigeAktie, FundScreenQuery(otherRegion = listOf("Taiwan"))))

        assertTrue(FundScreenFilter.matches(smabolag, FundScreenQuery(alignment = listOf("Småbolag"))))
        assertFalse(FundScreenFilter.matches(sverigeAktie, FundScreenQuery(alignment = listOf("Småbolag"))))

        assertTrue(FundScreenFilter.matches(sekRanta, FundScreenQuery(interestType = listOf("SEK"))))
        assertFalse(FundScreenFilter.matches(sverigeAktie, FundScreenQuery(interestType = listOf("SEK"))))

        assertTrue(FundScreenFilter.matches(ovrigt, FundScreenQuery(misc = listOf("Övriga"))))
        assertFalse(FundScreenFilter.matches(sverigeAktie, FundScreenQuery(misc = listOf("Övriga"))))
    }

    @Test
    fun `risk matchar mot fondens risksiffra som strang`() {
        val query = FundScreenQuery(risk = listOf("4"))

        assertTrue(FundScreenFilter.matches(fund("A", risk = 4), query))
        assertFalse(FundScreenFilter.matches(fund("B", risk = 5), query))
        assertFalse("Okänd risk ska inte matcha ett satt riskfilter", FundScreenFilter.matches(fund("C", risk = null), query))
    }

    @Test
    fun `maxTotalFee exkluderar dyrare fonder och fonder utan kand avgift`() {
        val query = FundScreenQuery(maxTotalFee = 0.3)

        assertTrue(FundScreenFilter.matches(fund("A", totalFee = 0.21), query))
        assertFalse(FundScreenFilter.matches(fund("B", totalFee = 0.73), query))
        assertFalse(FundScreenFilter.matches(fund("C", totalFee = null), query))
    }

    @Test
    fun `fritext matchar namn skiftlageokansligt`() {
        val query = FundScreenQuery(nameContains = "index")

        assertTrue(FundScreenFilter.matches(fund("A", name = "Länsförsäkringar Sverige Index"), query))
        assertFalse(FundScreenFilter.matches(fund("B", name = "Avanza Zero"), query))
    }

    @Test
    fun `apply sorterar pa avgift stigande och sidbryter med sidstorlek 20`() {
        val funds = (1..25).map { fund("F$it", totalFee = (26 - it).toDouble()) }
        val query = FundScreenQuery(sortField = "totalFee", sortDirection = FundScreenSortDirection.ASCENDING)

        val page = FundScreenFilter.apply(funds, query)

        assertEquals(20, page.size)
        assertEquals("F25", page.first().isin)
        assertEquals(1.0, page.first().totalFee ?: -1.0, 1e-9)
    }

    @Test
    fun `apply respekterar startIndex for foljande sidor`() {
        val funds = (1..25).map { fund("F$it", totalFee = it.toDouble()) }
        val query = FundScreenQuery(sortField = "totalFee", startIndex = 20)

        val page = FundScreenFilter.apply(funds, query)

        assertEquals(5, page.size)
        assertEquals("F21", page.first().isin)
    }

    @Test
    fun `apply utan sortField sorterar pa namn`() {
        val funds = listOf(fund("A", name = "Zebra"), fund("B", name = "Alfa"))

        val page = FundScreenFilter.apply(funds, FundScreenQuery())

        assertEquals(listOf("Alfa", "Zebra"), page.map { it.name })
    }
}
