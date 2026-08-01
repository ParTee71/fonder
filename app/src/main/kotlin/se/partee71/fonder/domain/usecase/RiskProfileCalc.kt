package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.RiskProfileAnswers
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Föreslår en målrisknivå ur riskprofilens tre svar (SET-3, issue #68) — ett kodifierat
 * omdöme, inte ett verifierbart faktum som t.ex. ANA-9:s avgiftsjämförelse. Ligger därför
 * samlat på ett enda, enhetstestat ställe i klartext, i stället för spridd if-logik. Förslaget
 * är bara ett förslag: användaren äger den slutgiltiga
 * [se.partee71.fonder.domain.model.RiskProfile.targetRiskLevel].
 *
 * [availableLevels] ska komma från källans faktiska riskskala (TP-21) — aldrig hårdkodad,
 * samma princip som alla andra filtervärden i appen (se `FundFilterVocabulary`). En tom skala
 * (ingen fondmetadata hämtad än) ger null, aldrig en gissning eller en krasch.
 */
object RiskProfileCalc {

    /** Full poäng: [se.partee71.fonder.domain.model.TimeHorizon] 0..3 + [se.partee71.fonder.domain.model.DownturnReaction] 0..3 + [se.partee71.fonder.domain.model.PrimaryGoal] 0..2. */
    private const val MAX_SCORE = 8

    fun suggest(answers: RiskProfileAnswers, availableLevels: List<Int>): Int? {
        if (availableLevels.isEmpty()) return null
        val sorted = availableLevels.distinct().sorted()
        val min = sorted.first()
        val max = sorted.last()
        val score = answers.horizon.ordinal + answers.reaction.ordinal + answers.goal.ordinal
        val target = min + ((score.toDouble() / MAX_SCORE) * (max - min)).roundToInt()
        return sorted.minBy { abs(it - target) }
    }
}
