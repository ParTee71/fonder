package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundTag

/** [IndexBenchmarkSelector] — vilken referens Hems indexjämförelse mäts mot (HEM-10). */
class IndexBenchmarkSelectorTest {

    private fun fond(
        isin: String,
        totalFee: Double? = 0.2,
        indexFund: Boolean = true,
        type: String = IndexBenchmarkSelector.TAG_TYPE_EQUITY,
        region: String? = IndexBenchmarkSelector.TAG_REGION_GLOBAL,
        startDateEpochDay: Long? = 15_000L,
    ) = FundMetadata(
        isin = isin,
        name = "Fond $isin",
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
        tags = listOfNotNull(
            FundTag(title = type, category = FundTag.CATEGORY_TYPE),
            region?.let { FundTag(title = it, category = FundTag.CATEGORY_COMMON_REGION) },
        ),
    )

    private fun rantefond(isin: String, totalFee: Double = 0.1) =
        fond(isin, totalFee = totalFee, type = IndexBenchmarkSelector.TAG_TYPE_BOND, region = null)

    private fun bucket(label: String, valueKr: Double) =
        PortfolioExposureCalc.Bucket(label, valueKr, fraction = 0.0)

    private fun dimension(buckets: List<PortfolioExposureCalc.Bucket>, unknownValueKr: Double = 0.0) =
        PortfolioExposureCalc.Dimension(
            buckets = buckets,
            unknownValueKr = unknownValueKr,
            unknownFraction = 0.0,
            unknownCount = if (unknownValueKr > 0.0) 1 else 0,
        )

    // --- Aktieandelen ur exponeringen ---

    @Test
    fun `aktieandelen raknas over klassificerat varde`() {
        // 50 % aktier, 25 % räntor, 25 % blandfond → aktieandel 2/3, inte 1/2. Blandfonden
        // räknas varken som det ena eller det andra; den syns som oklassificerad i stället.
        val byType = dimension(
            listOf(
                bucket(IndexBenchmarkSelector.TAG_TYPE_EQUITY, 500.0),
                bucket(IndexBenchmarkSelector.TAG_TYPE_BOND, 250.0),
                bucket("Blandfond", 250.0),
            ),
        )

        val split = IndexBenchmarkSelector.exposureSplit(byType)

        assertEquals(2.0 / 3.0, split.equityShare, 1e-9)
        assertEquals(0.25, split.unclassifiedFraction, 1e-9)
    }

    @Test
    fun `okand fondtyp raknas som oklassificerat, inte som aktier`() {
        val byType = dimension(listOf(bucket(IndexBenchmarkSelector.TAG_TYPE_EQUITY, 800.0)), unknownValueKr = 200.0)

        val split = IndexBenchmarkSelector.exposureSplit(byType)

        assertEquals(1.0, split.equityShare, 1e-9)
        assertEquals(0.20, split.unclassifiedFraction, 1e-9)
    }

    @Test
    fun `en ren aktieportfolj ger aktieandel 1 och inget oklassificerat`() {
        val split = IndexBenchmarkSelector.exposureSplit(
            dimension(listOf(bucket(IndexBenchmarkSelector.TAG_TYPE_EQUITY, 1_000.0))),
        )

        assertEquals(1.0, split.equityShare, 1e-9)
        assertEquals(0.0, split.unclassifiedFraction, 1e-9)
    }

    @Test
    fun `utan klassificerat varde blir det breda aktier, med hela portfoljen markerad`() {
        val split = IndexBenchmarkSelector.exposureSplit(dimension(listOf(bucket("Blandfond", 1_000.0))))

        assertEquals(1.0, split.equityShare, 1e-9)
        assertEquals(1.0, split.unclassifiedFraction, 1e-9)
    }

    @Test
    fun `tom exponering kraschar inte`() {
        val split = IndexBenchmarkSelector.exposureSplit(PortfolioExposureCalc.Dimension.EMPTY)

        assertEquals(1.0, split.equityShare, 1e-9)
        assertEquals(1.0, split.unclassifiedFraction, 1e-9)
    }

    // --- Urvalet ---

    @Test
    fun `full aktieandel ger en enda komponent — samma beteende som fore blandningen`() {
        val benchmark = IndexBenchmarkSelector.select(
            equityCandidates = listOf(fond("SE_DYR", 0.40), fond("SE_BILLIG", 0.10)),
            bondCandidates = listOf(rantefond("SE_RANTA")),
            equityShare = 1.0,
        )!!

        assertEquals(1, benchmark.components.size)
        assertEquals("SE_BILLIG", benchmark.components.single().metadata.isin)
        assertEquals(1.0, benchmark.components.single().weight, 1e-9)
        assertFalse(benchmark.missingBondComponent)
    }

    @Test
    fun `delad portfolj ger en aktie- och en rantekomponent med ratt vikter`() {
        val benchmark = IndexBenchmarkSelector.select(
            equityCandidates = listOf(fond("SE_AKTIE", 0.15)),
            bondCandidates = listOf(rantefond("SE_RANTA_DYR", 0.30), rantefond("SE_RANTA", 0.05)),
            equityShare = 0.7,
        )!!

        assertEquals(listOf("SE_AKTIE", "SE_RANTA"), benchmark.components.map { it.metadata.isin })
        assertEquals(0.7, benchmark.components[0].weight, 1e-9)
        assertEquals(0.3, benchmark.components[1].weight, 1e-9)
    }

    @Test
    fun `vikterna summerar alltid till ett`() {
        val benchmark = IndexBenchmarkSelector.select(
            equityCandidates = listOf(fond("SE_AKTIE")),
            bondCandidates = listOf(rantefond("SE_RANTA")),
            equityShare = 0.37,
        )!!

        assertEquals(1.0, benchmark.components.sumOf { it.weight }, 1e-9)
    }

