package se.partee71.fonder.domain.usecase

import kotlin.math.abs

/**
 * Riskkortets underlag (HEM-7, HEM-17, issue #238): andelen per risknivå mot målet, läget i en
 * mening ([Direction]), summan bytesplanen flyttar och **effekten av varje byte per nivå** —
 * "Nivå 5 14 % → 7 %, nivå 2 8 % → 15 %". Svaret på "varför?" räknas här och bara här, så Hem och
 * Beslut aldrig kan säga olika om samma portfölj.
 *
 * Samma nämnare som bytesplanen ([SwitchPlanCalc.plan]): portföljens **klassificerade** värde
 * (känd risknivå och känd kurs, [PortfolioRiskCalc.valueByLevel]) plus likviden som väntar på köp,
 * räknad på bevakningens målnivå (BYT-3, [SwitchPlanCalc.pendingProceedsByLevel]). Annars hade
 * kortet visat ett gap som planen redan anser finansierat.
 *
 * Effekten är **kumulativ**: planen är girig och sekventiell, så steg n förutsätter att steg
 * 0..n−1 gjorts — effekten av steg 2 räknas från läget efter steg 1, precis som planen räknade
 * fram det. Saknas beloppet för ett steg (rader inspelade före issue #75) går varken det eller
 * något senare steg att räkna, och de blir null i stället för en gissning.
 */
object SwitchEffectCalc {

    /**
     * Läget i en mening. [IN_LINE] när ingen nivå avviker minst [SwitchPlanCalc.MIN_GAP_PP] — samma
     * gräns under vilken planen inte föreslår något. Annars avgör det värdeviktade snittet mot
     * målets: minst [DIRECTION_MIN_LEVELS] över är [TOO_MUCH_RISK], lika långt under
     * [TOO_LITTLE_RISK], och däremellan [MIXED] — snittet stämmer men pengarna ligger på fel nivåer
     * (en 50/50-mix av nivå 2 och 6 mot ett mål på nivå 4).
     */
    enum class Direction { TOO_MUCH_RISK, TOO_LITTLE_RISK, MIXED, IN_LINE }

    /**
     * Hur långt (i risknivåer) det värdeviktade snittet ska ligga från målets för att läget ska
     * kallas för mycket eller för lite risk. En kvarts nivå: mindre än så syns inte som en
     * riktning i fördelningen, bara som brus mellan två grannivåer.
     */
    const val DIRECTION_MIN_LEVELS = 0.25

    /** Ett byte i planen, i planens ordning. [amountKr] null = beloppet okänt (se klassens KDoc). */
    data class Move(val fromLevel: Int, val toLevel: Int, val amountKr: Double?)

    /** En nivås andel av det klassificerade värdet mot målet. */
    data class LevelShare(val level: Int, val targetFraction: Double, val actualFraction: Double) {
        /** Faktisk minus mål i procentenheter — positiv = för mycket på nivån. */
        val excessPp: Double get() = (actualFraction - targetFraction) * 100.0

        /** För mycket på nivån — minst [SwitchPlanCalc.MIN_GAP_PP] över målet, samma gräns som planen säljer vid. */
        val isOverweight: Boolean get() = excessPp >= SwitchPlanCalc.MIN_GAP_PP
    }

    /** En nivås andel före och efter ett steg. */
    data class LevelChange(val level: Int, val beforeFraction: Double, val afterFraction: Double)

    /** Effekten av ett steg: nivån det säljs från och nivån det köps på. */
    data class StepEffect(val from: LevelChange, val to: LevelChange)

    data class Result(
        /** Nivåerna som finns i målet eller innehavet, lägst först. */
        val levels: List<LevelShare>,
        /** Null när inget värde är klassificerat — då finns inget läge att beskriva. */
        val direction: Direction?,
        /** Innehavens värdeviktade nivå, likviden inräknad på sin målnivå. Null som [direction]. */
        val weightedActualLevel: Double?,
        val weightedTargetLevel: Double,
        /** En per [Move], i samma ordning; null från första steget med okänt belopp. */
        val steps: List<StepEffect?>,
        /** Summan av bytenas belopp — null utan byten eller när något belopp är okänt. */
        val totalToMoveKr: Double?
    )

