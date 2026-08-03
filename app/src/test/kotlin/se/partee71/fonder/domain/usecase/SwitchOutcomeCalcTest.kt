package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.SuggestionRecord

/** Facit-beräkningen för bytesplanen (SET-5, issue #80) — se [SwitchOutcomeCalc]. */
class SwitchOutcomeCalcTest {

    private fun record(
        id: Long = 1,
        planIndex: Int = 0,
        sellNav: Double = 100.0,
        buyNav: Double = 200.0,
        switchValueKr: Double? = 10_000.0,
        followed: Boolean? = null,
    ) = SuggestionRecord(
        id = id,
        suggestedAtEpochDay = 20_000,
        planIndex = planIndex,
        sellIsin = "SE_SELL",
        buyIsin = "SE_BUY",
        sellNavAtSuggestion = sellNav,
        buyNavAtSuggestion = buyNav,
        switchValueKr = switchValueKr,
        followed = followed,
    )

    @Test
    fun `meravkastning nar kopsidan gick battre`() {
        // Sålda fonden +5 %, köpta +12 % → bytet gav 7 procentenheter mer.
        val outcome = SwitchOutcomeCalc.evaluate(record(), sellNavNow = 105.0, buyNavNow = 224.0)

        assertTrue(outcome.isEvaluated)
        assertEquals(0.05, outcome.sellReturn!!, 1e-9)
        assertEquals(0.12, outcome.buyReturn!!, 1e-9)
        assertEquals(0.07, outcome.excessReturn!!, 1e-9)
        assertEquals(700.0, outcome.excessKr!!, 1e-9)
    }

    @Test
    fun `meravkastningen ar negativ nar kopsidan gick samre`() {
        // Rådet ska kunna visa sig ha varit fel — facit är ingen skyltfönstersiffra.
        val outcome = SwitchOutcomeCalc.evaluate(record(), sellNavNow = 110.0, buyNavNow = 200.0)

        assertEquals(-0.10, outcome.excessReturn!!, 1e-9)
        assertEquals(-1000.0, outcome.excessKr!!, 1e-9)
    }

    @Test
    fun `identisk utveckling ger exakt noll`() {
        val outcome = SwitchOutcomeCalc.evaluate(record(), sellNavNow = 110.0, buyNavNow = 220.0)

        assertEquals(0.0, outcome.excessReturn!!, 1e-9)
        assertEquals(0.0, outcome.excessKr!!, 1e-9)
    }

    @Test
    fun `utan belopp visas procent men inget kronbelopp`() {
        // Rader inspelade före issue #75 vet inte vilket belopp bytet gällde — ett påhittat
        // belopp vore värre än inget.
        val outcome = SwitchOutcomeCalc.evaluate(record(switchValueKr = null), sellNavNow = 105.0, buyNavNow = 224.0)

        assertTrue(outcome.isEvaluated)
        assertEquals(0.07, outcome.excessReturn!!, 1e-9)
        assertNull(outcome.excessKr)
    }

    @Test
    fun `saknad kurs pa endera sidan ger ej utvarderat inte noll`() {
        val utanKop = SwitchOutcomeCalc.evaluate(record(), sellNavNow = 105.0, buyNavNow = null)
        val utanSalj = SwitchOutcomeCalc.evaluate(record(), sellNavNow = null, buyNavNow = 224.0)

        assertFalse(utanKop.isEvaluated)
        assertNull(utanKop.excessReturn)
        assertNull(utanKop.excessKr)
        assertEquals(0.05, utanKop.sellReturn!!, 1e-9)

        assertFalse(utanSalj.isEvaluated)
        assertNull(utanSalj.excessReturn)
    }

    @Test
    fun `nav-utgangslage pa noll behandlas som saknad data i stallet for division`() {
        val outcome = SwitchOutcomeCalc.evaluate(record(sellNav = 0.0), sellNavNow = 105.0, buyNavNow = 224.0)

        assertFalse(outcome.isEvaluated)
        assertNull(outcome.sellReturn)
    }

    @Test
    fun `summeringen snittar procenten och summerar kronorna`() {
        val outcomes = listOf(
            SwitchOutcomeCalc.evaluate(record(id = 1), sellNavNow = 105.0, buyNavNow = 224.0), // +7 pp, +700 kr
            SwitchOutcomeCalc.evaluate(record(id = 2), sellNavNow = 110.0, buyNavNow = 200.0), // −10 pp, −1000 kr
        )

        val summary = SwitchOutcomeCalc.summarize(outcomes)

        assertEquals(-0.015, summary.averageExcessReturn!!, 1e-9)
        assertEquals(-300.0, summary.totalExcessKr!!, 1e-9)
        assertEquals(2, summary.evaluatedCount)
        assertEquals(2, summary.totalCount)
    }

    @Test
    fun `ej utvarderade rader raknas men drar aldrig ner snittet`() {
        // Att räkna en okänd rad som 0 hade tystat skillnaden mellan "gav ingenting" och
        // "vi vet inte än" (ANA-4-principen).
        val outcomes = listOf(
            SwitchOutcomeCalc.evaluate(record(id = 1), sellNavNow = 105.0, buyNavNow = 224.0),
            SwitchOutcomeCalc.evaluate(record(id = 2), sellNavNow = null, buyNavNow = null),
        )

        val summary = SwitchOutcomeCalc.summarize(outcomes)

        assertEquals(0.07, summary.averageExcessReturn!!, 1e-9)
        assertEquals(700.0, summary.totalExcessKr!!, 1e-9)
        assertEquals(1, summary.evaluatedCount)
        assertEquals(2, summary.totalCount)
    }

    @Test
    fun `helt outvarderad uppsattning ger null i stallet for NaN`() {
        val summary = SwitchOutcomeCalc.summarize(
            listOf(SwitchOutcomeCalc.evaluate(record(), sellNavNow = null, buyNavNow = null)),
        )

        assertNull(summary.averageExcessReturn)
        assertNull(summary.totalExcessKr)
        assertEquals(0, summary.evaluatedCount)
        assertEquals(1, summary.totalCount)
    }

    @Test
    fun `kronsumman utelamnar rader utan belopp men snittet raknar dem`() {
        val outcomes = listOf(
            SwitchOutcomeCalc.evaluate(record(id = 1), sellNavNow = 105.0, buyNavNow = 224.0),
            SwitchOutcomeCalc.evaluate(record(id = 2, switchValueKr = null), sellNavNow = 110.0, buyNavNow = 200.0),
        )

        val summary = SwitchOutcomeCalc.summarize(outcomes)

        assertEquals(-0.015, summary.averageExcessReturn!!, 1e-9)
        assertEquals(700.0, summary.totalExcessKr!!, 1e-9)
    }

    @Test
    fun `snitt per plats i planen sorteras stigande`() {
        val outcomes = listOf(
            SwitchOutcomeCalc.evaluate(record(id = 1, planIndex = 1), sellNavNow = 110.0, buyNavNow = 200.0), // −10 pp
            SwitchOutcomeCalc.evaluate(record(id = 2, planIndex = 0), sellNavNow = 105.0, buyNavNow = 224.0), // +7 pp
        )

        val byIndex = SwitchOutcomeCalc.byPlanIndex(outcomes)

        assertEquals(listOf(0, 1), byIndex.map { it.planIndex })
        assertEquals(0.07, byIndex[0].summary.averageExcessReturn!!, 1e-9)
        assertEquals(-0.10, byIndex[1].summary.averageExcessReturn!!, 1e-9)
    }
}
