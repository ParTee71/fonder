package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.usecase.SwitchOutcomeCalc.Horizon
import se.partee71.fonder.domain.usecase.SwitchOutcomeCalc.SameDayNav

/** Facit-beräkningen för bytesplanen (SET-5, issue #80 och #197) — se [SwitchOutcomeCalc]. */
class SwitchOutcomeCalcTest {

    private fun record(
        id: Long = 1,
        planIndex: Int = 0,
        sellNav: Double = 100.0,
        buyNav: Double = 200.0,
        switchValueKr: Double? = 10_000.0,
        followed: Boolean? = null,
        suggestedAtEpochDay: Long = 20_000,
        navEpochDay: Long? = 20_000,
        sellIsin: String = "SE_SELL",
        buyIsin: String = "SE_BUY"
    ) = SuggestionRecord(
        id = id,
        suggestedAtEpochDay = suggestedAtEpochDay,
        planIndex = planIndex,
        sellIsin = sellIsin,
        buyIsin = buyIsin,
        sellNavAtSuggestion = sellNav,
        buyNavAtSuggestion = buyNav,
        switchValueKr = switchValueKr,
        followed = followed,
        navEpochDay = navEpochDay
    )

    /** Ett NAV-par från dag 20 030 — 30 dagar efter förslaget. */
    private fun nav(sellNav: Double, buyNav: Double, epochDay: Long = 20_030) = SameDayNav(epochDay, sellNav, buyNav)

    private fun prices(fundId: String, vararg points: Pair<Long, Double>) = points.map { (day, nav) -> FundPrice(fundId, day, nav) }

    @Test
    fun `meravkastning nar kopsidan gick battre`() {
        // Sålda fonden +5 %, köpta +12 % → bytet gav 7 procentenheter mer.
        val outcome = SwitchOutcomeCalc.evaluate(record(), nav(sellNav = 105.0, buyNav = 224.0))

        assertTrue(outcome.isEvaluated)
        assertTrue(outcome.isBetter)
        assertEquals(0.05, outcome.sellReturn!!, 1e-9)
        assertEquals(0.12, outcome.buyReturn!!, 1e-9)
        assertEquals(0.07, outcome.excessReturn!!, 1e-9)
        assertEquals(700.0, outcome.excessKr!!, 1e-9)
        assertEquals(20_030L, outcome.measuredAtEpochDay)
        assertEquals(30L, outcome.days)
        assertEquals(Horizon.ONE_TO_SIX_MONTHS, outcome.horizon)
    }

    @Test
    fun `meravkastningen ar negativ nar kopsidan gick samre`() {
        // Rådet ska kunna visa sig ha varit fel — facit är ingen skyltfönstersiffra.
        val outcome = SwitchOutcomeCalc.evaluate(record(), nav(sellNav = 110.0, buyNav = 200.0))

        assertEquals(-0.10, outcome.excessReturn!!, 1e-9)
        assertEquals(-1000.0, outcome.excessKr!!, 1e-9)
        assertFalse(outcome.isBetter)
    }

    @Test
    fun `identisk utveckling ger exakt noll och ar inte battre`() {
        val outcome = SwitchOutcomeCalc.evaluate(record(), nav(sellNav = 110.0, buyNav = 220.0))

        assertEquals(0.0, outcome.excessReturn!!, 1e-9)
        assertEquals(0.0, outcome.excessKr!!, 1e-9)
        assertFalse(outcome.isBetter)
    }

    @Test
    fun `utan belopp visas procent men inget kronbelopp`() {
        // Rader inspelade före issue #75 vet inte vilket belopp bytet gällde — ett påhittat
        // belopp vore värre än inget.
        val outcome = SwitchOutcomeCalc.evaluate(record(switchValueKr = null), nav(sellNav = 105.0, buyNav = 224.0))

        assertTrue(outcome.isEvaluated)
        assertEquals(0.07, outcome.excessReturn!!, 1e-9)
        assertNull(outcome.excessKr)
    }

