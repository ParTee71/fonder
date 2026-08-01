package se.partee71.fonder.ui.hem

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.PortfolioFeeCalc
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import se.partee71.fonder.domain.usecase.PortfolioRiskCalc
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
            navEpochDay = java.time.LocalDate.of(2026, 7, 10).toEpochDay(), // POR-7, issue #27
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("500,00 kr", substring = true).assertExists()
        composeRule.onNodeWithText("+20,0 % · 100,00 kr", substring = true).assertExists()
        composeRule.onNodeWithText("+5,0 % · 50,00 kr", substring = true).assertExists()
        composeRule.onNodeWithText("Otillräcklig data").assertExists()
        composeRule.onNodeWithText("Värde per 2026-07-10").assertExists()
    }

    @Test
    fun otillracklig_historik_visar_otillracklig_data_ist_for_falsk_noll() {
        // Räcker inte historiken för en period markeras den "Otillräcklig data" — aldrig ett gissat 0.
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            totalValue = 500.0,
            totalGainLoss = 100.0,
            totalGainLossFraction = 0.2,
            performance = PortfolioPerformanceCalc.PortfolioPerformance(
                day = PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
                week = PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
                month = PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
            ),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onAllNodesWithText("Otillräcklig data").assertCountEquals(3)
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

    // --- Fondavgifter (HEM-5, issue #60) ---

    @Test
    fun fondavgiftskort_visar_totalen_och_forklaringstexten() {
        // Under 1000 kr — tusentalsavgränsaren kan vara vanligt eller hårt blanksteg (se MoneyFormatTest).
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            feeSummary = PortfolioFeeCalc.Result(totalAnnualFeeKr = 500.0, byHolding = emptyList(), unknownFeeCount = 0),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("Fondavgifter per år").assertExists()
        composeRule.onNodeWithText("500,00 kr", substring = true).assertExists()
        composeRule.onNodeWithText("Redan avdraget löpande ur fondernas kurs", substring = true).assertExists()
    }

    @Test
    fun fondavgiftskort_visar_antal_fonder_utan_kand_avgift() {
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            feeSummary = PortfolioFeeCalc.Result(totalAnnualFeeKr = 0.0, byHolding = emptyList(), unknownFeeCount = 2),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("2 fond(er) saknar känd avgift", substring = true).assertExists()
    }

    @Test
    fun fondavgiftskort_visar_ingen_okand_avgift_text_nar_alla_kanda() {
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            feeSummary = PortfolioFeeCalc.Result(totalAnnualFeeKr = 500.0, byHolding = emptyList(), unknownFeeCount = 0),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("saknar känd avgift", substring = true).assertDoesNotExist()
    }

    @Test
    fun fondavgiftskort_visar_rad_per_innehav_sorterat_och_navigerar_vid_klick() {
        val dyr = Fund(fundId = "DYR", name = "Dyr Fond")
        val billig = Fund(fundId = "BIL", name = "Billig Fond")
        var clickedFundId: String? = null
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            feeSummary = PortfolioFeeCalc.Result(
                totalAnnualFeeKr = 900.0,
                // Redan sorterat störst avgift först av PortfolioFeeCalc.compute — kortet
                // ska bara rendera i den ordningen, inte sortera om.
                byHolding = listOf(
                    PortfolioFeeCalc.HoldingFee(dyr, annualFeeKr = 700.0),
                    PortfolioFeeCalc.HoldingFee(billig, annualFeeKr = 200.0),
                ),
                unknownFeeCount = 0,
            ),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state, onFundClick = { clickedFundId = it }) }
        }

        composeRule.onNodeWithText("Dyr Fond").assertExists()
        composeRule.onNodeWithText("700,00 kr", substring = true).assertExists()
        composeRule.onNodeWithText("Billig Fond").assertExists()
        composeRule.onNodeWithText("200,00 kr", substring = true).assertExists()

        composeRule.onNodeWithText("Billig Fond").performClick()
        assertEquals("BIL", clickedFundId)
    }

    // --- Samlad besparingspotential (HEM-6, issue #61) ---

    @Test
    fun fondavgiftskort_visar_samlad_besparing_och_antal_genomsokta() {
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            feeSummary = PortfolioFeeCalc.Result(
                totalAnnualFeeKr = 900.0,
                byHolding = emptyList(),
                unknownFeeCount = 0,
                totalAnnualSavingsKr = 300.0,
                comparedCount = 2,
                comparableCount = 3,
            ),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("300,00 kr", substring = true).assertExists()
        composeRule.onNodeWithText("2 av 3", substring = true).assertExists()
    }

    @Test
    fun fondavgiftskort_visar_ingen_besparingstext_utan_jamforbara_innehav() {
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            feeSummary = PortfolioFeeCalc.Result(totalAnnualFeeKr = 0.0, byHolding = emptyList(), unknownFeeCount = 0),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("Möjlig besparing", substring = true).assertDoesNotExist()
    }

    @Test
    fun avgiftsrad_visar_besparing_nar_ett_farskt_billigare_alternativ_finns() {
        val dyr = Fund(fundId = "DYR", name = "Dyr Fond")
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            feeSummary = PortfolioFeeCalc.Result(
                totalAnnualFeeKr = 700.0,
                byHolding = listOf(PortfolioFeeCalc.HoldingFee(dyr, annualFeeKr = 700.0, annualSavingsKr = 350.0)),
                unknownFeeCount = 0,
                totalAnnualSavingsKr = 350.0,
                comparedCount = 1,
                comparableCount = 1,
            ),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("Kan spara 350,00 kr/år", substring = true).assertExists()
    }

    @Test
    fun avgiftsrad_visar_ingen_text_alls_for_ett_aldrig_sokt_innehav() {
        val dyr = Fund(fundId = "DYR", name = "Dyr Fond")
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            feeSummary = PortfolioFeeCalc.Result(
                totalAnnualFeeKr = 700.0,
                // wasCompared=false (standard) — aldrig sökt, eller ett utgånget resultat.
                byHolding = listOf(PortfolioFeeCalc.HoldingFee(dyr, annualFeeKr = 700.0, annualSavingsKr = null)),
                unknownFeeCount = 0,
            ),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("Kan spara", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Redan bland de billigaste", substring = true).assertDoesNotExist()
    }

    @Test
    fun avgiftsrad_visar_redan_bland_de_billigaste_nar_genomsokt_utan_traff() {
        // Skilt tillstånd från "aldrig sökt" — samma text som ANA-9:s eget kort i Fonddetalj
        // (regel 4), så det inte ser ut som att innehavet aldrig genomsökts.
        val dyr = Fund(fundId = "DYR", name = "Dyr Fond")
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            feeSummary = PortfolioFeeCalc.Result(
                totalAnnualFeeKr = 700.0,
                byHolding = listOf(PortfolioFeeCalc.HoldingFee(dyr, annualFeeKr = 700.0, annualSavingsKr = null, wasCompared = true)),
                unknownFeeCount = 0,
                comparedCount = 1,
                comparableCount = 1,
            ),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("Redan bland de billigaste", substring = true).assertExists()
        composeRule.onNodeWithText("Kan spara", substring = true).assertDoesNotExist()
    }

    // --- Skrollbarhet (UI-5, issue #63) ---

    @Test
    fun hem_ar_skrollbar_nar_innehallet_overstiger_skarmen() {
        // Ett generöst antal flaggade fonder — inte den minsta möjliga för att just överstiga
        // en uppskattad skärmhöjd (för sköra tal beroende på exakt Material3-metrik), utan
        // gott om marginal så testet är robust oavsett enhetens faktiska viewport.
        val funds = (1..10).map { i -> Fund(fundId = "F$i", name = "Flaggad Fond $i") }
        val analysis = sampleAnalysis(FundAnalysisCalc.SignalLevel.GUL)
        val flagged = funds.map { FlaggedHolding(it, analysis) }

        composeRule.setContent {
            FonderTheme {
                HemContent(
                    state = HemUiState(
                        loading = false,
                        hasHoldings = true,
                        analysisSummary = AnalysisSummary(gulCount = flagged.size, flagged = flagged),
                    ),
                )
            }
        }

        // performScrollTo() kräver en skrollbar förälder — utan den (bugen i #63) skulle
        // testet aldrig nå fram till assertIsDisplayed() på den sista, annars avklippta raden.
        composeRule.onNodeWithText("Flaggad Fond 10").performScrollTo().assertIsDisplayed()
    }

    private fun sampleAnalysis(status: FundAnalysisCalc.SignalLevel) = FundAnalysisCalc.Analysis(
        keyFigures = FundAnalysisCalc.KeyFigures(
            periodReturns = FundAnalysisCalc.Period.entries.map { FundAnalysisCalc.PeriodReturn(it, null, null) },
            cagr = null,
            currentNav = 100.0,
            gavPerShare = 100.0,
            gavFraction = 0.0,
            portfolioShareFraction = null,
            annualizedVolatility = null,
            sharpeRatio = null,
        ),
        distanceFromHigh = null,
        trend = FundAnalysisCalc.TrendSignal(status),
        momentum = null,
        status = status,
        profitTake = null,
    )

    // --- Riskprofil (HEM-7, issue #68) ---

    @Test
    fun riskkortet_uteblir_helt_utan_sparad_profil() {
        composeRule.setContent {
            FonderTheme { HemContent(state = HemUiState(loading = false, hasHoldings = true, riskProfile = null)) }
        }

        composeRule.onNodeWithText("Riskprofil").assertDoesNotExist()
    }

    @Test
    fun riskkortet_visar_malniva_och_faktisk_niva() {
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            riskProfile = RiskProfile(targetRiskLevel = 5),
            portfolioRisk = PortfolioRiskCalc.Result(weightedAverageRisk = 3.5, includedValueKr = 1000.0, excludedCount = 0),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("Riskprofil").assertExists()
        composeRule.onNodeWithText("5").assertExists()
        composeRule.onNodeWithText("3,50").assertExists()
    }

    @Test
    fun riskkortet_visar_otillrackligt_data_nar_ingen_faktisk_niva_kunde_beraknas() {
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            riskProfile = RiskProfile(targetRiskLevel = 4),
            portfolioRisk = PortfolioRiskCalc.Result(weightedAverageRisk = null, includedValueKr = 0.0, excludedCount = 1),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("Otillräcklig data").assertExists()
        composeRule.onNodeWithText("1 fond(er) saknar känd risknivå", substring = true).assertExists()
    }
}
