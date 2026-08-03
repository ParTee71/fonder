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
    fun `alla fem fordelningar summerar till 100 procent`() {
        for (profile in RiskProfileCalc.Profile.entries) {
            assertTrue("${profile.name} summerar till ${profile.allocation.values.sum()}", RiskProfileCalc.isCompleteAllocation(profile.allocation))
        }
    }

    @Test
    fun `isCompleteAllocation avvisar en fordelning som inte summerar till 100`() {
        assertTrue(RiskProfileCalc.isCompleteAllocation(mapOf(3 to 0.25, 4 to 0.5, 5 to 0.25)))
        assertTrue(RiskProfileCalc.isCompleteAllocation(mapOf(3 to 0.5, 4 to 0.5)))

        assertTrue(!RiskProfileCalc.isCompleteAllocation(mapOf(3 to 0.5, 4 to 0.2)))
        assertTrue(!RiskProfileCalc.isCompleteAllocation(mapOf(3 to 0.6, 4 to 0.6)))
        assertTrue(!RiskProfileCalc.isCompleteAllocation(emptyMap()))
    }

    @Test
    fun `horisontspargen kan inte havas av risktolerans`() {
        // "Köper mer" + "maximal tillväxt" är högsta möjliga risktolerans, men under 3 års
        // horisont ger ändå Bevarande — motiverat av uppmätt återhämtningstid (issue #71).
        val level = RiskProfileCalc.suggest(
            answers(TimeHorizon.UNDER_3_AR, DownturnReaction.KOPER_MER, PrimaryGoal.MAXIMAL_TILLVAXT),
            fullScale,
        )
        assertEquals(RiskProfileCalc.Profile.BEVARANDE.allocation, level)
    }

    @Test
    fun `under 3 ar ger alltid Bevarande oavsett svar`() {
        for (reaction in DownturnReaction.entries) {
            for (goal in PrimaryGoal.entries) {
                val level = RiskProfileCalc.suggest(answers(TimeHorizon.UNDER_3_AR, reaction, goal), fullScale)
                assertEquals(RiskProfileCalc.Profile.BEVARANDE.allocation, level)
            }
        }
    }

    @Test
    fun `3-7 ar tar aldrig hogre an Balanserad`() {
        val level = RiskProfileCalc.suggest(
            answers(TimeHorizon.TRE_TILL_7_AR, DownturnReaction.KOPER_MER, PrimaryGoal.MAXIMAL_TILLVAXT),
            fullScale,
        )
        assertEquals(RiskProfileCalc.Profile.BALANSERAD.allocation, level)
    }

    @Test
    fun `7-15 ar tar aldrig hogre an Tillvaxt`() {
        val level = RiskProfileCalc.suggest(
            answers(TimeHorizon.SJU_TILL_15_AR, DownturnReaction.KOPER_MER, PrimaryGoal.MAXIMAL_TILLVAXT),
            fullScale,
        )
        assertEquals(RiskProfileCalc.Profile.TILLVAXT.allocation, level)
    }

    @Test
    fun `over 15 ar och hogsta risktolerans ger Offensiv`() {
        val level = RiskProfileCalc.suggest(
            answers(TimeHorizon.OVER_15_AR, DownturnReaction.KOPER_MER, PrimaryGoal.MAXIMAL_TILLVAXT),
            fullScale,
        )
        assertEquals(RiskProfileCalc.Profile.OFFENSIV.allocation, level)
    }

    @Test
    fun `lagsta risktoleransen ger Bevarande aven med lang horisont`() {
        val level = RiskProfileCalc.suggest(
            answers(TimeHorizon.OVER_15_AR, DownturnReaction.SALJER_ALLT, PrimaryGoal.BEVARA),
            fullScale,
        )
        assertEquals(RiskProfileCalc.Profile.BEVARANDE.allocation, level)
    }

    @Test
    fun `forslaget hamnar alltid inom den tillgangliga skalan och summerar till 100`() {
        for (horizon in TimeHorizon.entries) {
            for (reaction in DownturnReaction.entries) {
                for (goal in PrimaryGoal.entries) {
                    val suggestion = RiskProfileCalc.suggest(answers(horizon, reaction, goal), fullScale)
                    assertTrue(suggestion != null && suggestion.keys.all { it in fullScale })
                    assertTrue(RiskProfileCalc.isCompleteAllocation(suggestion!!))
                }
            }
        }
    }

    @Test
    fun `en enda tillganglig niva samlar hela fordelningen dar`() {
        val suggestion = RiskProfileCalc.suggest(answers(), listOf(3))
        assertEquals(mapOf(3 to 1.0), suggestion)
    }

    @Test
    fun `nivaer som saknas i skalan klamms mot narmaste tillgangliga och slas ihop`() {
        // Försiktig = {2: 0,40, 3: 0,40, 4: 0,20}. En skala utan 2 klämmer den nivån mot 3.
        val suggestion = RiskProfileCalc.suggest(
            answers(TimeHorizon.TRE_TILL_7_AR, DownturnReaction.GOR_INGET, PrimaryGoal.BEVARA),
            listOf(3, 4, 5, 6),
        )
        assertEquals(RiskProfileCalc.Profile.FORSIKTIG.allocation, mapOf(2 to 0.40, 3 to 0.40, 4 to 0.20))
        assertEquals(mapOf(3 to 0.80, 4 to 0.20), suggestion)
    }

    @Test
    fun `dubblettvarden i skalan paverkar inte resultatet`() {
        val level = RiskProfileCalc.suggest(
            answers(TimeHorizon.OVER_15_AR, DownturnReaction.KOPER_MER, PrimaryGoal.MAXIMAL_TILLVAXT),
            listOf(6, 6, 6, 1, 2, 3, 4, 5),
        )
        assertEquals(RiskProfileCalc.Profile.OFFENSIV.allocation, level)
    }
}