    @Test
    fun `utan nav-par ger ej utvarderat inte noll`() {
        val outcome = SwitchOutcomeCalc.evaluate(record(), nav = null)

        assertFalse(outcome.isEvaluated)
        assertNull(outcome.excessReturn)
        assertNull(outcome.excessKr)
        assertNull(outcome.measuredAtEpochDay)
        assertNull(outcome.days)
        assertNull(outcome.horizon)
    }

    @Test
    fun `nav-utgangslage pa noll behandlas som saknad data i stallet for division`() {
        val outcome = SwitchOutcomeCalc.evaluate(record(sellNav = 0.0), nav(sellNav = 105.0, buyNav = 224.0))

        assertFalse(outcome.isEvaluated)
        assertNull(outcome.sellReturn)
    }

    @Test
    fun `matdagen ar senaste dag dar bada fonderna har en kurs`() {
        // Issue #197: säljfondens kurs uppdateras varje körning, köpfondens bara ibland. Säljfonden har
        // kurs dag 20 040, köpfonden bara till 20 035 — båda mäts på 20 035, aldrig på var sin dag.
        val sell = prices("SHB1", 20_000L to 100.0, 20_035L to 104.0, 20_040L to 110.0)
        val buy = prices("SE_BUY", 20_000L to 200.0, 20_035L to 212.0)

        val outcome = SwitchOutcomeCalc.evaluate(record(), sell, buy)

        assertEquals(20_035L, outcome.measuredAtEpochDay)
        assertEquals(0.04, outcome.sellReturn!!, 1e-9)
        assertEquals(0.06, outcome.buyReturn!!, 1e-9)
        assertEquals(0.02, outcome.excessReturn!!, 1e-9)
    }

    @Test
    fun `ingen gemensam dag efter forslaget ger ej utvarderat`() {
        // Den enda gemensamma dagen är förslagets NAV-dag: noll dagar är inget utfall.
        val sell = prices("SHB1", 20_000L to 100.0, 20_003L to 101.0)
        val buy = prices("SE_BUY", 20_000L to 200.0, 20_004L to 210.0)

        assertFalse(SwitchOutcomeCalc.evaluate(record(), sell, buy).isEvaluated)
    }

    @Test
    fun `matdagen ar tidigast forslagsdagen och utgangslaget nav-dagen`() {
        // Förslaget gjordes en söndag (20 002) på fredagens kurser (20 000). En gemensam kurs på
        // lördagen (20 001) räknas inte – den ligger före förslaget – men måndagens gör det.
        val rec = record(suggestedAtEpochDay = 20_002, navEpochDay = 20_000)
        val sell = prices("SHB1", 20_000L to 100.0, 20_001L to 101.0, 20_003L to 102.0)
        val buy = prices("SE_BUY", 20_000L to 200.0, 20_001L to 202.0, 20_003L to 206.0)

        val outcome = SwitchOutcomeCalc.evaluate(rec, sell, buy)

        assertEquals(20_003L, outcome.measuredAtEpochDay)
        assertEquals(3L, outcome.days)
        assertEquals(0.01, outcome.excessReturn!!, 1e-9)
        assertFalse(SwitchOutcomeCalc.evaluate(rec, sell.dropLast(1), buy.dropLast(1)).isEvaluated)
    }

    @Test
    fun `gammal rad utan nav-dag mats fran forslagsdagen`() {
        val rec = record(navEpochDay = null, suggestedAtEpochDay = 20_000)
        val sell = prices("SHB1", 20_000L to 100.0, 20_200L to 100.0)
        val buy = prices("SE_BUY", 20_000L to 200.0, 20_200L to 220.0)

        val outcome = SwitchOutcomeCalc.evaluate(rec, sell, buy)

        assertEquals(20_000L, rec.navBaseEpochDay)
        assertEquals(200L, outcome.days)
        assertEquals(Horizon.OVER_SIX_MONTHS, outcome.horizon)
    }

