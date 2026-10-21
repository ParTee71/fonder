package se.partee71.fonder.ui.fond

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * "per <datum>" i Fonddetaljens analys (ANA-1, issue #198): visas på periodraderna och på CAGR, GAV och
 * andel bara när slutkursen är inaktuell, aldrig annars. Påhittade tal.
 */
@RunWith(RobolectricTestRunner::class)
class AnalysisSectionAsOfTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val navDay = LocalDate.of(2026, 10, 12)

    private fun analysis(stale: Boolean) = FundAnalysisCalc.Analysis(
        keyFigures = FundAnalysisCalc.KeyFigures(
            periodReturns = FundAnalysisCalc.Period.entries.map {
                FundAnalysisCalc.PeriodReturn(it, amount = 10.0, fraction = 0.05, startEpochDay = navDay.minusYears(1).toEpochDay(), endEpochDay = navDay.toEpochDay(), endIsStale = stale)
            },
            cagr = 0.05,
            currentNav = 120.0,
            currentNavEpochDay = navDay.toEpochDay(),
            currentNavIsStale = stale,
            gavPerShare = 100.0,
            gavFraction = 0.2,
            portfolioShareFraction = 0.25,
            annualizedVolatility = null,
            sharpeRatio = null,
            riskWindowYears = null
        ),
        distanceFromHigh = null,
        trend = null,
        momentum = null,
        status = null,
        profitTake = null
    )

    @Test
    fun `per datum visas pa varje periodrad och pa CAGR GAV och andel nar slutkursen ar inaktuell`() {
        composeRule.setContent { FonderTheme { AnalysisSection(analysis(stale = true)) } }

        // Fem periodrader + CAGR + GAV + andel.
        composeRule.onAllNodesWithText("per 2026-10-12").assertCountEquals(8)
    }

    @Test
    fun `per datum visas inte nar slutkursen ar aktuell`() {
        composeRule.setContent { FonderTheme { AnalysisSection(analysis(stale = false)) } }

        composeRule.onAllNodesWithText("per 2026-10-12").assertCountEquals(0)
    }
}
