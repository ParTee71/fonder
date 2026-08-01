package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundTag
import se.partee71.fonder.domain.model.Holding

/**
 * Portföljens exponeringskarta (POR-9, issue #66) — andel av portföljens värde per
 * **fondtyp**, **region** och **index vs. aktivt förvaltat**, viktat på innehavens aktuella
 * värde. Ren inventering, ingen köp- eller rebalanseringsrekommendation. Samma anda som
 * [PortfolioFeeCalc]: tar innehav-med-värde + fondmetadata (TP-21), returnerar ett rent,
 * testbart resultat.
 *
 * Fondtyp läses ur källans **`TYPE`-tagg** ([FundTag.CATEGORY_TYPE]), inte ur
 * [FundMetadata.fundType] — det fältet är en engelsk transportkod (`EQUITY_FUND` m.fl.) vars
 * gruppering dessutom skiljer sig från taggens (samma `fundType` kan bära två olika
 * `TYPE`-titlar, verifierat 999/999-stickprov 2026-08-01). Region slår ihop
 * [FundTag.CATEGORY_COMMON_REGION] och [FundTag.CATEGORY_OTHER_REGION] — verifierat att de
 * aldrig förekommer samtidigt och aldrig fler än en per fond, så ihopslagningen dubbelräknar
 * aldrig värde. `MISC`/`INTEREST` tas medvetet inte med: flera fonder bär mer än en tagg i de
 * kategorierna (10,5 % respektive 2,9 % i stickprovet), vilket hade dubbelräknat värde.
 * `INDUSTRY`/`ALIGNMENT` tas inte heller med — täcker bara 14 % respektive 39 % av utbudet,
 * mest en okänd-hink. Index/aktivt läses ur [FundMetadata.indexFund] (ett booleskt fält,
 * alltid satt när metadata finns) — inte ur `INDEX`-taggen, som saknas på fonder utan den
 * egenskapen och alltså inte är en fullständig källa.
 */
object PortfolioExposureCalc {

    /** En kategori inom en dimension, t.ex. "Sverige" eller "Aktiefond". [fraction] är [valueKr] av [Result.includedValueKr]. */
    data class Bucket(val label: String, val valueKr: Double, val fraction: Double)

    /**
     * Fondtyp/region-nedbrytningen: [buckets] är de kategoriserade hinkarna (fallande på
     * värde), [unknownValueKr]/[unknownCount] är innehav som **är** medräknade
     * ([Result.includedValueKr]) men saknar en tagg i just den här dimensionen — en egen,
     * alltid separat hink, aldrig blandad in bland [buckets] även om den råkar vara liten.
     * Etiketten för okänd-hinken sätts av UI:t (t.ex. "Okänd region"), inte här — samma
     * princip som att domänlagret aldrig äger visningstext.
     */
    data class Dimension(val buckets: List<Bucket>, val unknownValueKr: Double, val unknownFraction: Double, val unknownCount: Int)

    /**
     * Index/aktivt är alltid känt för ett innehav som har metadata över huvud taget
     * ([FundMetadata.indexFund] är non-null) — ingen okänd-hink behövs eller kan uppstå.
     */
    data class IndexStatusSplit(val indexValueKr: Double, val indexFraction: Double, val activeValueKr: Double, val activeFraction: Double)

    data class Result(
        val byType: Dimension,
        val byRegion: Dimension,
        val indexStatus: IndexStatusSplit,
        /** Summan av alla medräknade innehavs värde — nämnaren för varje [Bucket.fraction]/[Dimension.unknownFraction]. */
        val includedValueKr: Double,
        /** Innehav utan ISIN, metadataträff eller känd kurs — exkluderade helt, aldrig gissade in i en kategori (samma princip som [PortfolioFeeCalc.Result.unknownFeeCount]). */
        val excludedCount: Int,
    )

    private data class EligibleHolding(val valueKr: Double, val metadata: FundMetadata)

    fun compute(holdings: List<Holding>, metadataByIsin: Map<String, FundMetadata>): Result {
        var excludedCount = 0
        val eligible = mutableListOf<EligibleHolding>()
        for (holding in holdings) {
            val value = holding.currentValue
            val metadata = holding.fund.isin?.let { metadataByIsin[it] }
            if (value == null || metadata == null) {
                excludedCount++
                continue
            }
            eligible += EligibleHolding(value, metadata)
        }

        val includedValueKr = eligible.sumOf { it.valueKr }

        return Result(
            byType = dimension(eligible, includedValueKr) { h -> h.metadata.tags.firstOrNull { it.category == FundTag.CATEGORY_TYPE }?.title },
            byRegion = dimension(eligible, includedValueKr) { h ->
                h.metadata.tags.firstOrNull { it.category == FundTag.CATEGORY_COMMON_REGION || it.category == FundTag.CATEGORY_OTHER_REGION }?.title
            },
            indexStatus = indexStatusSplit(eligible, includedValueKr),
            includedValueKr = includedValueKr,
            excludedCount = excludedCount,
        )
    }

    private fun dimension(eligible: List<EligibleHolding>, includedValueKr: Double, labelOf: (EligibleHolding) -> String?): Dimension {
        val grouped = eligible.groupBy(labelOf)
        val unknown = grouped[null].orEmpty()
        val buckets = grouped
            .mapNotNull { (label, items) -> label?.let { it to items.sumOf { item -> item.valueKr } } }
            .map { (label, valueKr) -> Bucket(label, valueKr, fractionOf(valueKr, includedValueKr)) }
            .sortedByDescending { it.valueKr }
        val unknownValueKr = unknown.sumOf { it.valueKr }
        return Dimension(buckets, unknownValueKr, fractionOf(unknownValueKr, includedValueKr), unknown.size)
    }

    private fun indexStatusSplit(eligible: List<EligibleHolding>, includedValueKr: Double): IndexStatusSplit {
        val indexValueKr = eligible.filter { it.metadata.indexFund }.sumOf { it.valueKr }
        val activeValueKr = includedValueKr - indexValueKr
        return IndexStatusSplit(
            indexValueKr = indexValueKr,
            indexFraction = fractionOf(indexValueKr, includedValueKr),
            activeValueKr = activeValueKr,
            activeFraction = fractionOf(activeValueKr, includedValueKr),
        )
    }

    private fun fractionOf(part: Double, whole: Double): Double = if (whole == 0.0) 0.0 else part / whole
}
