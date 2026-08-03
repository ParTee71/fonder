package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.SuggestionRecord

/**
 * Facit för bytesplanen (SET-5, issue #80) — vad ett inspelat bytesförslag ([SuggestionRecord],
 * HEM-8) gav jämfört med att ha behållit innehavet.
 *
 * Måttet är **meravkastningen**: köpfondens kursutveckling sedan förslagsdagen minus
 * säljfondens. Det är medvetet en jämförelse av två NAV-serier, inte av användarens faktiska
 * resultat — den senare beror på vilken dag bytet verkligen gjordes, på courtage och (i depå/AF,
 * som HEM-8 ändå aldrig ger förslag i) på skatt. Samma precisionsprincip som [PortfolioRiskCalc]
 * använder när den säger att ett värdeviktat snitt inte är portföljens risk.
 *
 * Ren och testbar, utan repository-beroenden: anroparen slår upp dagens NAV och skickar in det.
 */
object SwitchOutcomeCalc {

    /**
     * Utfallet för ett enskilt förslag. Saknas NAV på endera sidan är [excessReturn] null och
     * raden är **ej utvärderad** — aldrig 0, som skulle läsas som "bytet var en nollsumma"
     * (samma princip som ANA-4:s otillräckliga data).
     *
     * @param excessKr [excessReturn] omräknat till kronor på det belopp förslaget avsåg. Null
     *   även för en utvärderad rad om [SuggestionRecord.switchValueKr] saknas — rader inspelade
     *   före issue #75 vet inte vilket belopp bytet gällde, och ett påhittat belopp vore värre
     *   än inget.
     */
    data class Outcome(
        val record: SuggestionRecord,
        val sellReturn: Double? = null,
        val buyReturn: Double? = null,
        val excessReturn: Double? = null,
        val excessKr: Double? = null,
    ) {
        val isEvaluated: Boolean get() = excessReturn != null
    }

    /**
     * Summering över en uppsättning utfall. Procenten och kronorna är **två olika aggregat**,
     * inte samma tal i olika enheter: [averageExcessReturn] är ett enkelt snitt över alla
     * utvärderade förslag (varje råd väger lika — frågan är hur ofta rådet var rätt), medan
     * [totalExcessKr] är summan över dem som dessutom har ett känt belopp (frågan är vad det
     * var värt). De två urvalen skiljer sig därför när historiken innehåller rader från före
     * issue #75, och [evaluatedCount]/[totalCount] gör det synligt.
     */
    data class Summary(
        val averageExcessReturn: Double? = null,
        val totalExcessKr: Double? = null,
        val evaluatedCount: Int = 0,
        val totalCount: Int = 0,
    )

    /** Snittutfallet för en given plats i planen — se [byPlanIndex]. */
    data class PlanIndexSummary(val planIndex: Int, val summary: Summary)

    /**
     * Utvärderar ett förslag mot dagens NAV. [sellNavNow]/[buyNavNow] är null när fonden inte
     * kunnat slås upp eller ännu inte fyllts på av bakgrundsworkern.
     *
     * Ett NAV-utgångsläge på noll eller mindre kan inte ge någon avkastning och behandlas som
     * saknad data i stället för att divideras med — det vore en oändlighet, inte ett utfall.
     */
    fun evaluate(record: SuggestionRecord, sellNavNow: Double?, buyNavNow: Double?): Outcome {
        val sellReturn = returnSince(record.sellNavAtSuggestion, sellNavNow)
        val buyReturn = returnSince(record.buyNavAtSuggestion, buyNavNow)
        if (sellReturn == null || buyReturn == null) {
            return Outcome(record = record, sellReturn = sellReturn, buyReturn = buyReturn)
        }
        val excess = buyReturn - sellReturn
        return Outcome(
            record = record,
            sellReturn = sellReturn,
            buyReturn = buyReturn,
            excessReturn = excess,
            excessKr = record.switchValueKr?.let { it * excess },
        )
    }

    private fun returnSince(navAtSuggestion: Double, navNow: Double?): Double? {
        if (navNow == null || navAtSuggestion <= 0.0) return null
        return navNow / navAtSuggestion - 1.0
    }

    /**
     * Summerar [outcomes]. Ej utvärderade rader räknas i [Summary.totalCount] men drar aldrig
     * ner snittet — de har inget utfall, och att räkna dem som noll hade tystat skillnaden
     * mellan "bytet gav ingenting" och "vi vet inte än".
     */
    fun summarize(outcomes: List<Outcome>): Summary {
        val evaluated = outcomes.filter { it.isEvaluated }
        val withKr = evaluated.mapNotNull { it.excessKr }
        return Summary(
            averageExcessReturn = if (evaluated.isEmpty()) null else evaluated.mapNotNull { it.excessReturn }.average(),
            totalExcessKr = if (withKr.isEmpty()) null else withKr.sum(),
            evaluatedCount = evaluated.size,
            totalCount = outcomes.size,
        )
    }

    /**
     * Summerar per plats i planen, stigande. [SuggestionRecord.planIndex] sparades uttryckligen
     * för att göra mätbart om lägre rankade byten presterar sämre än det högst rankade — den
     * frågan avgör om `SwitchPlanCalc.MAX_SWITCHES_PER_PLAN` (3) kan höjas, och den går inte
     * att svara på förrän utfallen redovisas per plats.
     */
    fun byPlanIndex(outcomes: List<Outcome>): List<PlanIndexSummary> =
        outcomes.groupBy { it.record.planIndex }
            .toSortedMap()
            .map { (index, group) -> PlanIndexSummary(planIndex = index, summary = summarize(group)) }
}
