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
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
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
                    day = PortfolioPerformanceCalc.PeriodResult.Available(amount = 30.0, fraction = 0.03),
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

    @Test
    fun stale_price_visar_kurs_ej_uppdaterad_ist_for_falsk_noll() {
        // Regression för issue #18: en inaktuell kurs ska aldrig se ut som "+0,0 % · 0,00 kr".
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1100.0)
        val state = PortfoljUiState(
            loading = false,
            holdings = listOf(holding),
            performance = mapOf(
                fond.fundId to PortfolioPerformanceCalc.HoldingPerformance(
                    day = PortfolioPerformanceCalc.PeriodResult.StalePrice,
                    week = PortfolioPerformanceCalc.PeriodResult.StalePrice,
                    month = null,
                ),
            ),
        )

        composeRule.setContent {
            FonderTheme { PortfoljContent(state = state, onFundClick = {}) }
        }

        composeRule.onAllNodesWithText("Kurs ej uppdaterad", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun forsta_kop_och_inkopsvarde_visas_for_innehav() {
        // netInvested < 1000 undviker tusentalsavgränsarens tvetydiga blanksteg (vanligt vs
        // hårt) i formaterad text — se MoneyFormatTest.
        val holding = Holding(
            fund = fond,
            netShares = 10.0,
            netInvested = 500.0,
            currentValue = 1100.0,
            firstPurchaseEpochDay = java.time.LocalDate.of(2024, 3, 15).toEpochDay(),
        )
        val state = PortfoljUiState(loading = false, holdings = listOf(holding), performance = emptyMap())

        composeRule.setContent {
            FonderTheme { PortfoljContent(state = state, onFundClick = {}) }
        }

        composeRule.onNodeWithText("Första köp 2024-03-15 · Inköpsvärde 500,00 kr", substring = true).assertExists()
    }

    @Test
    fun visar_varde_per_nav_datum_for_total_och_innehav() {
        // POR-7, issue #27 — en normal endagsförskjutning mot en extern källa (t.ex. banken)
        // ska vara begriplig, inte se ut som ett fel.
        val holding = Holding(
            fund = fond,
            netShares = 10.0,
            netInvested = 1000.0,
            currentValue = 1100.0,
            navEpochDay = java.time.LocalDate.of(2026, 7, 10).toEpochDay(),
        )
        val state = PortfoljUiState(
            loading = false,
            holdings = listOf(holding),
            totalValue = 1100.0,
            totalGainLossFraction = 0.1,
            navEpochDay = java.time.LocalDate.of(2026, 7, 10).toEpochDay(),
        )

        composeRule.setContent {
            FonderTheme { PortfoljContent(state = state, onFundClick = {}) }
        }

        composeRule.onAllNodesWithText("Värde per 2026-07-10", useUnmergedTree = true).assertCountEquals(2)
    }

    private fun keyFigures(gavFraction: Double? = 0.6) = FundAnalysisCalc.KeyFigures(
        periodReturns = FundAnalysisCalc.Period.entries.map { FundAnalysisCalc.PeriodReturn(it, amount = null, fraction = null) },
        cagr = null,
        currentNav = 160.0,
        gavPerShare = 100.0,
        gavFraction = gavFraction,
        portfolioShareFraction = null,
        annualizedVolatility = null,
        sharpeRatio = null,
    )

    @Test
    fun vinstsignal_badge_visas_pa_kort_med_triggad_vinstsignal() {
        // POR-8/ANA-8, issue #26.
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1600.0)
        val analysis = FundAnalysisCalc.Analysis(
            keyFigures = keyFigures(),
            distanceFromHigh = null,
            trend = null,
            momentum = null,
            status = null,
            profitTake = FundAnalysisCalc.ProfitTakeSignal(triggered = true, gainFraction = 0.6),
        )
        val state = PortfoljUiState(
            loading = false,
            holdings = listOf(holding),
            analysis = mapOf(fond.fundId to analysis),
        )

        composeRule.setContent {
            FonderTheme { PortfoljContent(state = state, onFundClick = {}) }
        }

        composeRule.onNodeWithText("Vinstläge", substring = true).assertExists()
    }

    @Test
    fun vinstsignal_badge_visas_inte_utan_triggad_signal_eller_analys() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1100.0)
        val analysis = FundAnalysisCalc.Analysis(
            keyFigures = keyFigures(gavFraction = 0.1),
            distanceFromHigh = null,
            trend = null,
            momentum = null,
            status = null,
            profitTake = FundAnalysisCalc.ProfitTakeSignal(triggered = false, gainFraction = 0.1),
        )
        val state = PortfoljUiState(
            loading = false,
            holdings = listOf(holding),
            analysis = mapOf(fond.fundId to analysis),
        )

        composeRule.setContent {
            FonderTheme { PortfoljContent(state = state, onFundClick = {}) }
        }

        composeRule.onNodeWithText("Vinstläge", substring = true).assertDoesNotExist()
    }
}
