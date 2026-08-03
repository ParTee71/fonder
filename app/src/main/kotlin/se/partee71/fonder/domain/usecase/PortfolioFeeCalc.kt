package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.Holding
import java.time.LocalDate

/**
 * Portföljens totala fondavgift per år (HEM-5, issue #60) och samlade besparingspotential
 * (HEM-6, issue #61) — ren, testbar summering ovanpå [Holding.currentValue] och fondmetadata
 * (TP-21), i samma anda som [PortfolioPerformanceCalc].
 *
 * Ett innehav utan känt ISIN, utan metadataträff eller utan känd `totalFee` gissas aldrig till
 * noll avgift (samma princip som ANA-4/POR-3) — det exkluderas ur totalen och räknas i
 * [Result.unknownFeeCount] i stället. Ett innehav utan känd kurs ([Holding.currentValue] null)
 * hoppas tyst över helt, utan att räknas här — den bristen har redan sin egen markering
 * (POR-3), och att blanda ihop "okänd kurs" med "okänd avgift" i samma räknare hade gjort
 * antalet svårtolkat.
 *
 * Besparingen räknas bara in för innehav med ett **färskt** jämförelseresultat
 * ([FundMetadata.comparisonResolvedAtEpochDay] satt och inte äldre än
 * [FundMetadataFreshness.COMPARISON_TTL_DAYS]) — ett utgånget resultat behandlas som osökt,
 * aldrig som en aktuell rekommendation (samma princip som köpbarhets-TTL:en). Kronbeloppet
 * räknas alltid ur innehavets *aktuella* värde, aldrig ur ett sparat kronbelopp — den sparade
 * `cheapestAlternativeFee` är värdeoberoende (se KRAVLISTA HEM-6), men kronorna är det inte.
 */
object PortfolioFeeCalc {

    /**
     * Ett innehavs årliga avgift i kr, för nedbrytningen i [Result.byHolding].
     * [annualSavingsKr] null = inget billigare alternativ i ett färskt resultat — antingen för
     * att inget hittades vid jämförelsen ([wasCompared] sant) eller för att innehavet aldrig
     * blivit (färskt) jämfört ([wasCompared] falskt). De två får inte blandas ihop i UI:t —
     * annars ser ett outforskat innehav ut som redan bland de billigaste.
     */
    data class HoldingFee(
        val fund: Fund,
        val annualFeeKr: Double,
        val annualSavingsKr: Double? = null,
        val wasCompared: Boolean = false,
    )

    data class Result(
        val totalAnnualFeeKr: Double,
        /** Störst avgift först. */
        val byHolding: List<HoldingFee>,
        val unknownFeeCount: Int,
        /** Summan av [HoldingFee.annualSavingsKr] för innehav med ett känt, färskt resultat. */
        val totalAnnualSavingsKr: Double = 0.0,
        /** Antal innehav med ett färskt jämförelseresultat, av [comparableCount] totalt. */
        val comparedCount: Int = 0,
        /** Innehav med känd avgift — de enda som någonsin kan bli genomsökta. */
        val comparableCount: Int = 0,
    )

    fun compute(holdings: List<Holding>, metadataByIsin: Map<String, FundMetadata>, today: LocalDate): Result {
        var unknownFeeCount = 0
        var comparedCount = 0
        val byHolding = mutableListOf<HoldingFee>()

        for (holding in holdings) {
            val value = holding.currentValue ?: continue
            val isin = holding.fund.isin
            val metadata = isin?.let { metadataByIsin[it] }
            val fee = metadata?.totalFee
            if (fee == null) {
                unknownFeeCount++
                continue
            }

            val resolvedAt = metadata.comparisonResolvedAtEpochDay
            val isFresh = resolvedAt != null && !FundMetadataFreshness.isStale(resolvedAt, today, FundMetadataFreshness.COMPARISON_TTL_DAYS)
            if (isFresh) comparedCount++
            val cheaperFee = if (isFresh) metadata.cheapestAlternativeFee else null
            // Den sparade alternativavgiften är en ögonblicksbild som bevaras i
            // COMPARISON_TTL_DAYS. Sjunker innehavets egen avgift under den inom fönstret blir
            // differensen negativ — och HEM-6 skrev ut "du kan spara -180,00 kr per år". En
            // icke-positiv besparing är ingen besparing: den räknas som "inget billigare".
            val savings = cheaperFee
                ?.let { FeeComparisonCalc.annualFeeKr(fee, value) - FeeComparisonCalc.annualFeeKr(it, value) }
                ?.takeIf { it > 0.0 }

            byHolding += HoldingFee(holding.fund, FeeComparisonCalc.annualFeeKr(fee, value), savings, wasCompared = isFresh)
        }

        return Result(
            totalAnnualFeeKr = byHolding.sumOf { it.annualFeeKr },
            byHolding = byHolding.sortedByDescending { it.annualFeeKr },
            unknownFeeCount = unknownFeeCount,
            totalAnnualSavingsKr = byHolding.sumOf { it.annualSavingsKr ?: 0.0 },
            comparedCount = comparedCount,
            comparableCount = byHolding.size,
        )
    }
}
