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

    @Test
    fun `fondbolagsledtrad avgor mellan annars lika traffar`() {
        // Fondnamnet ensamt ger exakt samma likhet mot båda kandidaterna (tie) — bara
        // fondbolagsledtråden ur exportraden avgör vilken som är rätt. Kandidaternas namn
        // inleds med bolagets varumärke, precis som i den verkliga katalogen.
        val fundA = Fund(fundId = "A", name = "Aktiebolag Ett Fond Sverige")
        val fundB = Fund(fundId = "B", name = "Aktiebolag Tva Fond Sverige")

        val match = FundNameMatcher.bestMatch(
            importedFundName = "Fond Sverige",
            candidates = listOf(fundA, fundB),
            importedCompanyName = "Aktiebolag Ett AB",
        )

        assertEquals(fundA, match?.fund)
    }

    @Test
    fun `bolagsnamn med bolagsform matchar katalogfond som borjar med varumarket`() {
        // "Handelsbanken Fonder AB" → kärnnamn "Handelsbanken"; katalogfonden inleds med
        // "Handelsbanken" och ska få bolagsförsprånget. (Tidigare tvåstegsmatchning mot
        // katalogbolaget "Handelsbanken" nådde inte likhetströskeln och gav inget försprång.)
        val correct = Fund(fundId = "SHB0000442", name = "Handelsbanken Sverige")
        val distractor = Fund(fundId = "0P0000AAA", name = "Nordea Sverige")

        val match = FundNameMatcher.bestMatch(
            importedFundName = "Sverige",
            candidates = listOf(correct, distractor),
            importedCompanyName = "Handelsbanken Fonder AB",
        )

        assertEquals(correct, match?.fund)
    }

    @Test
    fun `okand fondbolagsledtrad paverkar inte matchningen`() {
        val fund = Fund(fundId = "SHB0000442", name = "Handelsbanken Sverige (A1 SEK)")

        val match = FundNameMatcher.bestMatch(
            importedFundName = "Handelsbanken Sverige (A1 SEK)",
            candidates = listOf(fund),
            importedCompanyName = "Ett bolag som inte finns i katalogen",
        )

        assertEquals(fund, match?.fund)
        assertEquals(1.0, match?.confidence ?: 0.0, 1e-9)
    }
}
