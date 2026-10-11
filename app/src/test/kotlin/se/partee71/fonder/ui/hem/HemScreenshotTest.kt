package se.partee71.fonder.ui.hem

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.domain.usecase.BackupReminder
import se.partee71.fonder.domain.usecase.OpenSwitchWatch
import se.partee71.fonder.domain.usecase.PendingDecisions
import se.partee71.fonder.domain.usecase.PortfolioReturnSeriesCalc
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.testing.isDrawn
import kotlin.math.sin

/**
 * Skärmdumpar ljust + mörkt av Översikten enligt mockupen (HEM-13): värdekortet, "Att besluta",
 * jämförelsen mot index och påminnelsen om säkerhetskopia överst. Påhittade belopp.
 */
@RunWith(RobolectricTestRunner::class)
class HemScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val start = 20_300L

    private fun curve(slope: Double) = (0L until 60L).map { day -> (start + day) to slope * day + 0.01 * sin(day / 4.0) }

    private val decisions = PendingDecisions(
        switchPlan = (0..1).map { index ->
            SwitchPlanResolver.Suggestion(
                recordId = index.toLong(), planIndex = index, sellIsin = "SE_S$index", buyIsin = "SE_K$index",
                sellFundName = "Tillväxtmarknad Bred", buyFundName = "Global Indexfond A",
                fromLevel = 6, toLevel = 4, feeDeltaPercent = -0.5,
            )
        },
        openWatches = listOf(OpenSwitchWatch(watchId = 1, sellFundName = "Sverige Småbolag", daysWaiting = 2, candidateCount = 3)),
    )

    @Test
    fun `översikten med att besluta och jämförelsen`() = composeRule.captureLightAndDark(
        "HemScreen_oversikt",
        ready = { isDrawn(hasContentDescription("Avkastningsdiagram", substring = true)) },
    ) {
        HemContent(
            state = HemUiState(
                loading = false,
                hasHoldings = true,
                totalInvested = 374_646.0,
                totalValue = 412_860.0,
                totalGainLoss = 38_214.0,
                totalGainLossFraction = 0.102,
                navEpochDay = start + 59,
                returnSeries = PortfolioReturnSeriesCalc.Result(points = curve(0.002), partial = false),
                benchmarkSeries = BenchmarkSeries(fundName = "Global Index", points = curve(0.0015)),
                purchaseEpochDays = listOf(start + 20),
                decisions = decisions,
                backupReminder = BackupReminder(newTransactions = 3, lastBackupEpochMillis = 1_759_449_600_000L),
            ),
        )
    }
}
