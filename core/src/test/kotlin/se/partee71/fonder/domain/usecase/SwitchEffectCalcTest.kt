package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import se.partee71.fonder.domain.usecase.SwitchEffectCalc.Direction
import se.partee71.fonder.domain.usecase.SwitchEffectCalc.Move

/**
 * [SwitchEffectCalc] (issue #238). Golden-värdena kommer ur
 * `python tools/fondmatte/fondmatte.py golden tools/fondmatte/byteseffekt.json` → `switch_effect`.
 */
class SwitchEffectCalcTest {

    private val eps = 1e-12

    @Test
    fun `matchar fondmatte golden for byteseffekt`() {
        val result = SwitchEffectCalc.compute(
            valueByLevelKr = mapOf(2 to 8000.0, 3 to 24000.0, 4 to 52000.0, 5 to 14000.0),
            pendingProceedsByLevel = mapOf(3 to 2000.0),
            targetAllocation = mapOf(2 to 0.20, 3 to 0.35, 4 to 0.45),
            moves = listOf(Move(5, 2, 7000.0), Move(5, 3, 5000.0), Move(4, 2, null))
        )!!

        assertEquals(listOf(2, 3, 4, 5), result.levels.map { it.level })
        assertEquals(listOf(0.08, 0.26, 0.52, 0.14), result.levels.map { it.actualFraction })
        assertEquals(listOf(0.20, 0.35, 0.45, 0.0), result.levels.map { it.targetFraction })
        assertEquals(Direction.TOO_MUCH_RISK, result.direction)
        assertEquals(3.72, result.weightedActualLevel!!, eps)
        assertEquals(3.25, result.weightedTargetLevel, eps)

        val (first, second, third) = result.steps
        assertEquals(5, first!!.from.level)
        assertEquals(0.14, first.from.beforeFraction, eps)
        assertEquals(0.07, first.from.afterFraction, eps)
        assertEquals(2, first.to.level)
        assertEquals(0.08, first.to.beforeFraction, eps)
        assertEquals(0.15, first.to.afterFraction, eps)
        // Kumulativt: steg 2 utgår från läget efter steg 1 (nivå 5 7 %, inte 14 %).
        assertEquals(0.07, second!!.from.beforeFraction, eps)
        assertEquals(0.02, second.from.afterFraction, eps)
        assertEquals(0.26, second.to.beforeFraction, eps)
        assertEquals(0.31, second.to.afterFraction, eps)
        assertNull(third)
        assertNull(result.totalToMoveKr)
        assertNull(result.reachesTargetAfterPlan)
    }

    @Test
    fun `nivaer over malet med minst fem procentenheter ar overviktade`() {
        val result = SwitchEffectCalc.compute(mapOf(2 to 8000.0, 3 to 26000.0, 4 to 52000.0, 5 to 14000.0), emptyMap(), mapOf(2 to 0.20, 3 to 0.35, 4 to 0.45), emptyList())!!

        assertEquals(listOf(false, false, true, true), result.levels.map { it.isOverweight })
    }

    @Test
    fun `summan att flytta ar bytenas belopp`() {
        val result = SwitchEffectCalc.compute(mapOf(4 to 60.0, 2 to 40.0), emptyMap(), mapOf(2 to 0.5, 4 to 0.5), listOf(Move(4, 2, 6.0), Move(4, 2, 4.0)))!!

        assertEquals(10.0, result.totalToMoveKr!!, eps)
        assertEquals(0.5, result.steps.last()!!.to.afterFraction, eps)
        assertEquals(true, result.reachesTargetAfterPlan)
    }

    @Test
    fun `en plan som slutar fore malet nar inte fram`() {
        // 20 pp under målet på nivå 2, men planen flyttar bara 10 kr av 100.
        val result = SwitchEffectCalc.compute(mapOf(4 to 70.0, 2 to 30.0), emptyMap(), mapOf(2 to 0.5, 4 to 0.5), listOf(Move(4, 2, 10.0)))!!

        assertEquals(false, result.reachesTargetAfterPlan)
    }

    @Test
    fun `ett inspelat belopp storre an nivans varde flyttar bara det som finns`() {
        // Planen spelades in när nivå 5 hade mer; i dag finns 25 kr kvar där.
        val result = SwitchEffectCalc.compute(mapOf(5 to 25.0, 2 to 75.0), emptyMap(), mapOf(2 to 1.0), listOf(Move(5, 2, 40.0)))!!

        val step = result.steps.single()!!
        assertEquals(0.0, step.from.afterFraction, eps)
        assertEquals(1.0, step.to.afterFraction, eps)
    }