    @Test
    fun `latestCommonNav hoppar over kurser pa noll`() {
        val sell = prices("A", 1L to 10.0, 2L to 0.0)
        val buy = prices("B", 1L to 20.0, 2L to 21.0)

        assertEquals(SameDayNav(1L, 10.0, 20.0), SwitchOutcomeCalc.latestCommonNav(sell, buy))
        assertNull(SwitchOutcomeCalc.latestCommonNav(sell, buy, fromEpochDay = 2L))
    }

    @Test
    fun `horisontbanden`() {
        assertEquals(Horizon.UNDER_ONE_MONTH, Horizon.of(1))
        assertEquals(Horizon.UNDER_ONE_MONTH, Horizon.of(29))
        assertEquals(Horizon.ONE_TO_SIX_MONTHS, Horizon.of(30))
        assertEquals(Horizon.ONE_TO_SIX_MONTHS, Horizon.of(182))
        assertEquals(Horizon.OVER_SIX_MONTHS, Horizon.of(183))
    }

    @Test
    fun `arstakt och median`() {
        assertEquals(0.10, SwitchOutcomeCalc.annualize(0.21, 730)!!, 2e-4)
        assertEquals(1.21.pow(365.2425 / 730.0) - 1.0, SwitchOutcomeCalc.annualize(0.21, 730)!!, 1e-12)
        assertEquals(-1.0, SwitchOutcomeCalc.annualize(-1.2, 400)!!, 0.0)
        assertNull(SwitchOutcomeCalc.annualize(0.1, 0))
        assertEquals(2.0, SwitchOutcomeCalc.median(listOf(3.0, 1.0, 2.0))!!, 0.0)
        assertEquals(2.5, SwitchOutcomeCalc.median(listOf(4.0, 1.0, 3.0, 2.0))!!, 0.0)
        assertNull(SwitchOutcomeCalc.median(emptyList()))
    }

    @Test
    fun `arstakten kompounderar den relativa utvecklingen, inte meravkastningen`() {
        // Säljfonden +50 %, köpfonden +65 %: meravkastningen +15 pp, men köpfonden är bara 10 % före
        // säljfonden. Att kompoundera +15 pp hade överdrivit utfallet.
        val outcome = SwitchOutcomeCalc.evaluate(record(), nav(sellNav = 150.0, buyNav = 330.0, epochDay = 20_000L + 730))

        assertEquals(0.15, outcome.excessReturn!!, 1e-12)
        assertEquals(0.10, outcome.relativeReturn!!, 1e-12)
        assertEquals(1.10.pow(365.2425 / 730.0) - 1.0, outcome.annualizedRelativeReturn!!, 1e-12)
        assertNull(SwitchOutcomeCalc.evaluate(record(), nav = null).relativeReturn)
    }

    @Test
    fun `utgangslage saknas nar en NAV vid forslaget ar noll eller mindre`() {
        // Skärmen ska då inte säga "väntar på kurs": raden blir aldrig utvärderad.
        val noBase = SwitchOutcomeCalc.evaluate(record(sellNav = 0.0), nav(sellNav = 105.0, buyNav = 224.0))
        assertFalse(noBase.isEvaluated)
        assertFalse(noBase.hasBaseNav)
        assertFalse(SwitchOutcomeCalc.evaluate(record(buyNav = -1.0), nav = null).hasBaseNav)
        assertTrue(SwitchOutcomeCalc.evaluate(record(), nav = null).hasBaseNav)
    }

    @Test
    fun `summeringen ger traffandel median och kronsumma`() {
        val outcomes = listOf(
            SwitchOutcomeCalc.evaluate(record(id = 1), nav(sellNav = 105.0, buyNav = 224.0)), // +7 pp, +700 kr
            SwitchOutcomeCalc.evaluate(record(id = 2), nav(sellNav = 110.0, buyNav = 200.0)) // −10 pp, −1000 kr
        )

        val summary = SwitchOutcomeCalc.summarize(outcomes)

        assertEquals(1, summary.betterCount)
        assertEquals(-0.015, summary.medianExcessReturn!!, 1e-9)
        assertEquals(-300.0, summary.totalExcessKr!!, 1e-9)
        assertEquals(2, summary.evaluatedCount)
        assertEquals(2, summary.totalCount)
    }

