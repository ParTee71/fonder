package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCompany

class FundNameMatcherTest {

    @Test
    fun `exakt namn ger perfekt traff`() {
        val fund = Fund(fundId = "SHB0000442", name = "Handelsbanken Sverige (A1 SEK)")
        val match = FundNameMatcher.bestMatch("Handelsbanken Sverige (A1 SEK)", listOf(fund))

        assertEquals(fund, match?.fund)
        assertEquals(1.0, match?.confidence ?: 0.0, 1e-9)
    }

    @Test
    fun `extra ord fran exportens namn straffar inte ut en annars stark traff`() {
        // Exportens namn upprepar ibland fondbolaget före det riktiga fondnamnet.
        val fund = Fund(fundId = "0P00009XYZ", name = "Franklin Gold and Precious Metals Fund")
        val candidates = listOf(
            fund,
            Fund(fundId = "0P0000AAA", name = "Handelsbanken Sverige (A1 SEK)"),
        )

        val match = FundNameMatcher.bestMatch(
            "Franklin Templeton Franklin Gold and Precious Metals Fund",
            candidates,
        )

        assertEquals(fund, match?.fund)
    }

    @Test
    fun `ingen kandidat tillrackligt lik ger null`() {
        val candidates = listOf(Fund(fundId = "0P0000AAA", name = "Handelsbanken Sverige (A1 SEK)"))

        val match = FundNameMatcher.bestMatch("Nordea Rahastoyhtiö Suomi Oy Småbolagsfond Norden", candidates)

        assertNull(match)
    }

    @Test
    fun `tom kandidatlista ger null`() {
        assertNull(FundNameMatcher.bestMatch("Vilken fond som helst", emptyList()))
    }

    @Test
    fun `fondbolagsledtrad avgor mellan annars lika traffar`() {
        // Fondnamnet ensamt ger exakt samma likhet mot båda kandidaterna (tie) — bara
        // fondbolagsledtråden ur exportraden avgör vilken som är rätt.
        val fundA = Fund(fundId = "A", name = "Aktiebolag Ett Fond Sverige")
        val fundB = Fund(fundId = "B", name = "Aktiebolag Tva Fond Sverige")
        val companies = listOf(
            FundCompany(id = "2", name = "Aktiebolag Ett AB"),
            FundCompany(id = "3", name = "Aktiebolag Tva AB"),
        )

        val match = FundNameMatcher.bestMatch(
            importedFundName = "Fond Sverige",
            candidates = listOf(fundA, fundB),
            importedCompanyName = "Aktiebolag Ett AB",
            companies = companies,
        )

        assertEquals(fundA, match?.fund)
    }

    @Test
    fun `okand fondbolagsledtrad paverkar inte matchningen`() {
        val fund = Fund(fundId = "SHB0000442", name = "Handelsbanken Sverige (A1 SEK)")

        val match = FundNameMatcher.bestMatch(
            importedFundName = "Handelsbanken Sverige (A1 SEK)",
            candidates = listOf(fund),
            importedCompanyName = "Ett bolag som inte finns i katalogen",
            companies = emptyList(),
        )

        assertEquals(fund, match?.fund)
        assertEquals(1.0, match?.confidence ?: 0.0, 1e-9)
    }
}
