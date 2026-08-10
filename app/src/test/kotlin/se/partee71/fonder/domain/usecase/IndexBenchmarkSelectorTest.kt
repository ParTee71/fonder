package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundTag

/** [IndexBenchmarkSelector] — vilken fond Hems indexjämförelse mäts mot (HEM-10). */
class IndexBenchmarkSelectorTest {

    private fun fond(
        isin: String,
        name: String = isin,
        totalFee: Double? = 0.2,
        indexFund: Boolean = true,
        type: String = IndexBenchmarkSelector.TAG_TYPE_EQUITY,
        region: String = IndexBenchmarkSelector.TAG_REGION_GLOBAL,
        startDateEpochDay: Long? = 15_000L,
    ) = FundMetadata(
        isin = isin,
        name = name,
        orderbookId = isin,
        totalFee = totalFee,
        managementFee = totalFee,
        category = null,
        fundType = type,
        companyName = null,
        risk = 5,
        indexFund = indexFund,
        startDateEpochDay = startDateEpochDay,
        minimumBuy = null,
        tags = listOf(
            FundTag(title = type, category = FundTag.CATEGORY_TYPE),
            FundTag(title = region, category = FundTag.CATEGORY_COMMON_REGION),
        ),
    )

    @Test
    fun `valjer den billigaste globala indexfonden`() {
        val kandidater = listOf(
            fond("SE0001", totalFee = 0.40),
            fond("SE0002", totalFee = 0.12),
            fond("SE0003", totalFee = 0.25),
        )

        assertEquals("SE0002", IndexBenchmarkSelector.select(kandidater)?.isin)
    }

    @Test
    fun `aktivt forvaltade fonder duger inte som index`() {
        val kandidater = listOf(
            fond("SE0001", totalFee = 0.05, indexFund = false),
            fond("SE0002", totalFee = 0.30),
        )

        assertEquals("SE0002", IndexBenchmarkSelector.select(kandidater)?.isin)
    }

    @Test
    fun `regionala och icke-aktiefonder duger inte som index`() {
        // Källan ignorerar tyst ett filter den inte känner igen ("fail open", TP-21) — därför
        // filtreras det om lokalt, annars kunde en räntefond eller en Sverigefond bli "index".
        val kandidater = listOf(
            fond("SE0001", totalFee = 0.05, region = "Sverige"),
            fond("SE0002", totalFee = 0.06, type = "Räntefond"),
            fond("SE0003", totalFee = 0.30),
        )

        assertEquals("SE0003", IndexBenchmarkSelector.select(kandidater)?.isin)
    }

    @Test
    fun `fond utan kand avgift gar inte att rangordna och valjs aldrig`() {
        val kandidater = listOf(fond("SE0001", totalFee = null), fond("SE0002", totalFee = 0.50))

        assertEquals("SE0002", IndexBenchmarkSelector.select(kandidater)?.isin)
    }

    @Test
    fun `urvalet ar deterministiskt oavsett kallans ordning`() {
        // Samma avgift och samma startdatum: ISIN bryter lika. En referensfond som byter
        // identitet mellan körningar hade ritat om jämförelsekurvan utan att något hänt.
        val kandidater = listOf(fond("SE0009"), fond("SE0002"), fond("SE0005"))

        assertEquals("SE0002", IndexBenchmarkSelector.select(kandidater)?.isin)
        assertEquals("SE0002", IndexBenchmarkSelector.select(kandidater.reversed())?.isin)
        assertEquals("SE0002", IndexBenchmarkSelector.select(kandidater.shuffled())?.isin)
    }

    @Test
    fun `vid samma avgift vinner den med langst historik`() {
        val kandidater = listOf(
            fond("SE0001", startDateEpochDay = 16_000L),
            fond("SE0002", startDateEpochDay = 12_000L),
            fond("SE0003", startDateEpochDay = null),
        )

        assertEquals("SE0002", IndexBenchmarkSelector.select(kandidater)?.isin)
    }

    @Test
    fun `tom eller otillracklig katalog ger ingen referensfond`() {
        assertNull(IndexBenchmarkSelector.select(emptyList()))
        assertNull(IndexBenchmarkSelector.select(listOf(fond("SE0001", indexFund = false))))
    }

    @Test
    fun `fragan filtrerar pa global aktiefond och sorterar pa avgift`() {
        // Källans sida är låst till 20 träffar (TP-21) — utan avgiftssorteringen kan den
        // billigaste globala indexfonden ligga utanför sidan och aldrig ens ses.
        assertEquals(listOf(IndexBenchmarkSelector.TAG_TYPE_EQUITY), IndexBenchmarkSelector.QUERY.fundType)
        assertEquals(listOf(IndexBenchmarkSelector.TAG_REGION_GLOBAL), IndexBenchmarkSelector.QUERY.region)
        assertEquals(FundScreenFilter.SORT_FIELD_TOTAL_FEE, IndexBenchmarkSelector.QUERY.sortField)
    }
}
