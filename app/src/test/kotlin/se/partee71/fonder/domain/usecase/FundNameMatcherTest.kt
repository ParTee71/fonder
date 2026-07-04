package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.partee71.fonder.domain.model.Fund

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
}