    @Test
    fun `ej utvarderade rader raknas men aldrig i traffandelen`() {
        // Att räkna en okänd rad som 0 hade tystat skillnaden mellan "gav ingenting" och
        // "vi vet inte än" (ANA-4-principen).
        val outcomes = listOf(
            SwitchOutcomeCalc.evaluate(record(id = 1), nav(sellNav = 105.0, buyNav = 224.0)),
            SwitchOutcomeCalc.evaluate(record(id = 2), nav = null)
        )

        val summary = SwitchOutcomeCalc.summarize(outcomes)

        assertEquals(1, summary.betterCount)
        assertEquals(0.07, summary.medianExcessReturn!!, 1e-9)
        assertEquals(700.0, summary.totalExcessKr!!, 1e-9)
        assertEquals(1, summary.evaluatedCount)
        assertEquals(2, summary.totalCount)
    }

    @Test
    fun `helt outvarderad uppsattning ger null i stallet for NaN`() {
        val summary = SwitchOutcomeCalc.summarize(listOf(SwitchOutcomeCalc.evaluate(record(), nav = null)))

        assertNull(summary.medianExcessReturn)
        assertNull(summary.totalExcessKr)
        assertEquals(0, summary.betterCount)
        assertEquals(0, summary.evaluatedCount)
        assertEquals(1, summary.totalCount)
        assertTrue(SwitchOutcomeCalc.byHorizon(listOf(SwitchOutcomeCalc.evaluate(record(), nav = null))).isEmpty())
    }

    @Test
    fun `kronsumman utelamnar rader utan belopp men traffandelen raknar dem`() {
        val outcomes = listOf(
            SwitchOutcomeCalc.evaluate(record(id = 1), nav(sellNav = 105.0, buyNav = 224.0)),
            SwitchOutcomeCalc.evaluate(record(id = 2, switchValueKr = null), nav(sellNav = 110.0, buyNav = 200.0))
        )

        val summary = SwitchOutcomeCalc.summarize(outcomes)

        assertEquals(1, summary.betterCount)
        assertEquals(2, summary.evaluatedCount)
        assertEquals(700.0, summary.totalExcessKr!!, 1e-9)
    }

    @Test
    fun `traffandelens riktning - majoritet battre, majoritet samre och exakt halften`() {
        // Färgen på "x av n bättre": vinst vid majoritet bättre, förlust vid majoritet sämre, neutral vid lika.
        assertEquals(1, SwitchOutcomeCalc.Summary(betterCount = 2, evaluatedCount = 3).hitRateSign)
        assertEquals(-1, SwitchOutcomeCalc.Summary(betterCount = 1, evaluatedCount = 3).hitRateSign)
        assertEquals(0, SwitchOutcomeCalc.Summary(betterCount = 1, evaluatedCount = 2).hitRateSign)
        assertEquals(0, SwitchOutcomeCalc.Summary(betterCount = 2, evaluatedCount = 4).hitRateSign)
        assertEquals(-1, SwitchOutcomeCalc.Summary(betterCount = 0, evaluatedCount = 1).hitRateSign)
        assertEquals(0, SwitchOutcomeCalc.Summary(betterCount = 0, evaluatedCount = 0).hitRateSign)
    }

