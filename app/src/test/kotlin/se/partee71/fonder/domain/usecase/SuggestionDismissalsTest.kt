package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.SuggestionDismissal
import se.partee71.fonder.domain.model.SuggestionKind

/** HEM-14: vilka förslag ett avstående döljer, och hur länge. */
class SuggestionDismissalsTest {

    private val today = LocalDate.of(2026, 10, 11)

    private fun suggestion(
        recordId: Long,
        planIndex: Int,
        sell: String,
        buy: String,
        followed: Boolean = false,
        kind: SuggestionKind = SuggestionKind.RISK_PLAN
    ) = SwitchPlanResolver.Suggestion(
        recordId = recordId, planIndex = planIndex, sellIsin = sell, buyIsin = buy,
        sellFundName = sell, buyFundName = buy, fromLevel = 5, toLevel = 3, feeDeltaPercent = 0.0,
        followed = followed, kind = kind
    )

    private fun dismissal(sell: String, buy: String, daysAgo: Long = 0, kind: SuggestionKind = SuggestionKind.RISK_PLAN) = SuggestionDismissal(sellIsin = sell, buyIsin = buy, kind = kind, dismissedAtEpochDay = today.minusDays(daysAgo).toEpochDay())

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
            plan,
            listOf(dismissal("A", "B", daysAgo = SuggestionDismissals.TTL_DAYS + 1)),
            today
        )

        assertEquals(plan, visible)
        assertTrue(dismissed.isEmpty())
    }

    @Test
    fun `ett avstatt billigare alternativ doljs men inte samma par i bytesplanen`() {
        // Sorten bärs av förslaget självt: samma fondpar kan vara både ett riskplansbyte och ett avgiftsbyte.
        val fee = suggestion(1, 0, "A", "B", kind = SuggestionKind.FEE)
        val plan = suggestion(2, 0, "A", "B")

        val (visible, dismissed) = SuggestionDismissals.partition(
            listOf(fee, plan),
            listOf(dismissal("A", "B", kind = SuggestionKind.FEE)),
            today
        )

        assertEquals(listOf(2L), visible.map { it.recordId })
        assertEquals(listOf(1L), dismissed.map { it.recordId })
    }

    @Test
    fun `partition med en valjare delar en lista som bar forslag`() {
        val items = listOf("x" to suggestion(1, 0, "A", "B", kind = SuggestionKind.FEE), "y" to suggestion(2, 0, "C", "D", kind = SuggestionKind.FEE))

        val (visible, dismissed) = SuggestionDismissals.partition(
            items,
            listOf(dismissal("C", "D", kind = SuggestionKind.FEE)),
            today
        ) { it.second }

        assertEquals(listOf("x"), visible.map { it.first })
        assertEquals(listOf("y"), dismissed.map { it.first })
    }

    @Test
    fun `hides galler bara aktiva par av ratt sort och aldrig ett genomfort forslag`() {
        val active = SuggestionDismissals.activeKeys(
            listOf(
                dismissal("A", "B", kind = SuggestionKind.FEE),
                dismissal("C", "D", daysAgo = SuggestionDismissals.TTL_DAYS + 1, kind = SuggestionKind.FEE)
            ),
            today
        )

        assertTrue(SuggestionDismissals.hides(active, SuggestionKind.FEE, "A", "B", followed = false))
        assertFalse(SuggestionDismissals.hides(active, SuggestionKind.FEE, "A", "B", followed = true))
        assertFalse(SuggestionDismissals.hides(active, SuggestionKind.RISK_PLAN, "A", "B", followed = false))
        assertFalse("Utgånget avstående", SuggestionDismissals.hides(active, SuggestionKind.FEE, "C", "D", followed = false))
    }
}