    @Test
    fun `en forsumbar del tas bort i stallet for att kosta en egen backfill`() {
        // 3 % räntor flyttar inte kurvan mätbart men kostar ett fondval och en full
        // historikhämtning vid varje skanning.
        val benchmark = IndexBenchmarkSelector.select(
            equityCandidates = listOf(fond("SE_AKTIE")),
            bondCandidates = listOf(rantefond("SE_RANTA")),
            equityShare = 0.97,
        )!!

        assertEquals(listOf("SE_AKTIE"), benchmark.components.map { it.metadata.isin })
        assertEquals(1.0, benchmark.components.single().weight, 1e-9)
    }

    @Test
    fun `saknad rantekandidat lagger vikten pa aktiedelen och markeras`() {
        val benchmark = IndexBenchmarkSelector.select(
            equityCandidates = listOf(fond("SE_AKTIE")),
            bondCandidates = emptyList(),
            equityShare = 0.6,
        )!!

        assertEquals(listOf("SE_AKTIE"), benchmark.components.map { it.metadata.isin })
        assertEquals(1.0, benchmark.components.single().weight, 1e-9)
        assertTrue("vyn ska kunna säga att blandningen inte blev den avsedda", benchmark.missingBondComponent)
    }

    @Test
    fun `en ren ranteportfolj ger enbart rantekomponenten`() {
        val benchmark = IndexBenchmarkSelector.select(
            equityCandidates = listOf(fond("SE_AKTIE")),
            bondCandidates = listOf(rantefond("SE_RANTA")),
            equityShare = 0.0,
        )!!

        assertEquals(listOf("SE_RANTA"), benchmark.components.map { it.metadata.isin })
        assertEquals(1.0, benchmark.components.single().weight, 1e-9)
    }

    @Test
    fun `aktivt forvaltade, regionala och icke-aktiefonder duger inte som aktiereferens`() {
        // Källan ignorerar tyst ett filter den inte känner igen ("fail open", TP-21) — därför
        // filtreras det om lokalt, annars kunde en räntefond eller en Sverigefond bli "index".
        val benchmark = IndexBenchmarkSelector.select(
            equityCandidates = listOf(
                fond("SE_AKTIV", 0.05, indexFund = false),
                fond("SE_SVERIGE", 0.06, region = "Sverige"),
                fond("SE_UTAN_AVGIFT", totalFee = null),
                fond("SE_OK", 0.30),
            ),
            bondCandidates = emptyList(),
            equityShare = 1.0,
        )!!

        assertEquals("SE_OK", benchmark.components.single().metadata.isin)
    }

    @Test
    fun `urvalet ar deterministiskt oavsett kallans ordning`() {
        // Samma avgift och samma startdatum: ISIN bryter lika. En referens som byter identitet
        // mellan körningar hade ritat om jämförelsekurvan utan att något hänt.
        val kandidater = listOf(fond("SE0009"), fond("SE0002"), fond("SE0005"))

        listOf(kandidater, kandidater.reversed(), kandidater.shuffled()).forEach { ordning ->
            val benchmark = IndexBenchmarkSelector.select(ordning, emptyList(), equityShare = 1.0)!!
            assertEquals("SE0002", benchmark.components.single().metadata.isin)
        }
    }

    @Test
    fun `vid samma avgift vinner den med langst historik`() {
        val benchmark = IndexBenchmarkSelector.select(
            equityCandidates = listOf(
                fond("SE0001", startDateEpochDay = 16_000L),
                fond("SE0002", startDateEpochDay = 12_000L),
                fond("SE0003", startDateEpochDay = null),
            ),
            bondCandidates = emptyList(),
            equityShare = 1.0,
        )!!

        assertEquals("SE0002", benchmark.components.single().metadata.isin)
    }

    @Test
    fun `ingen duglig kandidat alls ger ingen referens`() {
        assertNull(IndexBenchmarkSelector.select(emptyList(), emptyList(), equityShare = 1.0))
        assertNull(IndexBenchmarkSelector.select(listOf(fond("SE0001", indexFund = false)), emptyList(), equityShare = 1.0))
    }

    @Test
    fun `fragorna filtrerar pa ratt fondtyp och sorterar pa avgift`() {
        // Källans sida är låst till 20 träffar (TP-21) — utan avgiftssorteringen kan den
        // billigaste indexfonden ligga utanför sidan och aldrig ens ses.
        assertEquals(listOf(IndexBenchmarkSelector.TAG_TYPE_EQUITY), IndexBenchmarkSelector.EQUITY_QUERY.fundType)
        assertEquals(listOf(IndexBenchmarkSelector.TAG_REGION_GLOBAL), IndexBenchmarkSelector.EQUITY_QUERY.region)
        assertEquals(FundScreenFilter.SORT_FIELD_TOTAL_FEE, IndexBenchmarkSelector.EQUITY_QUERY.sortField)

        assertEquals(listOf(IndexBenchmarkSelector.TAG_TYPE_BOND), IndexBenchmarkSelector.BOND_QUERY.fundType)
        // Räntefonder är nästan alltid knutna till en valuta eller marknad — ett globalt filter
        // hade tömt träfflistan i stället för att förfina den.
        assertTrue(IndexBenchmarkSelector.BOND_QUERY.region.isEmpty())
        assertEquals(FundScreenFilter.SORT_FIELD_TOTAL_FEE, IndexBenchmarkSelector.BOND_QUERY.sortField)
    }
}
