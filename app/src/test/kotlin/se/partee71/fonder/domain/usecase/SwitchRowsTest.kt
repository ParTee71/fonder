package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import java.time.LocalDate

/**
 * Listan "Byten" på Beslut (BYT-4): ett byte per rad med sitt tillstånd, avslutade i 14 dagar, och
 * antalen som Beslut, "Att besluta" (HEM-13) och flikmärket (NAV-9) läser.
 */
class SwitchRowsTest {

    private val today = LocalDate.of(2026, 10, 13)
    private val todayDay = today.toEpochDay()

    private fun plan(recordId: Long, planIndex: Int, followed: Boolean = false, inProgress: Boolean = false) = SwitchPlanResolver.Suggestion(
        recordId = recordId, planIndex = planIndex, sellIsin = "S$recordId", buyIsin = "K$recordId",
        sellFundName = "Sälj $recordId", buyFundName = "Köp $recordId", fromLevel = 5, toLevel = 3,
        feeDeltaPercent = -0.5, followed = followed, inProgress = inProgress,
    )

    private fun fee(recordId: Long, followed: Boolean = false, inProgress: Boolean = false) = CheaperAlternative(
        suggestion = plan(recordId, 0, followed, inProgress).copy(kind = SuggestionKind.FEE),
        sellFeePercent = 1.4,
        buyFeePercent = 0.4,
    )

    private fun open(id: Long, sourceRecordId: Long? = null, sourcePair: Pair<String, String>? = null, sourceKind: SuggestionKind? = null) =
        OpenSwitchWatch(
            watchId = id, sellFundName = "Fond $id", daysWaiting = 2, candidateCount = 3,
            sourceRecordId = sourceRecordId, sourcePair = sourcePair, sourceKind = sourceKind,
        )

    private fun closed(id: Long, reason: SwitchWatchCloseReason, closedDaysAgo: Long, boughtIsin: String? = null) = SwitchWatch(
        id = id, sellIsin = "SW$id", sellFundName = "Sålt $id", soldAtEpochDay = todayDay - 20,
        closedAtEpochDay = todayDay - closedDaysAgo, closeReason = reason, boughtIsin = boughtIsin,
        candidates = listOf(SwitchWatchCandidate(isin = "KAND", name = "Kandidat A")),
    )

    @Test
    fun `alla tillstand far var sin rad med ratt tillstand`() {
        val decisions = PendingDecisions(
            switchPlan = listOf(plan(1, 0), plan(2, 1, inProgress = true)),
            cheaperAlternatives = listOf(fee(10)),
            dismissedSuggestions = listOf(plan(3, 2)),
            dismissedCheaperAlternatives = listOf(fee(11)),
            openWatches = listOf(open(7, sourceRecordId = 2)),
        )
        val expiredOpen = SwitchWatch(id = 9, sellIsin = "SX", sellFundName = "Gammal", soldAtEpochDay = todayDay - 20)
        val rows = SwitchRows.build(
            decisions,
            closedWatches = listOf(
                expiredOpen,
                closed(4, SwitchWatchCloseReason.KOPT, closedDaysAgo = 1, boughtIsin = "KAND"),
                closed(5, SwitchWatchCloseReason.AVBRUTEN, closedDaysAgo = 2),
                closed(6, SwitchWatchCloseReason.UTGANGEN, closedDaysAgo = 3),
            ),
            today = today,
        )

        assertEquals(listOf(SwitchRowState.SOLD, SwitchRowState.PROPOSED, SwitchRowState.FEE), rows.active.map { it.state })
        assertEquals(
            listOf(
                SwitchRowState.EXPIRED, // öppen men utgången — utan stängningsdag, så överst
                SwitchRowState.BOUGHT,
                SwitchRowState.CANCELLED,
                SwitchRowState.EXPIRED,
                SwitchRowState.DISMISSED,
                SwitchRowState.DISMISSED,
            ),
            rows.closed.map { it.state },
        )
        assertEquals("Kandidat A", (rows.closed[1] as SwitchRow.Closed).boughtFundName)
        assertNull((rows.closed[2] as SwitchRow.Closed).boughtFundName)
    }

    @Test
    fun `ett salt planbyte ersatter planraden - ett byte en rad`() {
        // Samma byte i en nyare inspelning (nytt id, samma fondpar) ska inte heller bli en andra rad.
        val decisions = PendingDecisions(
            switchPlan = listOf(plan(1, 0), plan(2, 1)),
            openWatches = listOf(open(7, sourceRecordId = 99, sourcePair = "S2" to "K2", sourceKind = SuggestionKind.RISK_PLAN)),
        )

        val rows = SwitchRows.build(decisions, emptyList(), today)

        assertEquals(2, rows.active.size)
        val sold = rows.active.first() as SwitchRow.Sold
        assertEquals(SwitchOrigin.PLAN, sold.origin)
        assertEquals(1, sold.planIndex)
        assertEquals(1L, (rows.active[1] as SwitchRow.Suggested).suggestion.recordId)
    }

