package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.PortfolioTotalCalc
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * [TotalCard] (HEM-1/POR-3, issue #191): totalresultatet är huvudtalet, årsavkastningen visas
 * först efter ett år, orealiserat står som underordnad rad och förklaringen fälls ut.
 * Skärmdumpar ljust + mörkt för en portfölj över och under ett år (skill testing-strategy).
 */
@RunWith(RobolectricTestRunner::class)
class TotalCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val matureTotal = PortfolioTotalCalc.Result(
        netDepositsKr = 113_583.33,
        totalGainKr = 9_873.45,
        totalGainFraction = 0.0869,
        moneyWeightedAnnualReturn = 0.066,
        partial = false,
        ageDays = 812
    )
    private val youngTotal = PortfolioTotalCalc.Result(
        netDepositsKr = 50_000.0,
        totalGainKr = -1_789.7,
        totalGainFraction = -0.0358,
        moneyWeightedAnnualReturn = null,
        partial = false,
        ageDays = 200
    )

    @Test
    fun `visar totalresultat arsavkastning och orealiserat som egna rader`() {
        composeRule.setContent {
            FonderTheme {
                TotalCard(totalValue = 123_456.78, totalInvested = 110_208.0, gainLoss = 13_248.0, gainLossFraction = 0.1202, total = matureTotal, navEpochDay = 20_742)
            }
        }

        composeRule.onNodeWithText(MoneyFormat.kr(123_456.78)).assertIsDisplayed()
        composeRule.onNodeWithText("Totalresultat").assertIsDisplayed()
        composeRule.onNodeWithText("${MoneyFormat.percentSigned(0.0869)} · ${MoneyFormat.kr(9_873.45)}").assertIsDisplayed()
        composeRule.onNodeWithText("Årsavkastning (pengaviktad)").assertIsDisplayed()
        composeRule.onNodeWithText(MoneyFormat.percentSigned(0.066)).assertIsDisplayed()
        composeRule.onNodeWithText("Varav orealiserat").assertIsDisplayed()
        composeRule.onNodeWithText("${MoneyFormat.percentSigned(0.1202)} · ${MoneyFormat.kr(13_248.0)}").assertIsDisplayed()
        composeRule.onNodeWithText("Värde per 2026-10-16").assertIsDisplayed()
    }

    @Test
    fun `arsavkastningen ersatts av Visas efter ett ar for en ung portfolj`() {
        composeRule.setContent {
            FonderTheme {
                TotalCard(totalValue = 48_210.3, totalInvested = 50_000.0, gainLoss = -1_034.7, gainLossFraction = -0.021, total = youngTotal, navEpochDay = 20_742)
            }
        }

        composeRule.onNodeWithText("Visas efter ett år").assertIsDisplayed()
        composeRule.onNodeWithText("Otillräcklig data").assertDoesNotExist()
    }

    @Test
    fun `aldre portfolj utan losning pa XIRR visar Otillracklig data`() {
        composeRule.setContent {
            FonderTheme {
                TotalCard(totalValue = 1_000.0, totalInvested = 1_000.0, gainLoss = 0.0, gainLossFraction = 0.0, total = matureTotal.copy(moneyWeightedAnnualReturn = null), navEpochDay = 20_742)
            }
        }

        composeRule.onNodeWithText("Otillräcklig data").assertIsDisplayed()
        composeRule.onNodeWithText("Visas efter ett år").assertDoesNotExist()
    }

    @Test
    fun `forklaringen falls ut fran Vad raknas in`() {
        composeRule.setContent {
            FonderTheme {
                TotalCard(totalValue = 123_456.78, totalInvested = 110_208.0, gainLoss = 13_248.0, gainLossFraction = 0.1202, total = matureTotal, navEpochDay = 20_742)
            }
        }

        composeRule.onNodeWithText("Totalresultatet är dagens värde", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Vad räknas in?").performClick()
        composeRule.onNodeWithText("Totalresultatet är dagens värde", substring = true).assertIsDisplayed()
    }

    @Test
    fun `saknar bara nagot innehav kurs visas Kurs saknas annu pa totalresultat och arsavkastning`() {
        composeRule.setContent {
            FonderTheme {
                TotalCard(totalValue = 60_000.0, totalInvested = 110_208.0, gainLoss = 2_000.0, gainLossFraction = 0.034, total = matureTotal.copy(partial = true, totalGainKr = null, totalGainFraction = null, moneyWeightedAnnualReturn = null), navEpochDay = 20_742)
            }
        }

        composeRule.onNodeWithText("Totalresultat").assertIsDisplayed()
        composeRule.onAllNodesWithText("Kurs saknas ännu").assertCountEquals(2)
        composeRule.onNodeWithText("Otillräcklig data").assertDoesNotExist()
        composeRule.onNodeWithText("Varav orealiserat").assertIsDisplayed()
    }

    @Test
    fun `utan kurs visas nettoinvesterat och Kurs saknas annu`() {
        composeRule.setContent {
            FonderTheme {
                TotalCard(totalValue = 0.0, totalInvested = 110_208.0, gainLoss = 0.0, gainLossFraction = null, total = matureTotal.copy(partial = true, totalGainKr = null), navEpochDay = null)
            }
        }

        composeRule.onNodeWithText(MoneyFormat.kr(110_208.0)).assertIsDisplayed()
        composeRule.onNodeWithText("Kurs saknas ännu").assertIsDisplayed()
        composeRule.onNodeWithText("Totalresultat").assertDoesNotExist()
    }

    @Test
    fun `skärmdump över och under ett år`() = composeRule.captureLightAndDark("TotalCard_variants") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TotalCard(totalValue = 123_456.78, totalInvested = 110_208.0, gainLoss = 13_248.0, gainLossFraction = 0.1202, total = matureTotal, navEpochDay = 20_742)
            TotalCard(totalValue = 48_210.3, totalInvested = 50_000.0, gainLoss = -1_034.7, gainLossFraction = -0.021, total = youngTotal, navEpochDay = 20_742)
        }
    }
}
