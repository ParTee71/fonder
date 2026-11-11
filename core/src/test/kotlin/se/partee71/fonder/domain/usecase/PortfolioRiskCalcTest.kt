package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortfolioRiskCalcTest {

    private fun metadata(isin: String, risk: Int?) = fundMetadata(isin, risk = risk)

    @Test
    fun `tom portfolj ger null och inga exkluderade`() {
        val result = PortfolioRiskCalc.compute(emptyList(), emptyMap())

        assertNull(result.weightedAverageRisk)
        assertEquals(0.0, result.includedValueKr, 1e-9)
        assertEquals(0, result.excludedCount)
    }

    @Test
    fun `ett enda innehav ger exakt dess egen riskniva`() {
        val holdings = listOf(portfolioHolding("A", currentValue = 1000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 5))

        val result = PortfolioRiskCalc.compute(holdings, metadataByIsin)

        assertEquals(5.0, result.weightedAverageRisk ?: -1.0, 1e-9)
        assertEquals(1000.0, result.includedValueKr, 1e-9)
        assertEquals(0, result.excludedCount)
    }

    @Test
    fun `handraknat vardeviktat snitt over tva innehav`() {
        val holdings = listOf(
            portfolioHolding("A", currentValue = 300.0),
            portfolioHolding("B", currentValue = 700.0)
        )
        val metadataByIsin = mapOf(
            "SEA" to metadata("SEA", risk = 2),
            "SEB" to metadata("SEB", risk = 6)
        )

        val result = PortfolioRiskCalc.compute(holdings, metadataByIsin)

        // (300*2 + 700*6) / 1000 = (600 + 4200) / 1000 = 4,8.
        assertEquals(4.8, result.weightedAverageRisk ?: -1.0, 1e-9)
    }

    @Test
    fun `innehav utan isin exkluderas och rakas separat`() {
        val holdings = listOf(portfolioHolding("A", currentValue = 1000.0, isin = null))

        val result = PortfolioRiskCalc.compute(holdings, emptyMap())

        assertNull(result.weightedAverageRisk)
        assertEquals(1, result.excludedCount)
    }

    @Test
    fun `innehav utan metadatatraff exkluderas och rakas separat`() {
        val holdings = listOf(portfolioHolding("A", currentValue = 1000.0))

        val result = PortfolioRiskCalc.compute(holdings, emptyMap())

        assertNull(result.weightedAverageRisk)
        assertEquals(1, result.excludedCount)
    }

    @Test
    fun `innehav med metadata men okand risk exkluderas, aldrig en gissad risksiffra`() {
        val holdings = listOf(portfolioHolding("A", currentValue = 1000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = null))

        val result = PortfolioRiskCalc.compute(holdings, metadataByIsin)

        assertNull(result.weightedAverageRisk)
        assertEquals(1, result.excludedCount)
    }

    @Test
    fun `innehav utan kand kurs exkluderas`() {
        val holdings = listOf(portfolioHolding("A", currentValue = null))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 4))

        val result = PortfolioRiskCalc.compute(holdings, metadataByIsin)

        assertNull(result.weightedAverageRisk)
        assertEquals(1, result.excludedCount)
    }

    @Test
    fun `ett kant och ett okant innehav ger bara det kandas bidrag`() {
        val holdings = listOf(
            portfolioHolding("A", currentValue = 500.0),
            portfolioHolding("B", currentValue = 500.0, isin = null)
        )
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 3))

        val result = PortfolioRiskCalc.compute(holdings, metadataByIsin)

        assertEquals(3.0, result.weightedAverageRisk ?: -1.0, 1e-9)
        assertEquals(500.0, result.includedValueKr, 1e-9)
        assertEquals(1, result.excludedCount)
    }

    // --- Värde per risknivå (HEM-7, issue #71/#75/#238) ---
    // Mål mot faktisk per nivå räknas sedan #238 i SwitchEffectCalc (SwitchEffectCalcTest);
    // här ligger regressionen för nämnaren, som valueByLevel bär.

    @Test
    fun `valueByLevel tar bara det klassificerade vardet, inte hela portfoljen`() {
        // Regression (issue #75): Bucket.fraction divideras med includedValueKr, som också rymmer
        // innehav med okänd risknivå. Andelarna summerade då till mindre än 1 medan målet
        // summerar till exakt 1 — så varje nivå såg underviktad ut.
        val holdings = listOf(
            portfolioHolding("A", currentValue = 700.0),
            portfolioHolding("B", currentValue = 300.0)
        )
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 4), "SEB" to metadata("SEB", risk = null))
        val exposure = PortfolioExposureCalc.compute(holdings, metadataByIsin)

        // Förutsättningen: okänd risknivå ligger i okänd-hinken men *innanför* nämnaren.
        assertEquals(0.7, exposure.byRiskLevel.buckets.single().fraction, 1e-9)
        assertEquals(0.3, exposure.byRiskLevel.unknownFraction, 1e-9)

        val valueByLevel = PortfolioRiskCalc.valueByLevel(exposure.byRiskLevel)

        assertEquals(mapOf(4 to 700.0), valueByLevel)
        // 100 % av det klassificerade värdet ligger på målnivån — ingen avvikelse att åtgärda.
        val effect = SwitchEffectCalc.compute(valueByLevel, emptyMap(), mapOf(4 to 1.0), emptyList())!!
        assertEquals(1.0, effect.levels.single().actualFraction, 1e-9)
        assertEquals(SwitchEffectCalc.Direction.IN_LINE, effect.direction)
    }

    @Test
    fun `valueByLevel ar tom utan klassificerat varde`() {
        val exposure = PortfolioExposureCalc.compute(
            listOf(portfolioHolding("A", currentValue = 1000.0)),
            mapOf("SEA" to metadata("SEA", risk = null))
        )

        assertEquals(emptyMap<Int, Double>(), PortfolioRiskCalc.valueByLevel(exposure.byRiskLevel))
    }
}