    @Test
    fun `issuets exempel - snittet doljer att tva av tre var samre`() {
        // Issue #197: A (300 dagar, +12 pp), B och C (3 dagar, −1 pp). Ett enkelt snitt gav +3,3 pp.
        val a = SwitchOutcomeCalc.evaluate(record(id = 1), nav(sellNav = 100.0, buyNav = 224.0, epochDay = 20_300))
        val b = SwitchOutcomeCalc.evaluate(record(id = 2), nav(sellNav = 101.0, buyNav = 200.0, epochDay = 20_003))
        val c = SwitchOutcomeCalc.evaluate(record(id = 3), nav(sellNav = 101.0, buyNav = 200.0, epochDay = 20_003))

        val summary = SwitchOutcomeCalc.summarize(listOf(a, b, c))
        val bands = SwitchOutcomeCalc.byHorizon(listOf(a, b, c))

        assertEquals(1, summary.betterCount)
        assertEquals(3, summary.evaluatedCount)
        assertEquals(listOf(Horizon.UNDER_ONE_MONTH, Horizon.OVER_SIX_MONTHS), bands.map { it.horizon })
        assertEquals(0, bands[0].betterCount)
        assertEquals(-0.01, bands[0].medianExcessReturn, 1e-9)
        assertTrue(bands[1].isAnnualized)
        // Årstakten räknas på den relativa utvecklingen (2,24 / 2,00) / (1,00 / 1,00) − 1 = +12 %.
        assertEquals(1.12.pow(365.2425 / 300.0) - 1.0, bands[1].medianExcessReturn, 1e-12)
    }

    @Test
    fun `utfall per plats i planen sorteras stigande`() {
        val outcomes = listOf(
            SwitchOutcomeCalc.evaluate(record(id = 1, planIndex = 1), nav(sellNav = 110.0, buyNav = 200.0)), // −10 pp
            SwitchOutcomeCalc.evaluate(record(id = 2, planIndex = 0), nav(sellNav = 105.0, buyNav = 224.0)) // +7 pp
        )

        val byIndex = SwitchOutcomeCalc.byPlanIndex(outcomes)

        assertEquals(listOf(0, 1), byIndex.map { it.planIndex })
        assertEquals(1, byIndex[0].summary.betterCount)
        assertEquals(0.07, byIndex[0].summary.medianExcessReturn!!, 1e-9)
        assertEquals(0, byIndex[1].summary.betterCount)
        assertEquals(-0.10, byIndex[1].summary.medianExcessReturn!!, 1e-9)
    }

