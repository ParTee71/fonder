package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.Holding

class PortfolioRiskCalcTest {

    private fun fund(fundId: String, isin: String? = "SE$fundId") = Fund(fundId = fundId, name = fundId, isin = isin)

    private fun holding(fundId: String, currentValue: Double?, isin: String? = "SE$fundId") =
        Holding(fund = fund(fundId, isin), netShares = 1.0, netInvested = 0.0, currentValue = currentValue)

    private fun metadata(isin: String, risk: Int?) = FundMetadata(
        isin = isin, name = isin, orderbookId = isin, totalFee = null, managementFee = null,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
    )

    @Test
    fun `tom portfolj ger null och inga exkluderade`() {
        val result = PortfolioRiskCalc.compute(emptyList(), emptyMap())

        assertNull(result.weightedAverageRisk)
        assertEquals(0.0, result.includedValueKr, 1e-9)
        assertEquals(0, result.excludedCount)
    }

    @Test
    fun `ett enda innehav ger exakt dess egen riskniva`() {
        val holdings = listOf(holding("A", currentValue = 1000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 5))

        val result = PortfolioRiskCalc.compute(holdings, metadataByIsin)

        assertEquals(5.0, result.weightedAverageRisk ?: -1.0, 1e-9)
        assertEquals(1000.0, result.includedValueKr, 1e-9)
        assertEquals(0, result.excludedCount)
    }

    @Test
    fun `handraknat vardeviktat snitt over tva innehav`() {
        val holdings = listOf(
            holding("A", currentValue = 300.0),
            holding("B", currentValue = 700.0),
        )
        val metadataByIsin = mapOf(
            "SEA" to metadata("SEA", risk = 2),
            "SEB" to metadata("SEB", risk = 6),
        )

        val result = PortfolioRiskCalc.compute(holdings, metadataByIsin)

        // (300*2 + 700*6) / 1000 = (600 + 4200) / 1000 = 4,8.
        assertEquals(4.8, result.weightedAverageRisk ?: -1.0, 1e-9)
    }

    @Test
    fun `innehav utan isin exkluderas och rakas separat`() {
        val holdings = listOf(holding("A", currentValue = 1000.0, isin = null))

        val result = PortfolioRiskCalc.compute(holdings, emptyMap())

        assertNull(result.weightedAverageRisk)
        assertEquals(1, result.excludedCount)
    }

    @Test
    fun `innehav utan metadatatraff exkluderas och rakas separat`() {
        val holdings = listOf(holding("A", currentValue = 1000.0))

        val result = PortfolioRiskCalc.compute(holdings, emptyMap())

        assertNull(result.weightedAverageRisk)
        assertEquals(1, result.excludedCount)
    }

    @Test
    fun `innehav med metadata men okand risk exkluderas, aldrig en gissad risksiffra`() {
        val holdings = listOf(holding("A", currentValue = 1000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = null))

        val result = PortfolioRiskCalc.compute(holdings, metadataByIsin)

        assertNull(result.weightedAverageRisk)
        assertEquals(1, result.excludedCount)
    }

    @Test
    fun `innehav utan kand kurs exkluderas`() {
        val holdings = listOf(holding("A", currentValue = null))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 4))

        val result = PortfolioRiskCalc.compute(holdings, metadataByIsin)

        assertNull(result.weightedAverageRisk)
        assertEquals(1, result.excludedCount)
    }

    @Test
    fun `ett kant och ett okant innehav ger bara det kandas bidrag`() {
        val holdings = listOf(
            holding("A", currentValue = 500.0),
            holding("B", currentValue = 500.0, isin = null),
        )
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 3))

        val result = PortfolioRiskCalc.compute(holdings, metadataByIsin)

        assertEquals(3.0, result.weightedAverageRisk ?: -1.0, 1e-9)
        assertEquals(500.0, result.includedValueKr, 1e-9)
        assertEquals(1, result.excludedCount)
    }
}
