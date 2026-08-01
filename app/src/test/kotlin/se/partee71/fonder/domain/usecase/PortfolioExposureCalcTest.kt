package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundTag
import se.partee71.fonder.domain.model.Holding

class PortfolioExposureCalcTest {

    private fun fund(fundId: String, isin: String? = "SE$fundId") = Fund(fundId = fundId, name = fundId, isin = isin)

    private fun holding(fundId: String, currentValue: Double?, isin: String? = "SE$fundId") =
        Holding(fund = fund(fundId, isin), netShares = 1.0, netInvested = 0.0, currentValue = currentValue)

    private fun metadata(
        isin: String,
        typeTitle: String? = "Aktiefond",
        regionTitle: String? = "Sverige",
        regionCategory: String = FundTag.CATEGORY_COMMON_REGION,
        indexFund: Boolean = false,
        fundType: String? = "EQUITY_FUND",
    ): FundMetadata {
        val tags = buildList {
            typeTitle?.let { add(FundTag(title = it, category = FundTag.CATEGORY_TYPE)) }
            regionTitle?.let { add(FundTag(title = it, category = regionCategory)) }
        }
        return FundMetadata(
            isin = isin, name = isin, orderbookId = isin, totalFee = 1.0, managementFee = 1.0,
            category = null, fundType = fundType, companyName = null, risk = null, indexFund = indexFund,
            startDateEpochDay = null, minimumBuy = null, tags = tags,
        )
    }

    @Test
    fun `tom portfolj ger tomma dimensioner utan krasch`() {
        val result = PortfolioExposureCalc.compute(emptyList(), emptyMap())

        assertEquals(0.0, result.includedValueKr, 1e-9)
        assertEquals(0, result.excludedCount)
        assertTrue(result.byType.buckets.isEmpty())
        assertTrue(result.byRegion.buckets.isEmpty())
        assertEquals(0.0, result.indexStatus.indexFraction, 1e-9)
        assertEquals(0.0, result.indexStatus.activeFraction, 1e-9)
    }

    @Test
    fun `innehav utan isin exkluderas helt och rakas i excludedCount`() {
        val holdings = listOf(holding("A", currentValue = 1000.0, isin = null))

        val result = PortfolioExposureCalc.compute(holdings, emptyMap())

        assertEquals(1, result.excludedCount)
        assertEquals(0.0, result.includedValueKr, 1e-9)
    }

    @Test
    fun `innehav utan metadatatraff exkluderas helt`() {
        val holdings = listOf(holding("A", currentValue = 1000.0))

        val result = PortfolioExposureCalc.compute(holdings, emptyMap())

        assertEquals(1, result.excludedCount)
        assertEquals(0.0, result.includedValueKr, 1e-9)
    }

    @Test
    fun `innehav utan kand kurs exkluderas helt`() {
        val holdings = listOf(holding("A", currentValue = null))
        val metadataByIsin = mapOf("SEA" to metadata("SEA"))

        val result = PortfolioExposureCalc.compute(holdings, metadataByIsin)

        assertEquals(1, result.excludedCount)
        assertEquals(0.0, result.includedValueKr, 1e-9)
    }

