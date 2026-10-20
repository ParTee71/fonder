package se.partee71.fonder.ui.hem

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.time.LocalDate
import kotlin.math.sin
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.data.common.AppErrors
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.usecase.BackupReminder
import se.partee71.fonder.domain.usecase.CheaperAlternative
import se.partee71.fonder.domain.usecase.OpenSwitchWatch
import se.partee71.fonder.domain.usecase.PendingDecisions
import se.partee71.fonder.domain.usecase.PortfolioReturnSeriesCalc
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.testing.isDrawn
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.common.DetailUiState

/**
 * Skärmdumpar ljust + mörkt av Översikten enligt mockupen (HEM-13): värdekortet, "Att besluta",
 * jämförelsen mot index och påminnelsen om säkerhetskopia överst. Påhittade belopp. Här ligger också
 * "Att besluta"-radens undertext, som skärmdumpen visar, läsfelets "Försök igen" (NFR-6) och det tomma
 * lägets tre val (HEM-15) med en skärmdump.
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
                fromLevel = 6, toLevel = 4, feeDeltaPercent = -0.5
            )
        },
        cheaperAlternatives = listOf(
            CheaperAlternative(
                suggestion = SwitchPlanResolver.Suggestion(
                    recordId = 9, planIndex = 0, sellIsin = "SE_SB", buyIsin = "SE_SBI",
                    sellFundName = "Sverige Småbolag", buyFundName = "Småbolag Index",
                    fromLevel = 5, toLevel = 5, feeDeltaPercent = -1.0, switchValueKr = 96_000.0, kind = SuggestionKind.FEE
                ),
                sellFeePercent = 1.40,
                buyFeePercent = 0.40
            )
        ),
        openWatches = listOf(OpenSwitchWatch(watchId = 1, sellFundName = "Sverige Småbolag", daysWaiting = 2, candidateCount = 3))
    ).withSwitches(emptyList(), LocalDate.ofEpochDay(start + 59))

    private val emptyPortfolio = HemUiState(portfolio = DetailUiState.Content(HemPortfolio(hasHoldings = false)))

    @Test
    fun `lasfel visar felet i stallet for nollor (NFR-6)`() {
        composeRule.setContent {
            FonderTheme { HemContent(state = HemUiState(portfolio = DetailUiState.Error(AppErrors.map(DataError.Database)), decisions = decisions)) }
        }
        composeRule.onNodeWithText("Kunde inte visa innehållet").assertExists()
        composeRule.onNodeWithText("Appens data gick inte att läsa. Lämna skärmen och försök igen.").assertExists()
        composeRule.onNodeWithText("Ingen portfölj ännu").assertDoesNotExist()
        composeRule.onNodeWithText("0,00 kr", substring = true).assertDoesNotExist()
    }

    @Test
    fun `lasfelets Forsok igen laser om oversikten (NFR-6)`() {
        var retries = 0
        composeRule.setContent {
            FonderTheme { HemContent(state = HemUiState(portfolio = DetailUiState.Error(AppErrors.map(DataError.Database))), onRetry = { retries++ }) }
        }
        composeRule.onNodeWithText("Försök igen").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `tomt lage har tre vagar framat och aterstall gar till sakerhetskopian (HEM-15)`() {
        var opened = ""
        composeRule.setContent {
            FonderTheme {
                HemContent(
                    state = emptyPortfolio,
                    onWatchFund = { opened += "bevaka;" },
                    onImportHoldings = { opened += "import;" },
                    onOpenBackup = { opened += "backup;" }
                )
            }
        }
        composeRule.onNodeWithText("Ingen portfölj ännu").assertIsDisplayed()
        composeRule.onNodeWithText("Bevaka en fond").performScrollTo().performClick()
        composeRule.onNodeWithText("Importera innehav").performScrollTo().performClick()
        composeRule.onNodeWithText("Återställ från säkerhetskopia").performScrollTo().performClick()
        assertEquals("bevaka;import;backup;", opened)
    }

    @Test
    fun `skarmdump av Oversikten utan innehav`() = composeRule.captureLightAndDark("HemScreen_tom") {
        HemContent(state = emptyPortfolio)
    }

    @Test
    fun `att besluta namner varje slag som vantar och utelamnar nollor`() {
        // HEM-13: föreslagna, sålda och billigare alternativ (ANA-9) var för sig, ur listan Byten (BYT-4).
        composeRule.setContent {
            FonderTheme { HemContent(state = HemUiState(portfolio = DetailUiState.Content(HemPortfolio(hasHoldings = true)), decisions = decisions)) }
        }
        composeRule.onNodeWithText("2 föreslagna · 1 sålt, väntar på köp · 1 billigare alternativ").assertExists()
    }

    @Test
    fun `att besluta med bara ett billigare alternativ namner bara det`() {
        composeRule.setContent {
            FonderTheme {
                HemContent(
                    state = HemUiState(
                        portfolio = DetailUiState.Content(
                            HemPortfolio(
                                hasHoldings = true
                            )
                        ),
                        decisions = PendingDecisions(cheaperAlternatives = decisions.cheaperAlternatives)
                            .withSwitches(emptyList(), LocalDate.ofEpochDay(start + 59))
                    )
                )
            }
        }
        composeRule.onNodeWithText("1 billigare alternativ").assertExists()
    }

    @Test
    fun `översikten med att besluta och jämförelsen`() = composeRule.captureLightAndDark(
        "HemScreen_oversikt",
        ready = { isDrawn(hasContentDescription("Avkastningsdiagram", substring = true)) }
    ) {
        HemContent(
            state = HemUiState(
                portfolio = DetailUiState.Content(
                    HemPortfolio(
                        hasHoldings = true,
                        totalInvested = 374_646.0,
                        totalValue = 412_860.0,
                        totalGainLoss = 38_214.0,
                        totalGainLossFraction = 0.102,
                        navEpochDay = start + 59,
                        returnSeries = PortfolioReturnSeriesCalc.Result(points = curve(0.002), partial = false),
                        benchmarkSeries = BenchmarkSeries(fundName = "Global Index", points = curve(0.0015).toImmutableList()),
                        purchaseEpochDays = persistentListOf(start + 20)
                    )
                ),
                decisions = decisions,
                backupReminder = BackupReminder(newTransactions = 3, lastBackupEpochMillis = 1_759_449_600_000L)
            )
        )
    }
}
