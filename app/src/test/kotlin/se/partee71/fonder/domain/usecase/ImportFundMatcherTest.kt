package se.partee71.fonder.domain.usecase

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.Fund

/**
 * Prioritetsordningen i [ImportFundMatcher] (KRAVLISTA TP-13/TP-14/TP-18). Viktigast här är
 * steg 2, tillagt i issue #37: en namnkandidat ur fondlista-katalogen som **verifieras mot
 * ISIN** ska vinna över Avanza-uppslaget, så fonden får ett riktigt `FundId` i stället för att
 * identifieras med sitt ISIN.
 */
class ImportFundMatcherTest {

    private val katalogFond = Fund(fundId = "SHB0000442", name = "Handelsbanken Amerika Småbolag Tema")
    private val avanzaFond = Fund(fundId = "SE0004297927", name = "Handelsbanken Amerika Småbolag Tema", isin = "SE0004297927")

    private suspend fun match(
        isin: String = "SE0004297927",
        fundName: String = "Handelsbanken Amerika Småbolag Tema",
        catalogFunds: List<Fund> = listOf(katalogFond),
        trackedFunds: List<Fund> = emptyList(),
        findFundByIsin: suspend (String) -> Fund? = { null },
        lookupIsin: suspend (String) -> String? = { null },
    ) = ImportFundMatcher.match(
        isin = isin,
        fundName = fundName,
        fundCompanyName = "Handelsbanken Fonder AB",
        catalogFunds = catalogFunds,
        trackedFunds = trackedFunds,
        findFundByIsin = findFundByIsin,
        lookupIsin = lookupIsin,
    )

    @Test
    fun `redan bevakad fond med samma isin vinner over allt annat`() = runTest {
        val bevakad = Fund(fundId = "SHB0000442", name = "Redan bevakad", isin = "SE0004297927")

        val result = match(
            trackedFunds = listOf(bevakad),
            findFundByIsin = { avanzaFond },
            lookupIsin = { "SE0004297927" },
        )

        assertEquals(bevakad, result?.fund)
        assertEquals(1.0, result?.confidence ?: -1.0, 1e-9)
    }

    @Test
    fun `isin-verifierad katalogtraff vinner over Avanza-uppslaget`() = runTest {
        var avanzaAnropad = false

        val result = match(
            findFundByIsin = { avanzaAnropad = true; avanzaFond },
            lookupIsin = { fundId -> if (fundId == "SHB0000442") "SE0004297927" else null },
        )

        // Riktigt FundId från katalogen, inte ISIN:et som identitet.
        assertEquals("SHB0000442", result?.fund?.fundId)
        assertEquals("SE0004297927", result?.fund?.isin)
        assertEquals(1.0, result?.confidence ?: -1.0, 1e-9)
        assertFalse("Avanza ska inte behöva anropas när katalogen räcker", avanzaAnropad)
    }

    @Test
    fun `fel isin pa katalogkandidaten faller tillbaka till Avanza`() = runTest {
        // Namnmatchningen kan landa på fel andelsklass — verifieringen fångar det i stället
        // för att tyst acceptera en felaktig fond.
        val result = match(
            findFundByIsin = { avanzaFond },
            lookupIsin = { "SE0009999999" },
        )

        assertEquals(avanzaFond, result?.fund)
    }

    @Test
    fun `utan isin-uppslag och utan Avanza aterstar den overifierade namntraffen`() = runTest {
        // Namnet skiljer sig något från katalogens, så träffen är just en gissning.
        val result = match(fundName = "Handelsbanken Amerika Småbolag")

        assertEquals(katalogFond, result?.fund)
        // Ingen ISIN sätts på en overifierad träff — det gör bara steg 2.
        assertNull(result?.fund?.isin)
        assertTrue("Overifierad namnträff ska inte påstå full säkerhet", (result?.confidence ?: 1.0) < 1.0)
    }

    @Test
    fun `ingen namnkandidat och ingen kalla ger ingen traff`() = runTest {
        val result = match(fundName = "Helt Okänd Fond", catalogFunds = emptyList())

        assertNull(result)
    }

    @Test
    fun `isin-verifiering hoppas over nar ingen namnkandidat finns`() = runTest {
        var uppslag = 0

        match(
            fundName = "Helt Okänd Fond",
            catalogFunds = emptyList(),
            lookupIsin = { uppslag++; "SE0004297927" },
        )

        assertEquals(0, uppslag)
    }

    @Test
    fun `andra rankade kandidaten verifieras nar toppkandidaten har fel isin`() = runTest {
        // Verklig andelsklasskollision (Handelsbanken Sverige-familjen): den suffixlösa
        // basfonden är rätt träff (ISIN SE0000582033), men Jaccard rankar ett suffixerat
        // syskon ("A10 SEK") högre eftersom det delar suffix-tokenet "sek" med importradens
        // namn. Toppkandidatens ISIN stämmer inte — steg 2 ska då pröva nästa rankade
        // kandidat i stället för att ge upp direkt till Avanza.
        val basfond = Fund(fundId = "0P00000F8J", name = "Handelsbanken Sverige")
        val a10 = Fund(fundId = "SHB0000387", name = "Handelsbanken Sverige (A10 SEK)")
        var avanzaAnropad = false

        val result = match(
            isin = "SE0000582033",
            fundName = "Handelsbanken Sverige (A1 SEK)",
            catalogFunds = listOf(a10, basfond),
            findFundByIsin = { avanzaAnropad = true; null },
            lookupIsin = { fundId -> if (fundId == "0P00000F8J") "SE0000582033" else null },
        )

        assertEquals("0P00000F8J", result?.fund?.fundId)
        assertEquals("SE0000582033", result?.fund?.isin)
        assertEquals(1.0, result?.confidence ?: -1.0, 1e-9)
        assertFalse("Avanza ska inte behöva anropas när en rankad katalogkandidat verifieras", avanzaAnropad)
    }
}
