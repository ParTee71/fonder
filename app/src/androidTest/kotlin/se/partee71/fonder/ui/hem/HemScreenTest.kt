package se.partee71.fonder.ui.hem

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
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
                day = PortfolioPerformanceCalc.PortfolioPeriodResult.Available(amount = 50.0, fraction = 0.05, partial = false),
                week = PortfolioPerformanceCalc.PortfolioPeriodResult.Available(amount = 90.0, fraction = 0.1, partial = false),
                month = PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
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
    fun inaktuell_kurs_visar_kurs_ej_uppdaterad_ist_for_falsk_noll() {
        // Regression för issue #18.
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            totalValue = 500.0,
            totalGainLoss = 100.0,
            totalGainLossFraction = 0.2,
            performance = PortfolioPerformanceCalc.PortfolioPerformance(
                day = PortfolioPerformanceCalc.PortfolioPeriodResult.StalePrice,
                week = PortfolioPerformanceCalc.PortfolioPeriodResult.StalePrice,
                month = PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
            ),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onAllNodesWithText("Kurs ej uppdaterad").assertCountEquals(2)
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
                day = PortfolioPerformanceCalc.PortfolioPeriodResult.Available(amount = 10.0, fraction = 0.01, partial = true),
                week = PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
                month = PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
            ),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("Delvis osäker — någon fond saknar historik").assertExists()
    }

    @Test
    fun analys_summeringskort_visar_tomt_tillstand_utan_flaggade_fonder() {
        composeRule.setContent {
            FonderTheme { HemContent(state = HemUiState(loading = false, hasHoldings = true)) }
        }

        composeRule.onNodeWithText("Inga fonder flaggade").assertExists()
    }

    @Test
    fun analys_summeringskort_visar_flaggad_fond_och_navigerar_vid_klick() {
        val fund = Fund(fundId = "SHB0000442", name = "Flaggad Fond")
        val analysis = sampleAnalysis(FundAnalysisCalc.SignalLevel.GUL)
        var clickedFundId: String? = null

        composeRule.setContent {
            FonderTheme {
                HemContent(
                    state = HemUiState(
                        loading = false,
                        hasHoldings = true,
                        analysisSummary = AnalysisSummary(gulCount = 1, flagged = listOf(FlaggedHolding(fund, analysis))),
                    ),
                    onFundClick = { clickedFundId = it },
                )
            }
        }

        composeRule.onNodeWithText("Flaggad Fond").assertExists()
        composeRule.onNodeWithText("Under 200-dagars snitt").assertExists()

        composeRule.onNodeWithText("Flaggad Fond").performClick()
        assertEquals("SHB0000442", clickedFundId)
    }

    private fun sampleAnalysis(status: FundAnalysisCalc.SignalLevel) = FundAnalysisCalc.Analysis(
        keyFigures = FundAnalysisCalc.KeyFigures(
            periodReturns = FundAnalysisCalc.Period.entries.map { FundAnalysisCalc.PeriodReturn(it, null, null) },
            cagr = null,
            currentNav = 100.0,
            gavPerShare = 100.0,
            gavFraction = 0.0,
            portfolioShareFraction = null,
        ),
        distanceFromHigh = null,
        trend = FundAnalysisCalc.TrendSignal(status),
        momentum = null,
        status = status,
    )
}
