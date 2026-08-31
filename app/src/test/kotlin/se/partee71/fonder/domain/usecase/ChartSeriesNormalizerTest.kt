package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Normaliseringen bakom de två jämförelsevyerna: [ChartSeriesNormalizer.index] för
 * kursdiagrammets fondjämförelse (ANA-11, issue #85) och [ChartSeriesNormalizer.rebaseReturns]
 * för Hems avkastningskurva mot index (HEM-9/HEM-10).
 */
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

    // --- rebaseReturns: avkastningskurvor nollställda mot perioden (HEM-9/HEM-10) ---

    @Test
    fun `avkastningsserier nollstalls till 0 procent pa periodens forsta dag`() {
        // Utan nollställning ritas +45 % och +75 % som två band på olika höjd, och periodens
        // faktiska skillnad går inte att läsa ur bilden — precis felet på Hem-kortet.
        val portfolj = listOf(100L to 0.45, 110L to 0.45)
        val index = listOf(100L to 0.75, 110L to 0.75)

        val result = ChartSeriesNormalizer.rebaseReturns(listOf(portfolj, index))

        assertEquals(100L, result.baseEpochDay)
        assertPoints(listOf(100L to 0.0, 110L to 0.0), result.series[0])
        assertPoints(listOf(100L to 0.0, 110L to 0.0), result.series[1])
        assertFalse(result.partial)
    }

    @Test
    fun `nollstallningen raknar kvot, inte skillnad i procentenheter`() {
        // +100 % → +110 % är en uppgång på 5 % under perioden (2,10 / 2,00), inte 10
        // procentenheter. Subtraktion hade svarat fel på just den fråga vyn ställer.
        val portfolj = listOf(100L to 1.00, 110L to 1.10)

        val result = ChartSeriesNormalizer.rebaseReturns(listOf(portfolj))

        assertEquals(0.0, result.series[0][0].second, 1e-12)
        assertEquals(0.05, result.series[0][1].second, 1e-12)
    }

    @Test
    fun `periodens vinnare syns oavsett var kurvorna lag nar perioden borjade`() {
        // Portföljen ligger mycket lägre i ackumulerad avkastning men växer mer under perioden.
        val portfolj = listOf(100L to 0.40, 110L to 0.54) // 1,40 → 1,54 = +10 %
        val index = listOf(100L to 0.72, 110L to 0.7716) // 1,72 → 1,7716 = +3 %

        val result = ChartSeriesNormalizer.rebaseReturns(listOf(portfolj, index))

        assertEquals(0.10, result.series[0][1].second, 1e-9)
        assertEquals(0.03, result.series[1][1].second, 1e-9)
    }

    @Test
    fun `basdagen ar forsta dag bada serierna har data och kortare historik markeras`() {
        val portfolj = listOf(100L to 0.10, 110L to 0.21)
        val index = listOf(110L to 0.50)

        val result = ChartSeriesNormalizer.rebaseReturns(listOf(portfolj, index))

        assertEquals(110L, result.baseEpochDay)
        assertTrue(result.partial)
        assertPoints(listOf(110L to 0.0), result.series[0])
        assertPoints(listOf(110L to 0.0), result.series[1])
    }

    @Test
    fun `en serie som tar slut fore basdagen nollstalls inte alls`() {
        val portfolj = listOf(120L to 0.10)
        val slutadHistorik = listOf(100L to 0.50)

        val result = ChartSeriesNormalizer.rebaseReturns(listOf(portfolj, slutadHistorik))

        assertTrue(result.partial)
        assertNull(result.baseEpochDay)
        assertPoints(listOf(100L to 0.50), result.series[1])
    }

    @Test
    fun `en total forlust pa basdagen nollstalls inte i stallet for en division med noll`() {
        // −100 % → 1 + r = 0. Ingen ärlig bas att räkna kvot mot.
        val portfolj = listOf(100L to -1.0, 110L to -0.9)

        val result = ChartSeriesNormalizer.rebaseReturns(listOf(portfolj))

        assertTrue(result.partial)
        assertPoints(listOf(100L to -1.0, 110L to -0.9), result.series[0])
    }

    @Test
    fun `en ensam kurva fran forsta kopet ar oforandrad, sa Allt startar pa noll`() {
        // HEM-9: med Allt vald är basdagen första köpet, där avkastningen är 0 % per definition.
        val portfolj = listOf(100L to 0.0, 110L to 0.20, 120L to 0.35)

        val result = ChartSeriesNormalizer.rebaseReturns(listOf(portfolj))

        assertPoints(listOf(100L to 0.0, 110L to 0.20, 120L to 0.35), result.series[0])
        assertFalse(result.partial)
    }

    @Test
    fun `ett fonster ur en tidsviktad kedja ar produkten av periodens dagsfaktorer`() {
        // Poängen med issue #116: serien från PortfolioReturnSeriesCalc är ett kedjat index, så
        // nollställningen mot fönstrets första dag ger exakt den periodens egen avkastning —
        // varje period svarar på sin egen fråga i stället för att bero på var basdagen råkar
        // hamna. Punkterna ligger 30 dagar isär, faktorerna är +10 %, +10 %, −20 %, +25 %.
        var index = 1.0
        val kedja = mutableListOf(100L to 0.0)
        listOf(1.10, 1.10, 0.80, 1.25).forEachIndexed { steg, faktor ->
            index *= faktor
            kedja += (130L + steg * 30L) to (index - 1.0)
        }

        fun periodensAvkastning(period: ChartPeriodFilter.Period): Double =
            ChartSeriesNormalizer.rebaseReturns(listOf(ChartPeriodFilter.apply(kedja, period)))
                .series[0].last().second

        // Sista månaden = sista faktorn, sista kvartalet = de tre sista, Allt = hela kedjan.
        assertEquals(0.25, periodensAvkastning(ChartPeriodFilter.Period.EN_MANAD), 1e-9)
        assertEquals(0.10, periodensAvkastning(ChartPeriodFilter.Period.TRE_MANADER), 1e-9)
        assertEquals(0.21, periodensAvkastning(ChartPeriodFilter.Period.ALLT), 1e-9)
    }

    @Test
    fun `tomma serier ger tomt resultat utan krasch`() {
        val result = ChartSeriesNormalizer.rebaseReturns(listOf(emptyList(), emptyList()))

        assertNull(result.baseEpochDay)
        assertFalse(result.partial)
    }
}
