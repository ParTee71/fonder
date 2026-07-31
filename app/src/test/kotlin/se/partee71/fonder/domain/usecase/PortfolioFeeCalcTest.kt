package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.Holding
import java.time.LocalDate

class PortfolioFeeCalcTest {

    private val today = LocalDate.of(2026, 7, 31)

    private fun fund(fundId: String, isin: String? = "SE$fundId") = Fund(fundId = fundId, name = fundId, isin = isin)

    private fun holding(fundId: String, currentValue: Double?, isin: String? = "SE$fundId") =
        Holding(fund = fund(fundId, isin), netShares = 1.0, netInvested = 0.0, currentValue = currentValue)

    private fun metadata(
        isin: String,
        totalFee: Double?,
        cheapestAlternativeFee: Double? = null,
        comparisonResolvedAtEpochDay: Long? = null,
    ) = FundMetadata(
        isin = isin, name = isin, orderbookId = isin, totalFee = totalFee, managementFee = totalFee,
        category = null, fundType = null, companyName = null, risk = null, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
        cheapestAlternativeIsin = if (cheapestAlternativeFee != null) "SE_ALT" else null,
        cheapestAlternativeFee = cheapestAlternativeFee,
        comparisonResolvedAtEpochDay = comparisonResolvedAtEpochDay,
    )

    @Test
    fun `tom portfolj ger noll utan krasch`() {
        val result = PortfolioFeeCalc.compute(emptyList(), emptyMap(), today)

        assertEquals(0.0, result.totalAnnualFeeKr, 1e-9)
        assertTrue(result.byHolding.isEmpty())
        assertEquals(0, result.unknownFeeCount)
        assertEquals(0.0, result.totalAnnualSavingsKr, 1e-9)
        assertEquals(0, result.comparedCount)
        assertEquals(0, result.comparableCount)
    }

