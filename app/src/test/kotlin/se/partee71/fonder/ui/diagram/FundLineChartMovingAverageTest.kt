package se.partee71.fonder.ui.diagram

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.ui.theme.FonderTheme
import java.time.LocalDate

/**
 * Snittlinjen i [FundLineChart] (ANA-14): teckenförklaringen med senaste kurs och senaste snitt,
 * beskrivningen för TalkBack, och att inget av det visas när snittet saknas eller diagrammet
 * jämför fonder.
 */
@RunWith(RobolectricTestRunner::class)
class FundLineChartMovingAverageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val end = LocalDate.of(2026, 10, 9).toEpochDay()

    /** En månad (förvald period) med senaste kurs 384,50 kr. */
    private val points = (0L until 30L).map { (end - 29 + it) to 370.0 + it * 0.5 }

    /** Snittet: en punkt före perioden (ska beskäras bort) och två inom den, senaste 367,71 kr. */
    private val average = listOf((end - 40) to 300.0, (end - 1) to 367.5, end to 367.71)

    private fun show(
        movingAverage: List<Pair<Long, Double>>?,
        comparison: List<ChartSeries> = emptyList(),
        valueAxis: ChartValueAxis = ChartValueAxis.KRONOR,
        chartPoints: List<Pair<Long, Double>> = points,
    ) {
        composeRule.setContent {
            FonderTheme {
                FundLineChart(points = chartPoints, comparisonSeries = comparison, valueAxis = valueAxis, movingAverage = movingAverage)
            }
        }
    }

    @Test
    fun `forklaringen visar kurs och snitt med senaste varden`() {
        show(average)

        composeRule.onNodeWithText("Kurs").assertExists()
        composeRule.onNodeWithText("384,50 kr").assertExists()
        composeRule.onNodeWithText("200-dagarssnitt").assertExists()
        composeRule.onNodeWithText("367,71 kr").assertExists()
    }

    @Test
    fun `beskrivningen namner snittet`() {
        show(average)

        composeRule.onNodeWithContentDescription(
            "Kursdiagram, 1 mån, 30 kurspunkter mellan 370,00 kr och 384,50 kr, 200-dagarssnitt 367,71 kr",
        ).assertExists()
    }

    @Test
    fun `utan snitt i perioden visas varken forklaring eller snitt i beskrivningen`() {
        // Bara en punkt före perioden: inget att rita, och då ingen förklaringsrad (ANA-4).
        show(listOf((end - 40) to 300.0))

        composeRule.onAllNodesWithText("200-dagarssnitt").assertCountEquals(0)
        composeRule.onAllNodesWithText("Kurs").assertCountEquals(0)
        composeRule.onNodeWithContentDescription(
            "Kursdiagram, 1 mån, 30 kurspunkter mellan 370,00 kr och 384,50 kr",
        ).assertExists()
    }

    @Test
    fun `i en jamforelse ignoreras snittet`() {
        show(average, comparison = listOf(ChartSeries("Tillväxtmarknad Bred", points.map { it.first to it.second / 3 })))

        composeRule.onAllNodesWithText("200-dagarssnitt").assertCountEquals(0)
        composeRule.onNodeWithText("Tillväxtmarknad Bred").assertExists()
        composeRule.onNodeWithContentDescription("200-dagarssnitt", substring = true).assertDoesNotExist()
    }

    @Test
    fun `i procentlage ignoreras snittet`() {
        // Avkastning som andel, 0 … +2,9 %: ett snitt i kronor hade haft fel skala.
        val returns = points.mapIndexed { index, (day, _) -> day to index * 0.001 }
        show(average, valueAxis = ChartValueAxis.PROCENT, chartPoints = returns)

        composeRule.onAllNodesWithText("200-dagarssnitt").assertCountEquals(0)
        composeRule.onAllNodesWithText("Kurs").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Avkastningsdiagram", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("200-dagarssnitt", substring = true).assertDoesNotExist()
    }
}
