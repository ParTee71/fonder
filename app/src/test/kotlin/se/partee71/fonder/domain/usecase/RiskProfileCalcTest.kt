package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.RiskProfileAnswers
import se.partee71.fonder.domain.model.TimeHorizon

class RiskProfileCalcTest {

    private val fullScale = (1..6).toList()

    private fun answers(
        horizon: TimeHorizon = TimeHorizon.TRE_TILL_7_AR,
        reaction: DownturnReaction = DownturnReaction.GOR_INGET,
        goal: PrimaryGoal = PrimaryGoal.BALANSERAD,
    ) = RiskProfileAnswers(horizon, reaction, goal)

    @Test
    fun `tom skala ger null, aldrig en gissning`() {
        assertNull(RiskProfileCalc.suggest(answers(), emptyList()))
    }

    @Test
    fun `forslaget hamnar alltid inom den tillgangliga skalan`() {
        // En skala som bara går till 6 ska aldrig ge 7, oavsett svarskombination.
        for (horizon in TimeHorizon.entries) {
            for (reaction in DownturnReaction.entries) {
                for (goal in PrimaryGoal.entries) {
                    val level = RiskProfileCalc.suggest(answers(horizon, reaction, goal), fullScale)
                    assertTrue("level=$level utanför skalan", level != null && level in fullScale)
                }
            }
        }
    }

    @Test
    fun `lagsta svarskombinationen ger lagsta nivan`() {
        val level = RiskProfileCalc.suggest(
            answers(TimeHorizon.UNDER_3_AR, DownturnReaction.SALJER_ALLT, PrimaryGoal.BEVARA),
            fullScale,
        )
        assertEquals(1, level)
    }

    @Test
    fun `hogsta svarskombinationen ger hogsta nivan`() {
        val level = RiskProfileCalc.suggest(
            answers(TimeHorizon.OVER_15_AR, DownturnReaction.KOPER_MER, PrimaryGoal.MAXIMAL_TILLVAXT),
            fullScale,
        )
        assertEquals(6, level)
    }

    @Test
    fun `forslaget klampas till narmaste tillgangliga niva vid en skala med luckor`() {
        // En skala utan 4 och 5 (hypotetiskt) ska aldrig få förslaget att hamna där.
        val sparseScale = listOf(1, 2, 3, 6)
        val level = RiskProfileCalc.suggest(
            answers(TimeHorizon.SJU_TILL_15_AR, DownturnReaction.GOR_INGET, PrimaryGoal.BALANSERAD),
            sparseScale,
        )
        assertTrue(level in sparseScale)
    }

    @Test
    fun `en enda tillganglig niva ger alltid den nivan`() {
        assertEquals(3, RiskProfileCalc.suggest(answers(), listOf(3)))
    }

    @Test
    fun `dubblettvarden i skalan paverkar inte resultatet`() {
        val level = RiskProfileCalc.suggest(
            answers(TimeHorizon.OVER_15_AR, DownturnReaction.KOPER_MER, PrimaryGoal.MAXIMAL_TILLVAXT),
            listOf(6, 6, 6, 1, 2, 3, 4, 5),
        )
        assertEquals(6, level)
    }
}
