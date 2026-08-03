package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.RiskProfileAnswers
import se.partee71.fonder.domain.model.TimeHorizon
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Föreslår en **målfördelning** över risknivåer ur riskprofilens tre svar (SET-3, issue #68,
 * uppgraderad från en enda skalär nivå till en fördelning i issue #71) — ett kodifierat
 * omdöme, inte ett verifierbart faktum som t.ex. ANA-9:s avgiftsjämförelse. Ligger därför
 * samlat på ett enda, enhetstestat ställe i klartext, i stället för spridd if-logik. Förslaget
 * är bara ett förslag: användaren äger den slutgiltiga
 * [se.partee71.fonder.domain.model.RiskProfile.targetAllocation].
 *
 * [availableLevels] ska komma från källans faktiska riskskala (TP-21) — aldrig hårdkodad,
 * samma princip som alla andra filtervärden i appen (se `FundFilterVocabulary`). En tom skala
 * (ingen fondmetadata hämtad än) ger null, aldrig en gissning eller en krasch.
 */
object RiskProfileCalc {

    /**
     * Enkätens fem namngivna målfördelningar, grundade i uppmätt volatilitet, värsta
     * nedgång, återhämtningstid och sammansättning per risknivå (6 års NAV-historik, se
     * issue #71). Ordnade försiktigast → offensivast; deklarationsordningen driver
     * [horizonCapIndex]s tak.
     *
     * **Offensiv ökar nivå 4, inte nivå 5.** Nivå 5 domineras av tematisk och geografisk
     * koncentration (ny teknik, Kina, fastigheter — 71 av 216 undersökta fonder
     * branschtaggade, mot 15 av 220 på nivå 4) snarare än bred marknadsrisk, och
     * koncentrationsrisk är enligt teorin inte kompenserad på samma sätt som systematisk
     * risk. Att öka nivå 5 vore alltså inte "mer marknad" utan "ett större vad på enskilda
     * teman" — uppmätt gav den bredare nivå-4-tunga fördelningen både högre avkastning och
     * mindre nedgång än ett tidigare utkast med mer nivå 5.
     */
    enum class Profile(val allocation: Map<Int, Double>) {
        BEVARANDE(mapOf(2 to 0.70, 3 to 0.30)),
        FORSIKTIG(mapOf(2 to 0.40, 3 to 0.40, 4 to 0.20)),
        BALANSERAD(mapOf(2 to 0.20, 3 to 0.35, 4 to 0.45)),
        TILLVAXT(mapOf(2 to 0.10, 3 to 0.25, 4 to 0.55, 5 to 0.10)),
        OFFENSIV(mapOf(3 to 0.10, 4 to 0.75, 5 to 0.15)),
    }

    /** Full poäng inom taket: [DownturnReaction] 0..3 + [PrimaryGoal] 0..2. */
    private const val MAX_TOLERANCE_SCORE = 5

    /** Toleranstal för att räkna en fördelning som fullständigt summerad till 100 % (flyttalsavrundning från heltalsprocent). */
    private const val SUM_TOLERANCE = 1e-6

    /**
     * Tidshorisonten är en **hård spärr**, inte en av flera likvärdiga poäng — motiverad av
     * uppmätt återhämtningstid efter värsta nedgången (2,0–2,6 år, samtliga nivåer). En kort
     * horisont kan inte hävas av hög risktolerans: svarar man "köper mer" och "maximal
     * tillväxt" men har under 3 års horisont blir förslaget ändå Bevarande.
     */
    private fun horizonCapIndex(horizon: TimeHorizon): Int = when (horizon) {
        TimeHorizon.UNDER_3_AR -> 0
        TimeHorizon.TRE_TILL_7_AR -> 2
        TimeHorizon.SJU_TILL_15_AR -> 3
        TimeHorizon.OVER_15_AR -> 4
    }

    fun suggest(answers: RiskProfileAnswers, availableLevels: List<Int>): Map<Int, Double>? {
        if (availableLevels.isEmpty()) return null
        val cap = horizonCapIndex(answers.horizon)
        val toleranceScore = answers.reaction.ordinal + answers.goal.ordinal
        val index = ((toleranceScore.toDouble() / MAX_TOLERANCE_SCORE) * cap).roundToInt().coerceIn(0, cap)
        return remapToAvailable(Profile.entries[index].allocation, availableLevels)
    }

    /** En fördelning räknas som sparbar bara om den inte är tom och summerar till (i praktiken) exakt 100 % — ingen tyst normalisering av en avvikande summa (issue #71). */
    fun isCompleteAllocation(allocation: Map<Int, Double>): Boolean =
        allocation.isNotEmpty() && abs(allocation.values.sum() - 1.0) <= SUM_TOLERANCE

    /** Klämmer en fördelnings nivåer mot den faktiskt tillgängliga skalan (samma princip som #68:s tidigare skalär-klämning) — nivåer som saknas i [availableLevels] slås ihop med sin närmaste granne i stället för att tappas bort. */
    private fun remapToAvailable(allocation: Map<Int, Double>, availableLevels: List<Int>): Map<Int, Double> {
        val sorted = availableLevels.distinct().sorted()
        val remapped = linkedMapOf<Int, Double>()
        allocation.forEach { (level, fraction) ->
            val nearest = sorted.minBy { abs(it - level) }
            remapped[nearest] = (remapped[nearest] ?: 0.0) + fraction
        }
        return remapped
    }
}