    @Test
    fun `i linje nar ingen niva avviker fem procentenheter`() {
        val result = SwitchEffectCalc.compute(mapOf(3 to 48.0, 4 to 52.0), emptyMap(), mapOf(3 to 0.5, 4 to 0.5), emptyList())!!

        assertEquals(Direction.IN_LINE, result.direction)
    }

    @Test
    fun `ratt snitt men fel mix`() {
        val result = SwitchEffectCalc.compute(mapOf(2 to 50.0, 6 to 50.0), emptyMap(), mapOf(4 to 1.0), emptyList())!!

        assertEquals(Direction.MIXED, result.direction)
        assertEquals(listOf(2, 4, 6), result.levels.map { it.level })
    }

    @Test
    fun `for lite risk`() {
        val result = SwitchEffectCalc.compute(mapOf(2 to 100.0), emptyMap(), mapOf(4 to 1.0), emptyList())!!

        assertEquals(Direction.TOO_LITTLE_RISK, result.direction)
    }

    @Test
    fun `likviden raknas pa malnivan i bade nivans vikt och namnaren`() {
        val result = SwitchEffectCalc.compute(mapOf(4 to 50.0), mapOf(2 to 50.0), mapOf(2 to 0.5, 4 to 0.5), emptyList())!!

        assertEquals(Direction.IN_LINE, result.direction)
        assertEquals(listOf(0.5, 0.5), result.levels.map { it.actualFraction })
    }

    @Test
    fun `niva i malet utan innehav visas med noll`() {
        val result = SwitchEffectCalc.compute(mapOf(4 to 100.0), emptyMap(), mapOf(2 to 0.3, 4 to 0.7), emptyList())!!

        val level2 = result.levels.first { it.level == 2 }
        assertEquals(0.0, level2.actualFraction, eps)
        assertEquals(0.3, level2.targetFraction, eps)
    }

    @Test
    fun `nivaer med noll i bade mal och innehav utelamnas`() {
        val result = SwitchEffectCalc.compute(mapOf(4 to 100.0, 6 to 0.0), emptyMap(), mapOf(3 to 0.0, 4 to 1.0), emptyList())!!

        assertEquals(listOf(4), result.levels.map { it.level })
    }

    @Test
    fun `tom plan ger inga steg och ingen summa`() {
        val result = SwitchEffectCalc.compute(mapOf(4 to 100.0), emptyMap(), mapOf(4 to 1.0), emptyList())!!

        assertEquals(emptyList<SwitchEffectCalc.StepEffect?>(), result.steps)
        assertNull(result.totalToMoveKr)
    }

    @Test
    fun `okant belopp i forsta steget gor alla steg okanda`() {
        val result = SwitchEffectCalc.compute(mapOf(5 to 50.0, 2 to 50.0), emptyMap(), mapOf(2 to 1.0), listOf(Move(5, 2, null), Move(5, 2, 10.0)))!!

        assertEquals(listOf(null, null), result.steps)
        assertNull(result.totalToMoveKr)
    }

    @Test
    fun `utan klassificerat varde finns inget lage`() {
        val result = SwitchEffectCalc.compute(emptyMap(), emptyMap(), mapOf(3 to 1.0), listOf(Move(5, 3, 10.0)))

        assertNotNull(result)
        assertNull(result!!.direction)
        assertNull(result.weightedActualLevel)
        assertEquals(listOf(3), result.levels.map { it.level })
        assertEquals(listOf(null), result.steps)
    }

    @Test
    fun `utan malfordelning finns inget kort`() {
        assertNull(SwitchEffectCalc.compute(mapOf(4 to 100.0), emptyMap(), emptyMap(), emptyList()))
    }

    @Test
    fun `valueByLevel ger nivans varde i kronor`() {
        val dimension = PortfolioExposureCalc.Dimension(
            buckets = listOf(PortfolioExposureCalc.Bucket("4", 700.0, 0.7), PortfolioExposureCalc.Bucket("2", 100.0, 0.1)),
            unknownValueKr = 200.0,
            unknownFraction = 0.2,
            unknownCount = 1
        )

        assertEquals(mapOf(4 to 700.0, 2 to 100.0), PortfolioRiskCalc.valueByLevel(dimension))
    }
}
