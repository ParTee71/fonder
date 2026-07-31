package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.Holding

/**
 * Portföljens totala fondavgift per år (HEM-5, issue #60) — ren, testbar summering ovanpå
 * [Holding.currentValue] och fondmetadata (TP-21), i samma anda som [PortfolioPerformanceCalc].
 *
 * Ett innehav utan känt ISIN, utan metadataträff eller utan känd `totalFee` gissas aldrig till
 * noll avgift (samma princip som ANA-4/POR-3) — det exkluderas ur totalen och räknas i
 * [Result.unknownFeeCount] i stället. Ett innehav utan känd kurs ([Holding.currentValue] null)
 * hoppas tyst över helt, utan att räknas här — den bristen har redan sin egen markering
 * (POR-3), och att blanda ihop "okänd kurs" med "okänd avgift" i samma räknare hade gjort
 * antalet svårtolkat.
 */
object PortfolioFeeCalc {

    /** Ett innehavs årliga avgift i kr, för nedbrytningen i [Result.byHolding]. */
    data class HoldingFee(val fund: Fund, val annualFeeKr: Double)

    data class Result(
        val totalAnnualFeeKr: Double,
        /** Störst avgift först. */
        val byHolding: List<HoldingFee>,
        val unknownFeeCount: Int,
    )

    fun compute(holdings: List<Holding>, metadataByIsin: Map<String, FundMetadata>): Result {
        var unknownFeeCount = 0
        val byHolding = mutableListOf<HoldingFee>()

        for (holding in holdings) {
            val value = holding.currentValue ?: continue
            val isin = holding.fund.isin
            val fee = isin?.let { metadataByIsin[it] }?.totalFee
            if (fee == null) {
                unknownFeeCount++
                continue
            }
            byHolding += HoldingFee(holding.fund, FeeComparisonCalc.annualFeeKr(fee, value))
        }

        return Result(
            totalAnnualFeeKr = byHolding.sumOf { it.annualFeeKr },
            byHolding = byHolding.sortedByDescending { it.annualFeeKr },
            unknownFeeCount = unknownFeeCount,
        )
    }
}
