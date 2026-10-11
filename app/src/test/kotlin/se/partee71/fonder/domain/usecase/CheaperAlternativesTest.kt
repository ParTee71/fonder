package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import java.time.LocalDate

/** ANA-9 på Beslut (NAV-9): vilka inspelade avgiftsbyten som visas som billigare alternativ. */
class CheaperAlternativesTest {

    private val today = LocalDate.of(2026, 10, 11)

    private fun meta(isin: String, fee: Double?, risk: Int? = 5, shown: List<String> = emptyList()) = FundMetadata(
        isin = isin, name = "Fond $isin", orderbookId = isin, totalFee = fee, managementFee = fee,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(), shownAlternativeIsins = shown,
    )

    private fun fee(
        id: Long,
        sell: String = "S",
        buy: String = "B1",
        daysAgo: Long = 0,
        valueKr: Double? = 96_000.0,
        followed: Boolean? = null,
        kind: SuggestionKind = SuggestionKind.FEE,
    ) = SuggestionRecord(
        id = id, suggestedAtEpochDay = today.minusDays(daysAgo).toEpochDay(), planIndex = 0,
        sellIsin = sell, buyIsin = buy, sellNavAtSuggestion = 100.0, buyNavAtSuggestion = 50.0,
        switchValueKr = valueKr, followed = followed, batchEpochMillis = 1_000 - daysAgo, kind = kind,
    )

    private val metadata = mapOf(
        "S" to meta("S", fee = 1.40, shown = listOf("B1", "B2")),
        "B1" to meta("B1", fee = 0.40),
        "B2" to meta("B2", fee = 0.90),
    )

    @Test
    fun `ett inspelat alternativ blir ett forslag med avgifter och arsbesparing`() {
        val alternative = CheaperAlternatives.resolve(listOf(fee(1)), metadata, today).single()

        assertEquals(1L, alternative.suggestion.recordId)
        assertEquals(SuggestionKind.FEE, alternative.suggestion.kind)
        assertEquals("Fond S", alternative.suggestion.sellFundName)
        assertEquals("Fond B1", alternative.suggestion.buyFundName)
        assertEquals(1.40, alternative.sellFeePercent, 1e-9)
        assertEquals(0.40, alternative.buyFeePercent, 1e-9)
        // Mockupens exempel: 1,40 % → 0,40 % på 96 000 kr sparar 960 kr per år.
        assertEquals(960.0, alternative.annualSavingsKr!!, 1e-6)
    }

    @Test
    fun `storst arsbesparing forst`() {
        val alternatives = CheaperAlternatives.resolve(listOf(fee(1, buy = "B2"), fee(2, buy = "B1")), metadata, today)

        assertEquals(listOf("B1", "B2"), alternatives.map { it.suggestion.buyIsin })
    }

    @Test
    fun `senaste inspelningen per par vinner och bar sin kvittering`() {
        val alternatives = CheaperAlternatives.resolve(
            listOf(fee(1, daysAgo = 2, followed = true), fee(2, daysAgo = 0)),
            metadata,
            today,
        )

        assertEquals(2L, alternatives.single().suggestion.recordId)
        assertEquals(false, alternatives.single().suggestion.followed)
    }

    @Test
    fun `en inspelning aldre an TTL_DAYS visas inte`() {
        assertTrue(CheaperAlternatives.resolve(listOf(fee(1, daysAgo = CheaperAlternatives.TTL_DAYS + 1)), metadata, today).isEmpty())
        assertEquals(1, CheaperAlternatives.resolve(listOf(fee(1, daysAgo = CheaperAlternatives.TTL_DAYS)), metadata, today).size)
    }

    @Test
    fun `ett alternativ som fondens senaste jamforelse inte langre visar visas inte`() {
        val withoutB1 = metadata + ("S" to meta("S", fee = 1.40, shown = listOf("B2")))

        assertEquals(listOf("B2"), CheaperAlternatives.resolve(listOf(fee(1), fee(2, buy = "B2")), withoutB1, today).map { it.suggestion.buyIsin })
    }

    @Test
    fun `okand avgift eller risk eller ett alternativ som inte langre ar billigare visas inte`() {
        listOf(
            metadata + ("B1" to meta("B1", fee = null)),
            metadata + ("B1" to meta("B1", fee = 0.40, risk = null)),
            metadata + ("B1" to meta("B1", fee = 1.40)),
            metadata - "B1",
        ).forEach { meta ->
            assertTrue(CheaperAlternatives.resolve(listOf(fee(1)), meta, today).isEmpty())
        }
    }

    @Test
    fun `bytesplanens rader blir aldrig billigare alternativ`() {
        assertTrue(CheaperAlternatives.resolve(listOf(fee(1, kind = SuggestionKind.RISK_PLAN)), metadata, today).isEmpty())
    }

    @Test
    fun `utan belopp ges ingen gissad besparing`() {
        val alternative = CheaperAlternatives.resolve(listOf(fee(1, valueKr = null)), metadata, today).single()

        assertNull(alternative.annualSavingsKr)
    }

    @Test
    fun `latestPerPair ger nyast forst oavsett indata`() {
        val latest = CheaperAlternatives.latestPerPair(listOf(fee(1, daysAgo = 3), fee(2, buy = "B2", daysAgo = 1), fee(3, daysAgo = 0)))

        assertEquals(listOf(3L, 2L), latest.map { it.id })
    }
}
