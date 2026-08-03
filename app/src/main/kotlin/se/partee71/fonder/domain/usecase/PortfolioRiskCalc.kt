package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.Holding

/**
 * Innehavens genomsnittliga risknivå, viktad på värde (HEM-7, issue #68) —
 * `Σ(värde × risk) / Σ(värde)`, på källans egen riskskala ([FundMetadata.risk], TP-21).
 *
 * Det här är uttryckligen ett värdeviktat medel av de enskilda fondernas risknivåer, **inte**
 * "portföljens risk": korrelation och diversifiering modelleras inte — en 50/50-mix av nivå 1
 * och 6 räknas identiskt med en enda fond på nivå 3,5. Samma precisionsprincip som ANA-1
 * använder när den skiljer fondens kursutveckling från den egna avkastningen; UI-texten ska
 * säga vad måttet är.
 *
 * Innehav utan ISIN, metadataträff eller känd risknivå exkluderas helt och räknas separat,
 * aldrig en gissad risksiffra — samma princip som [PortfolioFeeCalc.Result.unknownFeeCount]/
 * [PortfolioExposureCalc.Result.excludedCount].
 */
object PortfolioRiskCalc {

    data class Result(
        /** Null om inget innehav har en känd risknivå (t.ex. tom portfölj) — 0 vore inte en risknivå på skalan. */
        val weightedAverageRisk: Double?,
        val includedValueKr: Double,
        val excludedCount: Int,
    )

    fun compute(holdings: List<Holding>, metadataByIsin: Map<String, FundMetadata>): Result {
        var excludedCount = 0
        var weightedSum = 0.0
        var includedValueKr = 0.0
        for (holding in holdings) {
            val value = holding.currentValue
            val risk = holding.fund.isin?.let { metadataByIsin[it] }?.risk
            if (value == null || risk == null) {
                excludedCount++
                continue
            }
            weightedSum += value * risk
            includedValueKr += value
        }
        val weighted = if (includedValueKr == 0.0) null else weightedSum / includedValueKr
        return Result(weightedAverageRisk = weighted, includedValueKr = includedValueKr, excludedCount = excludedCount)
    }

    /** Mål- och faktisk andel för en enskild risknivå, plus den härledda avvikelsen ur [deviationByLevel]. */
    data class LevelDeviation(val level: Int, val targetFraction: Double, val actualFraction: Double) {
        /** Mål minus faktisk — positiv betyder underviktad (köp här), negativ betyder överviktad (sälj här). */
        val deviationFraction: Double get() = targetFraction - actualFraction
    }

    /**
     * Avvikelse per risknivå (HEM-7/issue #71) — målfördelningen
     * ([se.partee71.fonder.domain.model.RiskProfile.effectiveAllocation]) mot innehavens
     * faktiska fördelning (t.ex. [PortfolioExposureCalc.Result.byRiskLevel]).
     * Sorterat på störst underviktning först, vilket direkt pekar ut hinken som bör fyllas —
     * det underlag issue #70 (bytesplan) konsumerar. Unionen av nivåer på båda sidor tas med,
     * så en nivå som bara finns i den ena fördelningen ändå syns med sin fulla avvikelse.
     */
    fun deviationByLevel(targetAllocation: Map<Int, Double>, actualAllocation: Map<Int, Double>): List<LevelDeviation> {
        val levels = (targetAllocation.keys + actualAllocation.keys).toSortedSet()
        return levels
            .map { level -> LevelDeviation(level, targetAllocation[level] ?: 0.0, actualAllocation[level] ?: 0.0) }
            .sortedByDescending { it.deviationFraction }
    }
}
