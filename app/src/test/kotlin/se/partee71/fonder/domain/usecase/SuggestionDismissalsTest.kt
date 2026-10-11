package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.SuggestionDismissal
import se.partee71.fonder.domain.model.SuggestionKind
import java.time.LocalDate

/** HEM-14: vilka förslag ett avstående döljer, och hur länge. */
class SuggestionDismissalsTest {

    private val today = LocalDate.of(2026, 10, 11)

    private fun suggestion(recordId: Long, planIndex: Int, sell: String, buy: String, followed: Boolean = false) =
        SwitchPlanResolver.Suggestion(
            recordId = recordId, planIndex = planIndex, sellIsin = sell, buyIsin = buy,
            sellFundName = sell, buyFundName = buy, fromLevel = 5, toLevel = 3, feeDeltaPercent = 0.0,
            followed = followed,
        )

    private fun dismissal(sell: String, buy: String, daysAgo: Long = 0, kind: SuggestionKind = SuggestionKind.RISK_PLAN) =
        SuggestionDismissal(sellIsin = sell, buyIsin = buy, kind = kind, dismissedAtEpochDay = today.minusDays(daysAgo).toEpochDay())

    @Test
    fun `avstaendet galler fran dagen det gjordes till och med TTL_DAYS dagar senare`() {
        assertTrue(SuggestionDismissals.isActive(dismissal("A", "B"), today))
        assertTrue(SuggestionDismissals.isActive(dismissal("A", "B", daysAgo = SuggestionDismissals.TTL_DAYS), today))
        assertFalse(SuggestionDismissals.isActive(dismissal("A", "B", daysAgo = SuggestionDismissals.TTL_DAYS + 1), today))
    }

    @Test
    fun `ett avstatt par doljs medan resten av planen behaller sin rangordning`() {
        val plan = listOf(suggestion(1, 0, "A", "B"), suggestion(2, 1, "C", "D"))

        val (visible, dismissed) = SuggestionDismissals.partition(plan, listOf(dismissal("A", "B")), today)

        // Ingen omnumrering: byte 2 står kvar som "2.", så det syns att ett steg hoppats över.
        assertEquals(listOf(1), visible.map { it.planIndex })
        assertEquals(listOf(1L), dismissed.map { it.recordId })
    }

    @Test
    fun `det omvanda bytet ar ett annat par och visas`() {
        val plan = listOf(suggestion(1, 0, "B", "A"))

        val (visible, dismissed) = SuggestionDismissals.partition(plan, listOf(dismissal("A", "B")), today)

        assertEquals(plan, visible)
        assertTrue(dismissed.isEmpty())
    }

    @Test
    fun `ett avstaende av ett avgiftsbyte doljer inte samma par i bytesplanen`() {
        val plan = listOf(suggestion(1, 0, "A", "B"))

        val (visible, _) = SuggestionDismissals.partition(plan, listOf(dismissal("A", "B", kind = SuggestionKind.FEE)), today)

        assertEquals(plan, visible)
    }

    @Test
    fun `ett genomfort forslag raknas aldrig som avstatt`() {
        val plan = listOf(suggestion(1, 0, "A", "B", followed = true))

        val (visible, dismissed) = SuggestionDismissals.partition(plan, listOf(dismissal("A", "B")), today)

        assertEquals(plan, visible)
        assertTrue(dismissed.isEmpty())
    }

    @Test
    fun `ett utganget avstaende doljer ingenting`() {
        val plan = listOf(suggestion(1, 0, "A", "B"))

        val (visible, dismissed) = SuggestionDismissals.partition(
            plan, listOf(dismissal("A", "B", daysAgo = SuggestionDismissals.TTL_DAYS + 1)), today,
        )

        assertEquals(plan, visible)
        assertTrue(dismissed.isEmpty())
    }
}
