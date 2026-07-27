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
}
