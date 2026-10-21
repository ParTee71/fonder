package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MovingAverageCalc] — 200-dagarssnittet (200 handelsdagar = 290 kalenderdagar) som både S2 (ANA-2) och kursdiagrammets snittlinje (ANA-14) använder. */
class MovingAverageCalcTest {

    private val window = MovingAverageCalc.WINDOW_DAYS

    /** En kurs per kalenderdag, dag 0…[lastDay], NAV = 100 + dag. */
    private fun daily(lastDay: Long) = (0L..lastDay).map { it to 100.0 + it }

    @Test
    fun `exakt 290 dagars historik ger ett snitt for sista dagen och inget fore`() {
        val points = daily(window)

        val series = MovingAverageCalc.series(points)

        assertEquals(listOf(window), series.map { it.first })
        // Medel av 100 … 390 = 245.
        assertEquals(245.0, series.single().second, 1e-9)
    }

    @Test
    fun `kortare historik an fonstret ger ingen serie och inget snitt`() {
        val points = daily(window - 1)

        assertTrue(MovingAverageCalc.series(points).isEmpty())
        assertNull(MovingAverageCalc.averageAt(points, window - 1))
        assertTrue(MovingAverageCalc.series(emptyList()).isEmpty())
    }

    @Test
    fun `fonstret ar 290 kalenderdagar oavsett helgluckor`() {
        // Bara vardagar har kurs. Första snittet kommer först när historiken nått 290 dagar bakåt,
        // och snittet är medel av vardagskurserna i [d − 290, d] (~207 punkter), inte 200 punkter.
        var day = java.time.LocalDate.of(2025, 1, 1)
        val points = mutableListOf<Pair<Long, Double>>()
        while (points.size < 400) {
            if (day.dayOfWeek != java.time.DayOfWeek.SATURDAY && day.dayOfWeek != java.time.DayOfWeek.SUNDAY) points += day.toEpochDay() to 100.0 + points.size
            day = day.plusDays(1)
        }
        val start = points.first().first

        val series = MovingAverageCalc.series(points)

        assertTrue(series.first().first - start >= 290)
        assertTrue(points.any { it.first < series.first().first && it.first - start < 290 })
        series.forEach { (d, average) ->
            assertEquals(points.filter { it.first in (d - 290)..d }.map { it.second }.average(), average, 1e-9)
        }
    }

    @Test
    fun `gles historik med veckopunkter - snittet raknas pa punkterna inom 290 dagar`() {
        // En kurs i veckan: fönstret rymmer ~42 punkter, inte 200.
        val points = (0L..700L step 7).map { it to 100.0 + it }

        val series = MovingAverageCalc.series(points)

        assertEquals(points.filter { it.first >= 290 }.map { it.first }, series.map { it.first })
        // Dag 294: kurserna på dag 7·k ∈ [4, 294] → k = 1…42 → NAV 107 … 394 → medel 250,5.
        assertEquals(250.5, series.first { it.first == 294L }.second, 1e-9)
        assertNull(MovingAverageCalc.averageAt(points, 287))
    }

    @Test
    fun `matchar fondmatte golden for sma_200`() {
        // tools/fondmatte/sma.json → funds.A.sma_200: 400 vardagar från 2025-01-01, kurs = 100 + 0,1·i + (7·i mod 13).
        var day = java.time.LocalDate.of(2025, 1, 1)
        val points = mutableListOf<Pair<Long, Double>>()
        while (points.size < 400) {
            if (day.dayOfWeek != java.time.DayOfWeek.SATURDAY && day.dayOfWeek != java.time.DayOfWeek.SUNDAY) {
                val i = points.size
                points += day.toEpochDay() to 100.0 + 0.1 * i + (i * 7 % 13)
            }
            day = day.plusDays(1)
        }

        val series = MovingAverageCalc.series(points)

        assertEquals(192, series.size)
        assertEquals(java.time.LocalDate.of(2025, 10, 20).toEpochDay(), series.first { it.first == java.time.LocalDate.of(2025, 10, 20).toEpochDay() }.first)
        assertEquals(116.4951690821256, series.first { it.first == java.time.LocalDate.of(2025, 10, 20).toEpochDay() }.second, 1e-9)
        assertEquals(java.time.LocalDate.of(2025, 12, 29).toEpochDay(), series.first { it.first == java.time.LocalDate.of(2025, 12, 29).toEpochDay() }.first)
        assertEquals(121.5, series.first { it.first == java.time.LocalDate.of(2025, 12, 29).toEpochDay() }.second, 1e-9)
        assertEquals(java.time.LocalDate.of(2026, 7, 14).toEpochDay(), series.first { it.first == java.time.LocalDate.of(2026, 7, 14).toEpochDay() }.first)
        assertEquals(135.6048309178744, series.first { it.first == java.time.LocalDate.of(2026, 7, 14).toEpochDay() }.second, 1e-9)
    }

    @Test
    fun `luckor i historiken - snittet ar medel av de kurser som finns i fonstret`() {
        // Bara var femte dag har en kurs (helger, röda dagar …): fönstret innehåller färre punkter
        // men ska ändå räknas — och dagar utan kurs får ingen egen snittpunkt.
        val points = (0L..400L step 5).map { it to if (it % 10 == 0L) 100.0 else 200.0 }

        val series = MovingAverageCalc.series(points)

        assertEquals(points.map { it.first }.filter { it >= window }, series.map { it.first })
        series.forEach { (day, average) ->
            val inWindow = points.filter { it.first in (day - window)..day }.map { it.second }
            assertEquals(inWindow.average(), average, 1e-9)
        }
    }

    @Test
    fun `stor lucka - en dag vars fonster saknar kurser utom den egna far den egna kursen`() {
        val points = listOf(0L to 100.0, 300L to 150.0)

        assertEquals(listOf(300L to 150.0), MovingAverageCalc.series(points))
    }

    @Test
    fun `kort period ur lang historik far snitt fran forsta punkten`() {
        // Två års historik, men diagrammet visar bara sista månaden: snittet räknas ur hela
        // historiken, så också periodens första dag har ett värde.
        val points = daily(730)
        val lastMonth = points.filter { it.first > 700 }

        val series = MovingAverageCalc.series(points).filter { it.first > 700 }

        assertEquals(lastMonth.map { it.first }, series.map { it.first })
        // Dag 701: medel av dag 411 … 701 → NAV 511 … 801 = 656.
        assertEquals(656.0, series.first().second, 1e-9)
    }

    @Test
    fun `osorterad indata ger samma resultat som sorterad`() {
        val points = daily(300)

        assertEquals(MovingAverageCalc.series(points), MovingAverageCalc.series(points.shuffled(kotlin.random.Random(1))))
    }

    @Test
    fun `averageAt och series ger samma varde for samma dag`() {
        val points = (0L..500L step 3).map { it to 100.0 + (it * 7 % 13) }

        MovingAverageCalc.series(points).forEach { (day, average) ->
            assertEquals(average, MovingAverageCalc.averageAt(points, day)!!, 0.0)
        }
    }

    @Test
    fun `averageAt for en dag efter sista kursen anvander fonstret bakat fran den dagen`() {
        // S2 frågar efter "idag", som kan ligga efter sista cachade kursen.
        val points = daily(300)

        // Fönster [20, 310] innehåller dag 20 … 300 → NAV 120 … 400 = 260.
        assertEquals(260.0, MovingAverageCalc.averageAt(points, 310)!!, 1e-9)
    }
}