    @Test
    fun `billigare alternativ foljer samma livscykel - salt ersatter avgiftsraden`() {
        val decisions = PendingDecisions(
            cheaperAlternatives = listOf(fee(10, inProgress = true), fee(11)),
            openWatches = listOf(open(7, sourceRecordId = 10, sourceKind = SuggestionKind.FEE)),
        )

        val rows = SwitchRows.build(decisions, emptyList(), today)

        assertEquals(listOf(SwitchRowState.SOLD, SwitchRowState.FEE), rows.active.map { it.state })
        assertEquals(SwitchOrigin.FEE, (rows.active.first() as SwitchRow.Sold).origin)
        assertEquals(1, rows.feeCount)
        assertEquals(1, rows.soldCount)
    }

    @Test
    fun `en fristaende bevakning ar salt utan forslag och ett kallforslag utanfor planen behaller sin sort`() {
        val decisions = PendingDecisions(
            openWatches = listOf(open(7), open(8, sourceRecordId = 50, sourceKind = SuggestionKind.RISK_PLAN)),
        )

        val rows = SwitchRows.build(decisions, emptyList(), today)

        assertEquals(SwitchOrigin.STANDALONE, (rows.active[0] as SwitchRow.Sold).origin)
        val fromPlan = rows.active[1] as SwitchRow.Sold
        assertEquals(SwitchOrigin.PLAN, fromPlan.origin)
        assertNull(fromPlan.planIndex)
    }

    @Test
    fun `ett kallforslag som gallrats ar inget eget salj`() {
        val rows = SwitchRows.build(PendingDecisions(openWatches = listOf(open(8, sourceRecordId = 50))), emptyList(), today)

        assertEquals(SwitchOrigin.PLAN, (rows.active.single() as SwitchRow.Sold).origin)
    }

    @Test
    fun `en bevakning som just stangts syns bara som avslutad aven om det oppna flodet ligger efter`() {
        val decisions = PendingDecisions(
            switchPlan = listOf(plan(1, 0)),
            openWatches = listOf(open(4, sourceRecordId = 1)),
        )
        val bought = closed(4, SwitchWatchCloseReason.KOPT, closedDaysAgo = 0, boughtIsin = "KAND")

        val rows = SwitchRows.build(decisions, closedWatches = listOf(bought, bought.copy(closedAtEpochDay = null, closeReason = null)), today = today)

        assertTrue(rows.active.none { it is SwitchRow.Sold })
        assertEquals(listOf(SwitchRowState.BOUGHT), rows.closed.map { it.state })
        assertEquals(0, rows.soldCount)
    }

    @Test
    fun `ett avstatt par som anda salts visas inte som avstatt`() {
        val decisions = PendingDecisions(
            dismissedSuggestions = listOf(plan(3, 0)),
            openWatches = listOf(open(7, sourceRecordId = 3)),
        )

        val rows = SwitchRows.build(decisions, emptyList(), today)

        assertEquals(listOf(SwitchRowState.SOLD), rows.active.map { it.state })
        assertTrue(rows.closed.isEmpty())
    }

    @Test
    fun `planens forslag visas i planIndex-ordning efter de salda`() {
        val decisions = PendingDecisions(switchPlan = listOf(plan(2, 1), plan(1, 0)), openWatches = listOf(open(7)))

        val rows = SwitchRows.build(decisions, emptyList(), today)

        assertEquals(listOf("watch-7", "suggestion-1", "suggestion-2"), rows.active.map { it.key })
    }

    @Test
    fun `avslutade visas i 14 dagar efter stangningen och sedan inte`() {
        val rows = SwitchRows.build(
            PendingDecisions(),
            closedWatches = listOf(
                closed(1, SwitchWatchCloseReason.KOPT, closedDaysAgo = SwitchWatchCalc.WATCH_TTL_DAYS),
                closed(2, SwitchWatchCloseReason.KOPT, closedDaysAgo = SwitchWatchCalc.WATCH_TTL_DAYS + 1),
            ),
            today = today,
        )

        assertEquals(listOf("closed-1"), rows.closed.map { it.key })
        assertTrue(rows.isEmpty)
    }

    @Test
    fun `antalet ar foreslaget plus avgift plus salt och kvitterade raknas inte`() {
        val decisions = PendingDecisions(
            switchPlan = listOf(plan(1, 0), plan(2, 1, followed = true)),
            cheaperAlternatives = listOf(fee(10), fee(11, followed = true)),
            openWatches = listOf(open(7), open(8)),
        )

        val rows = SwitchRows.build(decisions, emptyList(), today)

        assertEquals(1, rows.proposedCount)
        assertEquals(1, rows.feeCount)
        assertEquals(2, rows.soldCount)
        assertEquals(4, rows.waitingCount)
        // Samma tal som märket och "Att besluta" läser (NAV-9, HEM-13).
        val withRows = decisions.withSwitches(emptyList(), today)
        assertEquals(rows.waitingCount, withRows.count)
        assertEquals(rows.proposedCount, withRows.openSwitchCount)
        assertEquals(rows.feeCount, withRows.openCheaperAlternativeCount)
        assertEquals(rows.soldCount, withRows.soldCount)
    }

    @Test
    fun `ett kvitterat forslag vet om saljfonden redan bevakas`() {
        val decisions = PendingDecisions(switchPlan = listOf(plan(1, 0, followed = true)), watchedSellIsins = setOf("S1"))

        val row = SwitchRows.build(decisions, emptyList(), today).active.single() as SwitchRow.Suggested

        assertTrue(row.watched)
        assertEquals(0, SwitchRows.build(decisions, emptyList(), today).waitingCount)
    }
}
