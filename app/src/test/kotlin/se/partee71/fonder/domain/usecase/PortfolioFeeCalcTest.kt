package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.Holding

class PortfolioFeeCalcTest {

    private fun fund(fundId: String, isin: String? = "SE$fundId") = Fund(fundId = fundId, name = fundId, isin = isin)

    private fun holding(fundId: String, currentValue: Double?, isin: String? = "SE$fundId") =
        Holding(fund = fund(fundId, isin), netShares = 1.0, netInvested = 0.0, currentValue = currentValue)

    private fun metadata(isin: String, totalFee: Double?) = FundMetadata(
        isin = isin, name = isin, orderbookId = isin, totalFee = totalFee, managementFee = totalFee,
        category = null, fundType = null, companyName = null, risk = null, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
    )

    @Test
    fun `tom portfolj ger noll utan krasch`() {
        val result = PortfolioFeeCalc.compute(emptyList(), emptyMap())

        assertEquals(0.0, result.totalAnnualFeeKr, 1e-9)
        assertTrue(result.byHolding.isEmpty())
        assertEquals(0, result.unknownFeeCount)
    }

    @Test
    fun `handraknat fall, 300000 kr gange 0,73 procent ger 2190 kr per ar`() {
        val holdings = listOf(holding("A", currentValue = 300_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", totalFee = 0.73))

        val result = PortfolioFeeCalc.compute(holdings, metadataByIsin)

        assertEquals(2190.0, result.totalAnnualFeeKr, 0.01)
        assertEquals(0, result.unknownFeeCount)
    }

    @Test
    fun `summerar over flera innehav`() {
        val holdings = listOf(
            holding("A", currentValue = 100_000.0),
            holding("B", currentValue = 200_000.0),
        )
        val metadataByIsin = mapOf(
            "SEA" to metadata("SEA", totalFee = 0.5),
            "SEB" to metadata("SEB", totalFee = 1.0),
        )

        val result = PortfolioFeeCalc.compute(holdings, metadataByIsin)

        // 100 000×0,5% + 200 000×1,0% = 500 + 2000 = 2500 kr.
        assertEquals(2500.0, result.totalAnnualFeeKr, 0.01)
        assertEquals(2, result.byHolding.size)
    }

    @Test
    fun `innehav utan isin exkluderas ur totalen och rakas som okand avgift`() {
        val holdings = listOf(holding("A", currentValue = 100_000.0, isin = null))

        val result = PortfolioFeeCalc.compute(holdings, emptyMap())

        assertEquals(0.0, result.totalAnnualFeeKr, 1e-9)
        assertTrue(result.byHolding.isEmpty())
        assertEquals(1, result.unknownFeeCount)
    }

    @Test
    fun `innehav utan metadatatraff rakas som okand avgift`() {
        val holdings = listOf(holding("A", currentValue = 100_000.0))

        val result = PortfolioFeeCalc.compute(holdings, emptyMap())

        assertEquals(0.0, result.totalAnnualFeeKr, 1e-9)
        assertEquals(1, result.unknownFeeCount)
    }

    @Test
    fun `innehav med metadata men okand totalFee rakas som okand avgift, aldrig noll`() {
        val holdings = listOf(holding("A", currentValue = 100_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", totalFee = null))

        val result = PortfolioFeeCalc.compute(holdings, metadataByIsin)

        assertEquals(0.0, result.totalAnnualFeeKr, 1e-9)
        assertEquals(1, result.unknownFeeCount)
    }

    @Test
    fun `innehav utan kand kurs hoppas over helt, rakas varken som kant eller okant`() {
        val holdings = listOf(holding("A", currentValue = null))

        val result = PortfolioFeeCalc.compute(holdings, emptyMap())

        assertEquals(0.0, result.totalAnnualFeeKr, 1e-9)
        assertTrue(result.byHolding.isEmpty())
        // Kurs saknas har redan sin egen markering (POR-3) — blandas inte ihop med "okänd avgift".
        assertEquals(0, result.unknownFeeCount)
    }

    @Test
    fun `nedbrytning sorterad pa storst avgift forst`() {
        val holdings = listOf(
            holding("Liten", currentValue = 10_000.0),
            holding("Stor", currentValue = 500_000.0),
        )
        val metadataByIsin = mapOf(
            "SELiten" to metadata("SELiten", totalFee = 1.0),
            "SEStor" to metadata("SEStor", totalFee = 1.0),
        )

        val result = PortfolioFeeCalc.compute(holdings, metadataByIsin)

        assertEquals(listOf("Stor", "Liten"), result.byHolding.map { it.fund.fundId })
    }
}