    @Test
    fun `handraknat fall, 300000 kr gange 0,73 procent ger 2190 kr per ar`() {
        val holdings = listOf(holding("A", currentValue = 300_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", totalFee = 0.73))

        val result = PortfolioFeeCalc.compute(holdings, metadataByIsin, today)

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

        val result = PortfolioFeeCalc.compute(holdings, metadataByIsin, today)

        // 100 000×0,5% + 200 000×1,0% = 500 + 2000 = 2500 kr.
        assertEquals(2500.0, result.totalAnnualFeeKr, 0.01)
        assertEquals(2, result.byHolding.size)
    }

    @Test
    fun `innehav utan isin exkluderas ur totalen och rakas som okand avgift`() {
        val holdings = listOf(holding("A", currentValue = 100_000.0, isin = null))

        val result = PortfolioFeeCalc.compute(holdings, emptyMap(), today)

        assertEquals(0.0, result.totalAnnualFeeKr, 1e-9)
        assertTrue(result.byHolding.isEmpty())
        assertEquals(1, result.unknownFeeCount)
    }

    @Test
    fun `innehav utan metadatatraff rakas som okand avgift`() {
        val holdings = listOf(holding("A", currentValue = 100_000.0))

        val result = PortfolioFeeCalc.compute(holdings, emptyMap(), today)

        assertEquals(0.0, result.totalAnnualFeeKr, 1e-9)
        assertEquals(1, result.unknownFeeCount)
    }

    @Test
    fun `innehav med metadata men okand totalFee rakas som okand avgift, aldrig noll`() {
        val holdings = listOf(holding("A", currentValue = 100_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", totalFee = null))

        val result = PortfolioFeeCalc.compute(holdings, metadataByIsin, today)

        assertEquals(0.0, result.totalAnnualFeeKr, 1e-9)
        assertEquals(1, result.unknownFeeCount)
    }

    @Test
    fun `innehav utan kand kurs hoppas over helt, rakas varken som kant eller okant`() {
        val holdings = listOf(holding("A", currentValue = null))

        val result = PortfolioFeeCalc.compute(holdings, emptyMap(), today)

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

        val result = PortfolioFeeCalc.compute(holdings, metadataByIsin, today)

        assertEquals(listOf("Stor", "Liten"), result.byHolding.map { it.fund.fundId })
    }

    // --- Samlad besparingspotential (HEM-6, issue #61) ---

    @Test
    fun `farsk jamforelse med billigare alternativ ger besparing per rad och i aggregatet`() {
        val holdings = listOf(holding("A", currentValue = 300_000.0))
        val metadataByIsin = mapOf(
            "SEA" to metadata("SEA", totalFee = 0.73, cheapestAlternativeFee = 0.21, comparisonResolvedAtEpochDay = today.toEpochDay()),
        )

        val result = PortfolioFeeCalc.compute(holdings, metadataByIsin, today)

        // (0,73 - 0,21) / 100 * 300 000 = 1 560 kr/år.
        assertEquals(1560.0, result.byHolding.single().annualSavingsKr ?: -1.0, 0.01)
        assertEquals(1560.0, result.totalAnnualSavingsKr, 0.01)
        assertEquals(1, result.comparedCount)
        assertEquals(1, result.comparableCount)
    }

    @Test
    fun `farsk jamforelse utan billigare alternativ ger ingen besparing men rakas som genomsokt`() {
        val holdings = listOf(holding("A", currentValue = 300_000.0))
        val metadataByIsin = mapOf(
            "SEA" to metadata("SEA", totalFee = 0.73, cheapestAlternativeFee = null, comparisonResolvedAtEpochDay = today.toEpochDay()),
        )

        val result = PortfolioFeeCalc.compute(holdings, metadataByIsin, today)

        assertNull(result.byHolding.single().annualSavingsKr)
        assertEquals(0.0, result.totalAnnualSavingsKr, 1e-9)
        assertEquals(1, result.comparedCount)
        // Skilt från "aldrig genomsökt" — jämförelsen gjordes, den hittade bara inget billigare.
        assertTrue(result.byHolding.single().wasCompared)
    }

    @Test
    fun `aldrig genomsokt innehav rakas inte som genomsokt och visar ingen besparing`() {
        val holdings = listOf(holding("A", currentValue = 300_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", totalFee = 0.73)) // comparisonResolvedAtEpochDay null

        val result = PortfolioFeeCalc.compute(holdings, metadataByIsin, today)

        assertNull(result.byHolding.single().annualSavingsKr)
        assertEquals(0, result.comparedCount)
        assertEquals(1, result.comparableCount)
        assertFalse(result.byHolding.single().wasCompared)
    }

    @Test
    fun `utgangen jamforelse behandlas som osokt, ingen besparing och rakas inte som genomsokt`() {
        val staleDay = today.minusDays(FundMetadataFreshness.COMPARISON_TTL_DAYS + 1).toEpochDay()
        val holdings = listOf(holding("A", currentValue = 300_000.0))
        val metadataByIsin = mapOf(
            "SEA" to metadata("SEA", totalFee = 0.73, cheapestAlternativeFee = 0.21, comparisonResolvedAtEpochDay = staleDay),
        )

        val result = PortfolioFeeCalc.compute(holdings, metadataByIsin, today)

        assertNull("Ett utgånget resultat ska aldrig visas som en aktuell rekommendation", result.byHolding.single().annualSavingsKr)
        assertEquals(0, result.comparedCount)
        assertFalse("Utgånget ska behandlas som aldrig genomsökt, inte som genomsökt-utan-träff", result.byHolding.single().wasCompared)
    }

    @Test
    fun `besparing summeras over flera farskt genomsokta innehav`() {
        val holdings = listOf(
            holding("A", currentValue = 300_000.0),
            holding("B", currentValue = 100_000.0),
        )
        val metadataByIsin = mapOf(
            "SEA" to metadata("SEA", totalFee = 0.73, cheapestAlternativeFee = 0.21, comparisonResolvedAtEpochDay = today.toEpochDay()),
            "SEB" to metadata("SEB", totalFee = 1.0, cheapestAlternativeFee = 0.5, comparisonResolvedAtEpochDay = today.toEpochDay()),
        )

        val result = PortfolioFeeCalc.compute(holdings, metadataByIsin, today)

        // A: 1 560 kr, B: 0,5%×100 000 = 500 kr. Summa 2 060 kr.
        assertEquals(2060.0, result.totalAnnualSavingsKr, 0.01)
        assertEquals(2, result.comparedCount)
        assertEquals(2, result.comparableCount)
    }
}