    @Test
    fun `matchar fondmatte golden for facit`() {
        // python tools/fondmatte/fondmatte.py golden tools/fondmatte/facit.json → facit (issue #197).
        fun day(iso: String) = LocalDate.parse(iso).toEpochDay()
        val series = mapOf(
            "S1" to listOf("2025-02-28" to 100.0, "2025-12-26" to 105.0, "2025-12-29" to 106.0),
            "K1" to listOf("2025-02-28" to 50.0, "2025-12-26" to 58.5),
            "S2" to listOf("2025-12-23" to 200.0, "2025-12-26" to 202.0),
            "K2" to listOf("2025-12-23" to 80.0, "2025-12-26" to 80.0),
            "S3" to listOf("2025-12-23" to 10.0, "2025-12-26" to 10.2),
            "K3" to listOf("2025-12-23" to 20.0, "2025-12-26" to 20.2),
            "S4" to listOf("2025-09-01" to 100.0, "2025-12-01" to 103.0, "2025-12-29" to 104.0),
            "K4" to listOf("2025-09-01" to 100.0, "2025-12-01" to 108.0),
            "S5" to listOf("2025-12-29" to 30.0),
            "K5" to listOf("2025-12-29" to 60.0),
            "S6" to listOf("2024-12-02" to 100.0, "2025-12-29" to 130.0),
            "K6" to listOf("2024-12-02" to 100.0, "2025-12-29" to 110.0),
            "S7" to listOf("2025-10-01" to 50.0, "2025-12-29" to 51.0),
            "K7" to listOf("2025-10-01" to 40.0, "2025-12-29" to 42.0)
        ).mapValues { (fund, points) -> points.map { (date, nav) -> FundPrice(fund, day(date), nav) } }

        data class Fixture(val id: Long, val suggested: String, val navDate: String?, val n: Int, val sellNav: Double, val buyNav: Double, val valueKr: Double?, val followed: Boolean)
        val fixtures = listOf(
            Fixture(1, "2025-03-02", "2025-02-28", 1, 100.0, 50.0, 10_000.0, true),
            Fixture(2, "2025-12-23", "2025-12-23", 2, 200.0, 80.0, 5_000.0, false),
            Fixture(3, "2025-12-23", "2025-12-23", 3, 10.0, 20.0, 8_000.0, true),
            Fixture(4, "2025-09-01", null, 4, 100.0, 100.0, null, true),
            Fixture(5, "2025-12-29", "2025-12-29", 5, 30.0, 60.0, 2_000.0, false),
            Fixture(6, "2024-12-02", "2024-12-02", 6, 100.0, 100.0, 20_000.0, false),
            Fixture(7, "2025-10-01", "2025-10-01", 7, 50.0, 40.0, 4_000.0, true)
        )
        val outcomes = fixtures.map { f ->
            val rec = record(
                id = f.id, sellNav = f.sellNav, buyNav = f.buyNav, switchValueKr = f.valueKr, followed = f.followed,
                suggestedAtEpochDay = day(f.suggested), navEpochDay = f.navDate?.let(::day), sellIsin = "S${f.n}", buyIsin = "K${f.n}"
            )
            SwitchOutcomeCalc.evaluate(rec, series.getValue("S${f.n}"), series.getValue("K${f.n}"))
        }

        assertEquals(
            listOf("2025-12-26", "2025-12-26", "2025-12-26", "2025-12-01", null, "2025-12-29", "2025-12-29"),
            outcomes.map { o -> o.measuredAtEpochDay?.let { LocalDate.ofEpochDay(it).toString() } }
        )
        assertEquals(listOf(301L, 3L, 3L, 91L, null, 392L, 89L), outcomes.map { it.days })
        assertEquals(0.11999999999999988, outcomes[0].excessReturn!!, 1e-9)
        assertEquals(0.1142857142857141, outcomes[0].relativeReturn!!, 1e-9)
        assertEquals(0.14032081008877428, outcomes[0].annualizedRelativeReturn!!, 1e-9)
        assertEquals(-0.19999999999999996, outcomes[5].excessReturn!!, 1e-9)
        assertEquals(-0.15384615384615385, outcomes[5].relativeReturn!!, 1e-9)
        assertEquals(-0.144142297306877, outcomes[5].annualizedRelativeReturn!!, 1e-9)
        assertEquals(0.030000000000000027, outcomes[6].excessReturn!!, 1e-9)

        val all = SwitchOutcomeCalc.summarize(outcomes)
        assertEquals(listOf(3, 6, 7), listOf(all.betterCount, all.evaluatedCount, all.totalCount))
        assertEquals(0.010000000000000009, all.medianExcessReturn!!, 1e-9)
        assertEquals(-2810.0, all.totalExcessKr!!, 1e-9)

        val followed = SwitchOutcomeCalc.summarize(outcomes.filter { it.record.followed == true })
        assertEquals(listOf(3, 4, 4), listOf(followed.betterCount, followed.evaluatedCount, followed.totalCount))
        assertEquals(0.040000000000000036, followed.medianExcessReturn!!, 1e-9)
        assertEquals(1239.9999999999989, followed.totalExcessKr!!, 1e-9)

        val bands = SwitchOutcomeCalc.byHorizon(outcomes)
        assertEquals(listOf(Horizon.UNDER_ONE_MONTH, Horizon.ONE_TO_SIX_MONTHS, Horizon.OVER_SIX_MONTHS), bands.map { it.horizon })
        assertEquals(listOf(2, 2, 2), bands.map { it.count })
        assertEquals(listOf(0, 2, 1), bands.map { it.betterCount })
        assertEquals(-0.010000000000000009, bands[0].medianExcessReturn, 1e-9)
        assertEquals(0.040000000000000036, bands[1].medianExcessReturn, 1e-9)
        assertEquals(-0.0019107436090513574, bands[2].medianExcessReturn, 1e-9)
    }
}
