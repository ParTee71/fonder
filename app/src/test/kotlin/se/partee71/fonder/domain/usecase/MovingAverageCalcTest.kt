package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MovingAverageCalc] — 200-dagarssnittet som både S2 (ANA-2) och kursdiagrammets snittlinje (ANA-14) använder. */
class MovingAverageCalcTest {

    private val window = MovingAverageCalc.WINDOW_DAYS

    /** En kurs per kalenderdag, dag 0…[lastDay], NAV = 100 + dag. */
    private fun daily(lastDay: Long) = (0L..lastDay).map { it to 100.0 + it }

    @Test
    fun `exakt 200 dagars historik ger ett snitt for sista dagen och inget fore`() {
        val points = daily(window)

        val series = MovingAverageCalc.series(points)

        assertEquals(listOf(window), series.map { it.first })
        // Medel av 100 … 300 = 200.
        assertEquals(200.0, series.single().second, 1e-9)
    }

    @Test
    fun `kortare historik an fonstret ger ingen serie och inget snitt`() {
        val points = daily(window - 1)

        assertTrue(MovingAverageCalc.series(points).isEmpty())
        assertNull(MovingAverageCalc.averageAt(points, window - 1))
        assertTrue(MovingAverageCalc.series(emptyList()).isEmpty())
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
        // Dag 701: medel av dag 501 … 701 → NAV 601 … 801 = 701.
        assertEquals(701.0, series.first().second, 1e-9)
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

        // Fönster [110, 310] innehåller dag 110 … 300 → NAV 210 … 400 = 305.
        assertEquals(305.0, MovingAverageCalc.averageAt(points, 310)!!, 1e-9)
    }
}
