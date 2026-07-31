package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundTag

/**
 * Speglar den verkliga jämförelsen från issue #59: Handelsbanken Sverige Index Criteria
 * (0,73 %) mot Länsförsäkringar Sverige Index (0,21 %), identiska taggar
 * (`TYPE:Aktiefond`, `COMMON_REGION:Sverige`, `INDEX:Index`) — och de verifierade
 * felfallen som föll bort vid en strikt taggmängdskontroll (Avanza Sweden SEK A m.fl.,
 * som skilde sig enbart på `INDEX`).
 */
class FeeComparisonCalcTest {

    private val sverigeIndexTags = listOf(
        FundTag("Aktiefond", "TYPE"),
        FundTag("Sverige", "COMMON_REGION"),
        FundTag("Index", "INDEX"),
    )

    private fun fund(
        isin: String,
        name: String = isin,
        totalFee: Double?,
        indexFund: Boolean = true,
        tags: List<FundTag> = sverigeIndexTags,
    ) = FundMetadata(
        isin = isin,
        name = name,
        orderbookId = isin,
        totalFee = totalFee,
        managementFee = totalFee,
        category = "Sverige",
        fundType = "EQUITY_FUND",
        companyName = null,
        risk = null,
        indexFund = indexFund,
        startDateEpochDay = null,
        minimumBuy = null,
        tags = tags,
    )

    private val held = fund(isin = "SE0001466368", name = "Handelsbanken Sverige Index Criteria", totalFee = 0.73)

    @Test
    fun `candidateQuery grupperar hallda taggar per dimension och satter maxTotalFee till innehavets avgift`() {
        val query = FeeComparisonCalc.candidateQuery(held)

        assertEquals(listOf("Aktiefond"), query.fundType)
        assertEquals(listOf("Sverige"), query.region)
        assertTrue(query.otherRegion.isEmpty())
        assertTrue(query.alignment.isEmpty())
        assertEquals(0.73, query.maxTotalFee ?: -1.0, 1e-9)
        assertEquals("totalFee", query.sortField)
    }

    @Test
    fun `rank foreslar billigare fond med identisk taggmangd och besparingen i kronor`() {
        val lansforsakringar = fund(isin = "SE0000581434", name = "Länsförsäkringar Sverige Index", totalFee = 0.21)

        val result = FeeComparisonCalc.rank(held, listOf(lansforsakringar), holdingValue = 300_000.0)

        assertEquals(1, result.size)
        assertEquals("SE0000581434", result.first().candidate.isin)
        assertEquals(0.21, result.first().candidateFeePercent, 1e-9)
        // (0,73 - 0,21) / 100 * 300 000 = 1 560 kr/år.
        assertEquals(1560.0, result.first().annualSavingsKr, 0.01)
    }

    @Test
    fun `rank utesluter en kandidat som skiljer sig enbart pa indexstatus`() {
        // Verkligt fall (issue #59): Avanza Sweden SEK A hade samma TYPE/COMMON_REGION men
        // saknade INDEX-taggen (indexFund=false) — en aktivt förvaltad fond som inte får
        // föreslås som "samma exponering, billigare".
        val aktivtForvaltad = fund(
            isin = "SE_AKTIV",
            totalFee = 0.17,
            indexFund = false,
            tags = listOf(FundTag("Aktiefond", "TYPE"), FundTag("Sverige", "COMMON_REGION")),
        )

        val result = FeeComparisonCalc.rank(held, listOf(aktivtForvaltad), holdingValue = 300_000.0)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `rank utesluter en kandidat med extra tagg utover hallda fondens`() {
        // Samma bastaggar men en extra ALIGNMENT — inte identisk exponering.
        val smabolag = fund(
            isin = "SE_SMA",
            totalFee = 0.20,
            tags = sverigeIndexTags + FundTag("Småbolag", "ALIGNMENT"),
        )

        assertTrue(FeeComparisonCalc.rank(held, listOf(smabolag), holdingValue = 300_000.0).isEmpty())
    }

    @Test
    fun `rank utesluter kandidat med annan region`() {
        val global = fund(
            isin = "SE_GLOBAL",
            totalFee = 0.10,
            tags = listOf(FundTag("Aktiefond", "TYPE"), FundTag("Global", "COMMON_REGION"), FundTag("Index", "INDEX")),
        )

        assertTrue(FeeComparisonCalc.rank(held, listOf(global), holdingValue = 300_000.0).isEmpty())
    }

    @Test
    fun `rank utesluter kandidat med identisk avgift, maxTotalFee ar inklusive`() {
        val sammaAvgift = fund(isin = "SE_SAMMA", totalFee = 0.73)

        assertTrue(FeeComparisonCalc.rank(held, listOf(sammaAvgift), holdingValue = 300_000.0).isEmpty())
    }

    @Test
    fun `rank utesluter kandidat med hogre avgift`() {
        val dyrare = fund(isin = "SE_DYR", totalFee = 0.90)

        assertTrue(FeeComparisonCalc.rank(held, listOf(dyrare), holdingValue = 300_000.0).isEmpty())
    }

    @Test
    fun `rank foreslar aldrig innehavet sjalvt aven om det rakar finnas i kandidatlistan`() {
        val resultMedSigSjalv = FeeComparisonCalc.rank(held, listOf(held), holdingValue = 300_000.0)

        assertTrue(resultMedSigSjalv.isEmpty())
    }

    @Test
    fun `rank ger tom lista om hallda fondens avgift ar okand`() {
        val okandAvgift = held.copy(totalFee = null)
        val billigare = fund(isin = "SE_BILLIG", totalFee = 0.10)

        assertTrue(FeeComparisonCalc.rank(okandAvgift, listOf(billigare), holdingValue = 300_000.0).isEmpty())
    }

    @Test
    fun `rank hoppar over en kandidat med okand avgift utan att gissa`() {
        val okandKandidat = fund(isin = "SE_OKAND", totalFee = null)
        val kandBillig = fund(isin = "SE_BILLIG", totalFee = 0.10)

        val result = FeeComparisonCalc.rank(held, listOf(okandKandidat, kandBillig), holdingValue = 300_000.0)

        assertEquals(listOf("SE_BILLIG"), result.map { it.candidate.isin })
    }

    @Test
    fun `rank rankar flera kvalificerade kandidater efter storst besparing forst`() {
        val litenBesparing = fund(isin = "SE_LITEN", totalFee = 0.60)
        val storBesparing = fund(isin = "SE_STOR", totalFee = 0.05)

        val result = FeeComparisonCalc.rank(held, listOf(litenBesparing, storBesparing), holdingValue = 300_000.0)

        assertEquals(listOf("SE_STOR", "SE_LITEN"), result.map { it.candidate.isin })
    }

    @Test
    fun `annualFeeKr omvandlar en avgift i procentform till kronor per ar, delad primitiv med PortfolioFeeCalc (issue 60)`() {
        // 300 000 kr × 0,73 % = 2 190 kr/år.
        assertEquals(2190.0, FeeComparisonCalc.annualFeeKr(feePercent = 0.73, holdingValue = 300_000.0), 0.01)
        assertEquals(0.0, FeeComparisonCalc.annualFeeKr(feePercent = 0.73, holdingValue = 0.0), 1e-9)
    }
}