    /**
     * @param valueByLevelKr klassificerat värde per nivå i kronor ([PortfolioRiskCalc.valueByLevel]).
     * @param pendingProceedsByLevel likvid som väntar på köp per målnivå (BYT-3).
     * @param targetAllocation målfördelningen ([se.partee71.fonder.domain.model.RiskProfile.effectiveAllocation]).
     * @param moves planens byten i ordning, utan sålda och genomförda (de syns redan i värdet).
     * @return null utan målfördelning — utan profil finns inget kort.
     */
    fun compute(
        valueByLevelKr: Map<Int, Double>,
        pendingProceedsByLevel: Map<Int, Double>,
        targetAllocation: Map<Int, Double>,
        moves: List<Move>
    ): Result? {
        val targetSum = targetAllocation.values.sum()
        if (targetAllocation.isEmpty() || targetSum <= 0.0) return null
        val weightedTarget = targetAllocation.entries.sumOf { it.key * it.value } / targetSum

        val values = valueByLevelKr.filterValues { it > 0.0 }.toMutableMap()
        pendingProceedsByLevel.forEach { (level, kr) -> if (kr > 0.0) values[level] = (values[level] ?: 0.0) + kr }
        val totalKr = values.values.sum()

        val levels = (targetAllocation.filterValues { it > 0.0 }.keys + values.keys).toSortedSet().map { level ->
            LevelShare(
                level = level,
                targetFraction = targetAllocation[level] ?: 0.0,
                actualFraction = if (totalKr > 0.0) (values[level] ?: 0.0) / totalKr else 0.0
            )
        }

        val totalToMoveKr = moves.takeIf { it.isNotEmpty() && it.all { move -> move.amountKr != null } }?.sumOf { it.amountKr!! }
        if (totalKr <= 0.0) {
            return Result(levels, direction = null, weightedActualLevel = null, weightedTargetLevel = weightedTarget, steps = moves.map { null }, totalToMoveKr = totalToMoveKr)
        }

        val weightedActual = values.entries.sumOf { it.key * it.value } / totalKr
        val gaps = SwitchPlanCalc.gapsPp(targetAllocation, values, totalKr)
        val direction = when {
            gaps.values.all { abs(it) < SwitchPlanCalc.MIN_GAP_PP } -> Direction.IN_LINE
            weightedActual - weightedTarget >= DIRECTION_MIN_LEVELS -> Direction.TOO_MUCH_RISK
            weightedTarget - weightedActual >= DIRECTION_MIN_LEVELS -> Direction.TOO_LITTLE_RISK
            else -> Direction.MIXED
        }

        // Kumulativt: varje steg flyttar från läget efter det förra. Totalen är oförändrad — ett byte
        // flyttar pengar mellan nivåer, det tillför eller tar inget.
        var known = true
        val steps = moves.map { move ->
            val amount = move.amountKr
            if (!known || amount == null) {
                known = false
                return@map null
            }
            val fromBefore = (values[move.fromLevel] ?: 0.0) / totalKr
            val toBefore = (values[move.toLevel] ?: 0.0) / totalKr
            values[move.fromLevel] = (values[move.fromLevel] ?: 0.0) - amount
            values[move.toLevel] = (values[move.toLevel] ?: 0.0) + amount
            StepEffect(
                from = LevelChange(move.fromLevel, fromBefore, (values[move.fromLevel] ?: 0.0) / totalKr),
                to = LevelChange(move.toLevel, toBefore, (values[move.toLevel] ?: 0.0) / totalKr)
            )
        }
        return Result(levels, direction, weightedActual, weightedTarget, steps, totalToMoveKr)
    }
}
