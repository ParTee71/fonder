package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import java.time.LocalDate

/** Utvecklingen sedan säljdagen, rangordningen och färskhetsgränsen (ANA-12, issue #114). */
class SwitchWatchCalcTest {

    private val soldAt = LocalDate.of(2026, 8, 3)

    private fun candidate(
        isin: String = "SE_A",
        navAtStart: Double? = 100.0,
        navAtStartEpochDay: Long? = soldAt.toEpochDay(),
        position: Int = 0,
    ) = SwitchWatchCandidate(
        id = 1,
        watchId = 1,
        isin = isin,
        name = "Fond $isin",
        navAtStart = navAtStart,
        navAtStartEpochDay = navAtStartEpochDay,
        position = position,
    )

    private fun watch(
        soldAtEpochDay: Long = soldAt.toEpochDay(),
        closedAtEpochDay: Long? = null,
    ) = SwitchWatch(
        id = 1,
        sellIsin = "SE_SALJ",
        sellFundName = "Såld fond",
        soldAtEpochDay = soldAtEpochDay,
        proceedsKr = 10_000.0,
        closedAtEpochDay = closedAtEpochDay,
        closeReason = closedAtEpochDay?.let { SwitchWatchCloseReason.KOPT },
    )

    @Test
    fun `utvecklingen mats fran saljdagen, i bade procent och kronor`() {
        val outcome = SwitchWatchCalc.outcome(
            candidate = candidate(navAtStart = 100.0),
            latestNav = 103.5,
            proceedsKr = 10_000.0,
            soldAtEpochDay = soldAt.toEpochDay(),
        )

        assertEquals(0.035, outcome.changeFraction!!, 1e-9)
        assertEquals(350.0, outcome.changeKr!!, 1e-9)
        assertFalse(outcome.partial)
    }

    @Test
    fun `okand nollpunkt eller kurs ger ingen utveckling — aldrig noll`() {
        // Noll läses som "stod still", vilket är ett påstående appen inte kan göra när talet
        // saknas (ANA-4-principen). Raden ska visas som ej utvärderad.
        val utanNollpunkt = SwitchWatchCalc.outcome(candidate(navAtStart = null), 103.5, 10_000.0, soldAt.toEpochDay())
        val utanKurs = SwitchWatchCalc.outcome(candidate(navAtStart = 100.0), null, 10_000.0, soldAt.toEpochDay())

        assertNull(utanNollpunkt.changeFraction)
        assertNull(utanKurs.changeFraction)
        assertFalse(utanNollpunkt.isEvaluated)
        assertFalse(utanKurs.isEvaluated)
    }

    @Test
    fun `en nollpunkt pa noll ger ingen utveckling i stallet for en oandlig`() {
        val outcome = SwitchWatchCalc.outcome(candidate(navAtStart = 0.0), 103.5, 10_000.0, soldAt.toEpochDay())

        assertNull(outcome.changeFraction)
    }

    @Test
    fun `okant belopp ger procent men inga kronor`() {
        val outcome = SwitchWatchCalc.outcome(candidate(navAtStart = 100.0), 110.0, null, soldAt.toEpochDay())

        assertEquals(0.10, outcome.changeFraction!!, 1e-9)
        assertNull(outcome.changeKr)
    }

    @Test
    fun `en nollpunkt ankrad efter saljdagen markeras som delvis`() {
        val outcome = SwitchWatchCalc.outcome(
            candidate = candidate(navAtStartEpochDay = soldAt.toEpochDay() + 2),
            latestNav = 110.0,
            proceedsKr = 10_000.0,
            soldAtEpochDay = soldAt.toEpochDay(),
        )

        assertTrue(outcome.partial)
    }

    @Test
    fun `rangordningen satter bast forst och ej utvarderade sist utan att tappa dem`() {
        val outcomes = listOf(
            SwitchWatchCalc.outcome(candidate("SE_A", position = 0), latestNav = 101.0, proceedsKr = null, soldAtEpochDay = soldAt.toEpochDay()),
            SwitchWatchCalc.outcome(candidate("SE_B", navAtStart = null, position = 1), latestNav = null, proceedsKr = null, soldAtEpochDay = soldAt.toEpochDay()),
            SwitchWatchCalc.outcome(candidate("SE_C", position = 2), latestNav = 105.0, proceedsKr = null, soldAtEpochDay = soldAt.toEpochDay()),
        )

        val ranked = SwitchWatchCalc.ranked(outcomes)

        assertEquals(listOf("SE_C", "SE_A", "SE_B"), ranked.map { it.candidate.isin })
    }

    @Test
    fun `dagar i vantan raknas fran saljdagen och blir aldrig negativ`() {
        assertEquals(3, SwitchWatchCalc.daysWaiting(watch(), soldAt.plusDays(3)))
        assertEquals(0, SwitchWatchCalc.daysWaiting(watch(), soldAt.minusDays(1)))
    }

    @Test
    fun `TTL-gransen gar exakt vid fjortonde dygnet`() {
        assertFalse(SwitchWatchCalc.isExpired(watch(), soldAt.plusDays(SwitchWatchCalc.WATCH_TTL_DAYS)))
        assertTrue(SwitchWatchCalc.isExpired(watch(), soldAt.plusDays(SwitchWatchCalc.WATCH_TTL_DAYS + 1)))
    }

    @Test
    fun `en stangd bevakning ar aldrig utgangen`() {
        // Den har ett avslut, och att räkna om det till "utgången" hade skrivit över vad
        // användaren faktiskt gjorde.
        val closed = watch(closedAtEpochDay = soldAt.plusDays(2).toEpochDay())

        assertFalse(SwitchWatchCalc.isExpired(closed, soldAt.plusDays(60)))
    }

    @Test
    fun `nollpunkten ankras pa saljdagen nar den finns`() {
        val history = listOf(
            soldAt.minusDays(1).toEpochDay() to 99.0,
            soldAt.toEpochDay() to 100.0,
            soldAt.plusDays(1).toEpochDay() to 101.0,
        )

        assertEquals(soldAt.toEpochDay() to 100.0, SwitchWatchCalc.anchor(history, soldAt.toEpochDay()))
    }

    @Test
    fun `saknas saljdagen ankras forsta dagen efter — aldrig en dag fore`() {
        // En kurs från dagen innan hade mätt en utveckling som delvis skedde medan pengarna
        // fortfarande låg i den sålda fonden.
        val history = listOf(
            soldAt.minusDays(1).toEpochDay() to 99.0,
            soldAt.plusDays(2).toEpochDay() to 102.0,
        )

        assertEquals(soldAt.plusDays(2).toEpochDay() to 102.0, SwitchWatchCalc.anchor(history, soldAt.toEpochDay()))
    }

    @Test
    fun `en historik som slutar fore saljdagen ger ingen nollpunkt`() {
        val history = listOf(soldAt.minusDays(5).toEpochDay() to 95.0)

        assertNull(SwitchWatchCalc.anchor(history, soldAt.toEpochDay()))
    }
}
