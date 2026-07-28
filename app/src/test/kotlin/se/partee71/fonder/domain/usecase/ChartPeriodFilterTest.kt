package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.usecase.ChartPeriodFilter.Period

class ChartPeriodFilterTest {

    private fun point(epochDay: Long, nav: Double = 100.0) = epochDay to nav

    @Test
    fun `en manad behaller bara punkter inom 30 dagar fran senaste punkten`() {
        val latest = 20_000L
        val points = listOf(
            point(latest - 400),
            point(latest - 31),
            point(latest - 30),
            point(latest - 1),
            point(latest),
        )

        val result = ChartPeriodFilter.apply(points, Period.EN_MANAD)

        assertEquals(setOf(latest - 30, latest - 1, latest), result.map { it.first }.toSet())
    }

    @Test
    fun `kortare historik an perioden ger hela historiken`() {
        val latest = 20_000L
        val points = listOf(point(latest - 5), point(latest - 2), point(latest))

        val result = ChartPeriodFilter.apply(points, Period.ETT_AR)

        assertEquals(points.map { it.first }.toSet(), result.map { it.first }.toSet())
    }

    @Test
    fun `allt ger tillbaka precis samma punkter oavsett tidsspann`() {
        val points = listOf(point(0), point(10_000), point(20_000))

        val result = ChartPeriodFilter.apply(points, Period.ALLT)

        assertEquals(points, result)
    }

    @Test
    fun `tom lista ger tom lista utan att krascha`() {
        assertTrue(ChartPeriodFilter.apply(emptyList(), Period.EN_MANAD).isEmpty())
    }

    @Test
    fun `cutoff raknas fran senaste punkten i datan, inte fran nagon extern tidsreferens`() {
        // En fond med gammal, orörlig historik ska ändå visa sitt senaste kända fönster när
        // "1 månad" väljs — inte ett tomt diagram bara för att ingen punkt ligger nära dagens
        // datum.
        val gammaltDatum = 5_000L
        val points = listOf(point(gammaltDatum - 40), point(gammaltDatum - 10), point(gammaltDatum))

        val result = ChartPeriodFilter.apply(points, Period.EN_MANAD)

        assertEquals(setOf(gammaltDatum - 10, gammaltDatum), result.map { it.first }.toSet())
    }

    @Test
    fun `punkter behover inte vara sorterade for att senaste ska hittas ratt`() {
        val latest = 20_000L
        val ordnat = listOf(point(latest), point(latest - 5), point(latest - 400))

        val result = ChartPeriodFilter.apply(ordnat, Period.EN_MANAD)

        assertEquals(setOf(latest, latest - 5), result.map { it.first }.toSet())
    }
}
