package se.partee71.fonder.ui.diagram

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.testing.captureLightAndDark
import java.time.LocalDate
import kotlin.math.sin

/**
 * Skärmdumpar ljust + mörkt av [FundLineChart] (UI-12, skill diagram): en ensam kurva med tonad
 * yta och köpmarkörer i koppar, och en jämförelse utan yta. Påhittad kurshistorik.
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

    /**
     * Vico bygger modellen på en annan tråd än compose-klockan; diagrammet är klart när dess yta
     * innehåller annat än en enda färg (axlar och kurva är ritade). Utan väntan fångade bilden
     * ibland bara periodknapparna.
     */
    private val chartDrawn: ComposeContentTestRule.() -> Boolean = {
        val pixels = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .onFirst().captureToImage().toPixelMap()
        val first = pixels[0, 0]
        (0 until pixels.height step 4).any { y -> (0 until pixels.width step 4).any { x -> pixels[x, y] != first } }
    }

    @Test
    fun `ensam kurva med köpmarkör`() = composeRule.captureLightAndDark("FundLineChart_single", ready = chartDrawn) {
        FundLineChart(points = series(300.0, 0.8), purchaseEpochDays = listOf(end - 12))
    }

    @Test
    fun `jämförelse mot föreslagen fond`() = composeRule.captureLightAndDark("FundLineChart_comparison", ready = chartDrawn) {
        FundLineChart(
            points = series(300.0, 0.8),
            primaryLabel = "Global Indexfond A",
            comparisonSeries = listOf(ChartSeries("Tillväxtmarknad Bred", series(120.0, -0.2))),
        )
    }
}
