package se.partee71.fonder.ui.portfolj

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Instrumenterat test av Portföljens tillståndsdrivna innehåll, inklusive de nya
 * dag/vecka/månads-badgarna per innehav (issue #14, POR-5) — bygger [PortfoljUiState]
 * direkt i stället för att gå via ett riktigt [PortfoljViewModel]/Hilt.
 */
@RunWith(AndroidJUnit4::class)
class PortfoljScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fond = Fund(fundId = "SHB0000442", name = "Fond A")

    @Test
    fun tomt_tillstand_visas_utan_innehav() {
        composeRule.setContent {
            FonderTheme { PortfoljContent(state = PortfoljUiState(loading = false), onFundClick = {}) }
        }

        composeRule.onNodeWithText("Inga innehav ännu").assertExists()
    }

    @Test
    fun innehavsrad_visar_period_badge_per_fond() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1100.0)
        val state = PortfoljUiState(
            loading = false,
            holdings = listOf(holding),
            performance = mapOf(
                fond.fundId to PortfolioPerformanceCalc.HoldingPerformance(
                    day = PortfolioPerformanceCalc.Change(amount = 30.0, fraction = 0.03),
                    week = null,
                    month = null,
                ),
            ),
        )

        composeRule.setContent {
            FonderTheme { PortfoljContent(state = state, onFundClick = {}) }
        }

        composeRule.onNodeWithText("Fond A").assertExists()
        composeRule.onNodeWithText("+3,0 % · 30,00 kr", substring = true).assertExists()
        // Vecka och månad saknar historik -> två "otillräcklig data"-rader. Card slår ihop
        // sina barns semantik till en enda nod (mergeDescendants), så både onNodeWithText
        // (kräver exakt en träff) och en oskyddad onAllNodesWithText skulle bara se en
        // sammanslagen nod — useUnmergedTree=true krävs för att räkna raderna var för sig.
        composeRule.onAllNodesWithText("Otillräcklig data", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun innehav_utan_performance_visar_otillrackligt_data_for_alla_perioder() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1100.0)
        val state = PortfoljUiState(loading = false, holdings = listOf(holding), performance = emptyMap())

        composeRule.setContent {
            FonderTheme { PortfoljContent(state = state, onFundClick = {}) }
        }

        composeRule.onAllNodesWithText("Otillräcklig data", useUnmergedTree = true).assertCountEquals(3)
    }
}
