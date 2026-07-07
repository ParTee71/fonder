package se.partee71.fonder.ui.hem

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Instrumenterat test av Hem-skärmens tillståndsdrivna innehåll (issue #14) — bygger
 * [HemUiState] direkt i stället för att gå via ett riktigt [HemViewModel]/Hilt, för ett
 * deterministiskt test utan korutin-timing.
 */
@RunWith(AndroidJUnit4::class)
class HemScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tomt_tillstand_visas_utan_innehav() {
        composeRule.setContent {
            FonderTheme { HemContent(state = HemUiState(loading = false, hasHoldings = false)) }
        }

        composeRule.onNodeWithText("Ingen portfölj ännu").assertExists()
    }

    @Test
    fun totalkort_visar_varde_vinst_och_period_forandring() {
        // Undviker tresiffriga belopp — tusentalsavgränsaren kan vara vanligt eller hårt
        // blanksteg beroende på JVM/lokal (samma försiktighet som MoneyFormatTest).
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            totalValue = 500.0,
            totalGainLoss = 100.0,
            totalGainLossFraction = 0.2,
            performance = PortfolioPerformanceCalc.PortfolioPerformance(
                day = PortfolioPerformanceCalc.TotalChange(amount = 50.0, fraction = 0.05, partial = false),
                week = PortfolioPerformanceCalc.TotalChange(amount = 90.0, fraction = 0.1, partial = false),
                month = null,
            ),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("500,00 kr", substring = true).assertExists()
        composeRule.onNodeWithText("+20,0 % · 100,00 kr", substring = true).assertExists()
        composeRule.onNodeWithText("+5,0 % · 50,00 kr", substring = true).assertExists()
        composeRule.onNodeWithText("Otillräcklig data").assertExists()
    }

    @Test
    fun delvis_osaker_markering_visas_for_en_period() {
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            totalValue = 1000.0,
            totalGainLoss = 0.0,
            totalGainLossFraction = 0.0,
            performance = PortfolioPerformanceCalc.PortfolioPerformance(
                day = PortfolioPerformanceCalc.TotalChange(amount = 10.0, fraction = 0.01, partial = true),
                week = null,
                month = null,
            ),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("Delvis osäker — någon fond saknar historik").assertExists()
    }
}
