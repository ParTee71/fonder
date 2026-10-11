package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.SuggestionKind

/**
 * Antalet som Beslut-flikens märke och Översiktens "Att besluta" visar (NAV-9, HEM-13): öppna
 * förslag plus pågående byten. Ett kvitterat förslag är redan beslutat och räknas inte.
 */
class PendingDecisionsTest {

    private fun suggestion(recordId: Long, followed: Boolean) = SwitchPlanResolver.Suggestion(
        recordId = recordId, planIndex = recordId.toInt(), sellIsin = "S$recordId", buyIsin = "K$recordId",
        sellFundName = "Sälj $recordId", buyFundName = "Köp $recordId", fromLevel = 5, toLevel = 3,
        feeDeltaPercent = 0.0, followed = followed,
    )

    private fun cheaper(recordId: Long, followed: Boolean) = CheaperAlternative(
        suggestion = suggestion(recordId, followed).copy(kind = SuggestionKind.FEE, planIndex = 0),
        sellFeePercent = 1.4,
        buyFeePercent = 0.4,
    )

    private fun watch(id: Long) = OpenSwitchWatch(watchId = id, sellFundName = "Fond $id", daysWaiting = 1, candidateCount = 0)

    @Test
    fun `antalet ar oppna forslag plus pagaende byten`() {
        val decisions = PendingDecisions(
            switchPlan = listOf(suggestion(0, followed = false), suggestion(1, followed = false)),
            openWatches = listOf(watch(7)),
        )

        assertEquals(2, decisions.openSuggestionCount)
        assertEquals(3, decisions.count)
        assertFalse(decisions.isEmpty)
    }

    @Test
    fun `ett kvitterat forslag raknas inte men visas fortfarande`() {
        val decisions = PendingDecisions(switchPlan = listOf(suggestion(0, followed = true)))

        assertEquals(0, decisions.count)
        // Planen ligger kvar på Beslut — "Bevaka alternativ" hör till det kvitterade bytet.
        assertFalse(decisions.isEmpty)
    }

    @Test
    fun `en utgangen bevakning blockerar men raknas inte`() {
        val decisions = PendingDecisions(watchedSellIsins = setOf("SE_SALJ"))

        assertEquals(0, decisions.count)
        assertTrue(decisions.isEmpty)
    }

    @Test
    fun `oppna billigare alternativ raknas for sig och i antalet`() {
        val decisions = PendingDecisions(
            switchPlan = listOf(suggestion(0, followed = false)),
            cheaperAlternatives = listOf(cheaper(10, followed = false), cheaper(11, followed = true)),
            openWatches = listOf(watch(7)),
        )

        assertEquals(1, decisions.openSwitchCount)
        assertEquals(1, decisions.openCheaperAlternativeCount)
        assertEquals(2, decisions.openSuggestionCount)
        assertEquals(3, decisions.count)
    }

    @Test
    fun `bara billigare alternativ ar inte ett tomt lage och avstadda raknas ihop`() {
        val decisions = PendingDecisions(
            cheaperAlternatives = listOf(cheaper(10, followed = true)),
            dismissedSuggestions = listOf(suggestion(0, followed = false)),
            dismissedCheaperAlternatives = listOf(cheaper(11, followed = false)),
        )

        assertFalse(decisions.isEmpty)
        assertEquals(0, decisions.count)
        assertEquals(2, decisions.dismissedCount)
    }
}
