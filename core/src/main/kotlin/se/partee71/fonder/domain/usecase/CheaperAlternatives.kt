package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord

/**
 * Ett billigare, likvärdigt alternativ (ANA-9) som ett eget förslag på Beslut (NAV-9) — läst ur
 * den **inspelade** avgiftsbytesraden ([SuggestionKind.FEE]), aldrig omräknat.
 *
 * [suggestion] bär det som är gemensamt med bytesplanens förslag — raden "Följ" skriver mot, fondparet
 * ett avstående (HEM-14) gäller, positionens värde och risknivåerna en bevakning (ANA-12) startas med —
 * så Beslut kan kvittera, avstå och bevaka båda sorterna med samma vägar.
 */
data class CheaperAlternative(
    val suggestion: SwitchPlanResolver.Suggestion,
    /** Innehavets totalavgift i procent (TP-21). */
    val sellFeePercent: Double,
    /** Alternativets totalavgift i procent — alltid lägre än [sellFeePercent]. */
    val buyFeePercent: Double
) {
    /**
     * Besparing per år i kronor vid det inspelade innehavsvärdet — samma räknestycke som ANA-9-kortet
     * ([FeeComparisonCalc.annualFeeKr]). Null när raden saknar belopp; då visas ingen besparing
     * hellre än en gissad.
     */
    val annualSavingsKr: Double?
        get() = suggestion.switchValueKr?.let { value ->
            FeeComparisonCalc.annualFeeKr(sellFeePercent, value) - FeeComparisonCalc.annualFeeKr(buyFeePercent, value)
        }
}

/**
 * Gör de inspelade avgiftsbytena (ANA-9, issue #91/#93) visningsbara — den enda läsvägen från
 * `suggestion_records` till Beslut, flikmärket, "Att besluta" (via [PendingDecisionsSource]) och
 * Fonddetaljs kvittering ([latestPerPair]). Jämförelsen själv görs bara av
 * `FundMetadataRepository.suggestCheaperAlternatives`; här räknas ingenting om.
 */
object CheaperAlternatives {

    /**
     * Hur gammal den senaste inspelningen av ett alternativ får vara för att visas på Beslut.
     * Bakgrundsskanningen spelar in varje visat alternativ på nytt varje dygn (budgeterat), så ett
     * alternativ som slutat spelas in — innehavet är sålt eller jämförelsen ger det inte längre —
     * ska försvinna i stället för att ligga kvar. Samma gräns som bytesplanen (HEM-8).
     */
    const val TTL_DAYS = SwitchPlanCalc.PLAN_TTL_DAYS

    /**
     * Den senaste inspelade raden per fondpar bland avgiftsbytena i [records] — kvitteringen ska
     * gälla det senaste rådet, inte ett halvår gammalt som råkar nämna samma par. Ordningen är
     * nyast först oavsett i vilken ordning [records] kom.
     */
    fun latestPerPair(records: List<SuggestionRecord>): List<SuggestionRecord> = records
        .filter { it.kind == SuggestionKind.FEE }
        .sortedWith(
            compareByDescending<SuggestionRecord> { it.suggestedAtEpochDay }
                .thenByDescending { it.batchEpochMillis }
                .thenByDescending { it.id }
        )
        .distinctBy { it.sellIsin to it.buyIsin }

    /**
     * De billigare alternativ som väntar på ett beslut, störst årsbesparing först. Ett alternativ
     * visas bara när
     * - dess senaste inspelning är högst [TTL_DAYS] gammal,
     * - innehavets senaste jämförelse fortfarande visar det ([FundMetadata.shownAlternativeIsins]),
     *   så Beslut aldrig visar ett alternativ Fonddetalj inte längre ger, och
     * - båda fondernas avgift och risknivå är kända och alternativet fortfarande är billigare —
     *   annars vore besparingen gissad (ANA-4-principen).
     *
     * Avstådda alternativ filtreras inte här, utan av [SuggestionDismissals] (samma regel som planen).
     */
    fun resolve(
        records: List<SuggestionRecord>,
        metadataByIsin: Map<String, FundMetadata>,
        today: LocalDate
    ): List<CheaperAlternative> = latestPerPair(records)
        .filterNot { FundMetadataFreshness.isStale(it.suggestedAtEpochDay, today, TTL_DAYS) }
        .mapNotNull { record -> record.toAlternative(metadataByIsin) }
        .sortedWith(compareBy(nullsLast(reverseOrder())) { it.annualSavingsKr })

    private fun SuggestionRecord.toAlternative(metadataByIsin: Map<String, FundMetadata>): CheaperAlternative? {
        val sell = metadataByIsin[sellIsin] ?: return null
        if (buyIsin !in sell.shownAlternativeIsins) return null
        val buy = metadataByIsin[buyIsin] ?: return null
        val sellFee = sell.totalFee ?: return null
        val buyFee = buy.totalFee ?: return null
        if (buyFee >= sellFee) return null
        val fromLevel = sell.risk ?: return null
        val toLevel = buy.risk ?: return null
        return CheaperAlternative(
            suggestion = SwitchPlanResolver.Suggestion(
                recordId = id,
                planIndex = planIndex,
                sellIsin = sellIsin,
                buyIsin = buyIsin,
                sellFundName = sell.name,
                buyFundName = buy.name,
                fromLevel = fromLevel,
                toLevel = toLevel,
                feeDeltaPercent = buyFee - sellFee,
                switchValueKr = switchValueKr,
                followed = followed == true,
                kind = SuggestionKind.FEE
            ),
            sellFeePercent = sellFee,
            buyFeePercent = buyFee
        )
    }
}
