package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Indexeringen bakom jämförelsediagrammet (ANA-11, issue #85). */
class ChartSeriesNormalizerTest {

    private fun assertPoints(expected: List<Pair<Long, Double>>, actual: List<Pair<Long, Double>>) {
        assertEquals(expected.map { it.first }, actual.map { it.first })
        expected.zip(actual).forEach { (e, a) -> assertEquals(e.second, a.second, 0.0001) }
    }

    @Test
    fun `en ensam serie indexeras inte — kursen ska visas i kronor`() {
        val series = listOf(listOf(100L to 12.0, 101L to 13.0))

        val result = ChartSeriesNormalizer.index(series)

        assertFalse(result.indexed)
        assertFalse(result.partial)
        assertNull(result.baseEpochDay)
        assertPoints(listOf(100L to 12.0, 101L to 13.0), result.series.single())
    }

    @Test
    fun `tva serier indexeras till 100 vid gemensam start`() {
        // Två helt olika NAV-nivåer: utan indexering hade den ena kurvan varit en platt linje.
        val holding = listOf(100L to 50.0, 101L to 55.0)
        val candidate = listOf(100L to 1000.0, 101L to 1050.0)

        val result = ChartSeriesNormalizer.index(listOf(holding, candidate))

        assertTrue(result.indexed)
        assertFalse(result.partial)
        assertEquals(100L, result.baseEpochDay)
        assertPoints(listOf(100L to 100.0, 101L to 110.0), result.series[0])
        assertPoints(listOf(100L to 100.0, 101L to 105.0), result.series[1])
    }

    @Test
    fun `kandidat med kortare historik beskars till gemensam start och markeras som delvis`() {
        val holding = listOf(100L to 50.0, 110L to 60.0, 120L to 66.0)
        val candidate = listOf(110L to 20.0, 120L to 21.0)

        val result = ChartSeriesNormalizer.index(listOf(holding, candidate))

        assertTrue(result.indexed)
        assertTrue("Perioden före dag 110 saknar jämförelse", result.partial)
        assertEquals(110L, result.baseEpochDay)
        // Innehavet indexeras från dag 110, inte från sin egen start — annars mäts fonderna
        // över olika perioder.
        assertPoints(listOf(110L to 100.0, 120L to 110.0), result.series[0])
        assertPoints(listOf(110L to 100.0, 120L to 105.0), result.series[1])
    }

    @Test
    fun `serie som tar slut fore gemensam start indexeras inte alls`() {
        val holding = listOf(100L to 50.0, 110L to 60.0)
        val candidate = listOf(200L to 20.0)

        val result = ChartSeriesNormalizer.index(listOf(holding, candidate))

        assertFalse("Utan överlapp finns ingen ärlig gemensam utgångspunkt", result.indexed)
        assertTrue(result.partial)
        assertNull(result.baseEpochDay)
    }

    @Test
    fun `tom kandidatserie lamnar innehavets kurva orord`() {
        val holding = listOf(100L to 50.0, 110L to 60.0)

        val result = ChartSeriesNormalizer.index(listOf(holding, emptyList()))

        assertFalse(result.indexed)
        assertPoints(holding, result.series[0])
        assertTrue(result.series[1].isEmpty())
    }

    @Test
    fun `osorterad indata sorteras innan indexering`() {
        val holding = listOf(110L to 60.0, 100L to 50.0)
        val candidate = listOf(110L to 21.0, 100L to 20.0)

        val result = ChartSeriesNormalizer.index(listOf(holding, candidate))

        assertEquals(100L, result.baseEpochDay)
        assertPoints(listOf(100L to 100.0, 110L to 120.0), result.series[0])
    }

    @Test
    fun `nollkurs pa basdagen ger ingen indexering i stallet for en division med noll`() {
        val holding = listOf(100L to 0.0, 110L to 60.0)
        val candidate = listOf(100L to 20.0, 110L to 21.0)

        val result = ChartSeriesNormalizer.index(listOf(holding, candidate))

        assertFalse(result.indexed)
        assertTrue(result.partial)
    }
}
