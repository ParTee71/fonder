package se.partee71.fonder.ui.diagram

import androidx.compose.ui.test.junit4.createComposeRule
import java.time.LocalDate
import kotlin.math.sin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.domain.usecase.MovingAverageCalc
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.testing.isDrawn

/**
 * Skärmdumpar ljust + mörkt av [FundLineChart] (UI-12, skill diagram): en ensam kurva med tonad
 * yta och köpmarkörer i koppar, en jämförelse utan yta, och en kurva med prickat 200-dagarssnitt
 * och dess teckenförklaring (ANA-14). Påhittad kurshistorik.
 */
@RunWith(RobolectricTestRunner::class)
class FundLineChartScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val end = LocalDate.of(2026, 10, 9).toEpochDay()

    /** En månads påhittade kurser kring 300 kr med en svag uppåttrend. */
    private fun series(base: Double, slope: Double) = (0L until 30L).map { day ->
        (end - 29 + day) to base + slope * day + 4 * sin(day / 3.0)
    }

    @Test
    fun `ensam kurva med köpmarkör`() = composeRule.captureLightAndDark("FundLineChart_single", ready = { isDrawn() }) {
        FundLineChart(points = series(300.0, 0.8), purchaseEpochDays = listOf(end - 12))
    }

    @Test
    fun `jämförelse mot föreslagen fond`() = composeRule.captureLightAndDark("FundLineChart_comparison", ready = { isDrawn() }) {
        FundLineChart(
            points = series(300.0, 0.8),
            primaryLabel = "Global Indexfond A",
            comparisonSeries = listOf(ChartSeries("Tillväxtmarknad Bred", series(120.0, -0.2)))
        )
    }

    @Test
    fun `kurva med 200-dagarssnitt`() = composeRule.captureLightAndDark("FundLineChart_movingAverage", ready = { isDrawn() }) {
        // Ett års påhittade kurser som stiger från ~330 till ~390 kr; diagrammet visar sista
        // månaden, snittet räknas ur hela året och ligger därför under kursen.
        val history = (0L until 365L).map { day -> (end - 364 + day) to 330.0 + 0.16 * day + 6 * sin(day / 5.0) }
        FundLineChart(points = history, movingAverage = MovingAverageCalc.series(history))
    }
}