    @Test
    fun `kant innehav utan regiontagg hamnar i regionens okand-hink, inte i excludedCount`() {
        val holdings = listOf(holding("A", currentValue = 1000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", regionTitle = null))

        val result = PortfolioExposureCalc.compute(holdings, metadataByIsin)

        assertEquals(0, result.excludedCount)
        assertEquals(1000.0, result.includedValueKr, 1e-9)
        assertEquals(1000.0, result.byRegion.unknownValueKr, 1e-9)
        assertEquals(1, result.byRegion.unknownCount)
        assertEquals(1.0, result.byRegion.unknownFraction, 1e-9)
        assertTrue(result.byRegion.buckets.isEmpty())
        // Fondtyp-dimensionen är opåverkad — den saknade bara regiontaggen.
        assertEquals(1, result.byType.buckets.size)
    }

    @Test
    fun `innehav utan TYPE-tagg hamnar i fondtypens okand-hink utan krasch`() {
        val holdings = listOf(holding("A", currentValue = 1000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", typeTitle = null))

        val result = PortfolioExposureCalc.compute(holdings, metadataByIsin)

        assertEquals(0, result.excludedCount)
        assertEquals(1, result.byType.unknownCount)
        assertTrue(result.byType.buckets.isEmpty())
    }

    @Test
    fun `fondtyp grupperas pa TYPE-taggen, inte pa fundType-faltet`() {
        // Samma fundType-fält ("EQUITY_FUND") på båda, men olika TYPE-tagg — verifierat live
        // (2026-08-01) att källan själv grupperar så (EQUITY_FUND förekommer på både
        // "Aktiefond" och "Alternativa"). Grupperingen måste följa taggen, inte fältet.
        val holdings = listOf(
            holding("A", currentValue = 1000.0),
            holding("B", currentValue = 500.0),
        )
        val metadataByIsin = mapOf(
            "SEA" to metadata("SEA", typeTitle = "Aktiefond", fundType = "EQUITY_FUND"),
            "SEB" to metadata("SEB", typeTitle = "Alternativa", fundType = "EQUITY_FUND"),
        )

        val result = PortfolioExposureCalc.compute(holdings, metadataByIsin)

        assertEquals(2, result.byType.buckets.size)
        assertEquals(setOf("Aktiefond", "Alternativa"), result.byType.buckets.map { it.label }.toSet())
    }

    @Test
    fun `region slar ihop COMMON_REGION och OTHER_REGION till en dimension`() {
        val holdings = listOf(
            holding("A", currentValue = 1000.0),
            holding("B", currentValue = 1000.0),
        )
        val metadataByIsin = mapOf(
            "SEA" to metadata("SEA", regionTitle = "Sverige", regionCategory = FundTag.CATEGORY_COMMON_REGION),
            "SEB" to metadata("SEB", regionTitle = "Taiwan", regionCategory = FundTag.CATEGORY_OTHER_REGION),
        )

        val result = PortfolioExposureCalc.compute(holdings, metadataByIsin)

        assertEquals(2, result.byRegion.buckets.size)
        assertEquals(setOf("Sverige", "Taiwan"), result.byRegion.buckets.map { it.label }.toSet())
    }

    @Test
    fun `index-aktivt speglar indexFund oberoende av taggar`() {
        val holdings = listOf(
            holding("A", currentValue = 700.0),
            holding("B", currentValue = 300.0),
        )
        val metadataByIsin = mapOf(
            "SEA" to metadata("SEA", indexFund = true),
            "SEB" to metadata("SEB", indexFund = false),
        )

        val result = PortfolioExposureCalc.compute(holdings, metadataByIsin)

        assertEquals(700.0, result.indexStatus.indexValueKr, 1e-9)
        assertEquals(0.7, result.indexStatus.indexFraction, 1e-9)
        assertEquals(300.0, result.indexStatus.activeValueKr, 1e-9)
        assertEquals(0.3, result.indexStatus.activeFraction, 1e-9)
    }

    @Test
    fun `sortering fallande pa varde inom en dimension`() {
        val holdings = listOf(
            holding("Liten", currentValue = 100.0),
            holding("Stor", currentValue = 900.0),
        )
        val metadataByIsin = mapOf(
            "SELiten" to metadata("SELiten", typeTitle = "Räntefond"),
            "SEStor" to metadata("SEStor", typeTitle = "Aktiefond"),
        )

        val result = PortfolioExposureCalc.compute(holdings, metadataByIsin)

        assertEquals(listOf("Aktiefond", "Räntefond"), result.byType.buckets.map { it.label })
    }

    @Test
    fun `procenten summerar till 100 procent inklusive okand-hinken`() {
        val holdings = listOf(
            holding("A", currentValue = 600.0),
            holding("B", currentValue = 400.0),
        )
        val metadataByIsin = mapOf(
            "SEA" to metadata("SEA", regionTitle = "Sverige"),
            "SEB" to metadata("SEB", regionTitle = null),
        )

        val result = PortfolioExposureCalc.compute(holdings, metadataByIsin)

        val total = result.byRegion.buckets.sumOf { it.fraction } + result.byRegion.unknownFraction
        assertEquals(1.0, total, 1e-9)
        assertEquals(1000.0, result.byRegion.buckets.sumOf { it.valueKr } + result.byRegion.unknownValueKr, 1e-9)
    }

    @Test
    fun `handraknat exempel med tre innehav i olika kategorier`() {
        val holdings = listOf(
            holding("A", currentValue = 500.0),
            holding("B", currentValue = 300.0),
            holding("C", currentValue = 200.0),
        )
        val metadataByIsin = mapOf(
            "SEA" to metadata("SEA", typeTitle = "Aktiefond", regionTitle = "Sverige"),
            "SEB" to metadata("SEB", typeTitle = "Aktiefond", regionTitle = "USA"),
            "SEC" to metadata("SEC", typeTitle = "Räntefond", regionTitle = null),
        )

        val result = PortfolioExposureCalc.compute(holdings, metadataByIsin)

        assertEquals(1000.0, result.includedValueKr, 1e-9)
        val aktiefond = result.byType.buckets.single { it.label == "Aktiefond" }
        assertEquals(800.0, aktiefond.valueKr, 1e-9)
        assertEquals(0.8, aktiefond.fraction, 1e-9)
        val rantefond = result.byType.buckets.single { it.label == "Räntefond" }
        assertEquals(200.0, rantefond.valueKr, 1e-9)
        assertEquals(0.2, rantefond.fraction, 1e-9)
        assertEquals(200.0, result.byRegion.unknownValueKr, 1e-9)
        assertEquals(1, result.byRegion.unknownCount)
    }
}
