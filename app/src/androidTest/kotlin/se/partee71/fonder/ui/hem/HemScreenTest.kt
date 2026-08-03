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
        composeRule.onNodeWithText("5,00").assertExists()
        composeRule.onNodeWithText("3,50").assertExists()
    }

    @Test
    fun riskkortet_visar_mal_mot_faktisk_per_niva() {
        // Andelarna är medvetet olika sinsemellan — annars kan flera ExposureBar-rader råka
        // rendera samma procenttext, vilket gör onNodeWithText tvetydig (kräver exakt en träff).
        val target = mapOf(3 to 0.31, 4 to 0.46, 5 to 0.23)
        val actual = mapOf(3 to 0.12, 4 to 0.53, 5 to 0.35)
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            riskProfile = RiskProfile(targetAllocation = target),
            portfolioRisk = PortfolioRiskCalc.Result(weightedAverageRisk = 4.0, includedValueKr = 1000.0, excludedCount = 0),
            riskLevelDeviations = PortfolioRiskCalc.deviationByLevel(target, actual),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("Mål mot faktisk fördelning").assertExists()
        composeRule.onNodeWithText("Nivå 3").assertExists()
        composeRule.onNodeWithText("Nivå 4").assertExists()
        composeRule.onNodeWithText("Nivå 5").assertExists()
        composeRule.onNodeWithText("31,0 %").assertExists()
        composeRule.onNodeWithText("46,0 %").assertExists()
        composeRule.onNodeWithText("23,0 %").assertExists()
        composeRule.onNodeWithText("12,0 %").assertExists()
        composeRule.onNodeWithText("53,0 %").assertExists()
        composeRule.onNodeWithText("35,0 %").assertExists()
    }

    @Test
    fun riskkortet_visar_bytesplanen_med_ratt_rangordning_och_avgiftsskillnad() {
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            riskProfile = RiskProfile(targetAllocation = mapOf(3 to 1.0)),
            portfolioRisk = PortfolioRiskCalc.Result(weightedAverageRisk = 5.0, includedValueKr = 10_000.0, excludedCount = 0),
            switchPlan = listOf(
                SwitchSuggestionUi(
                    recordId = 1, planIndex = 0, sellFundName = "Dyr fond", buyFundName = "Billig fond",
                    fromLevel = 5, toLevel = 3, feeDeltaPercent = -0.7, switchValueKr = 250.0,
                ),
            ),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("Bytesplan").assertExists()
        composeRule.onNodeWithText("1. Sälj Dyr fond (nivå 5) → Köp Billig fond (nivå 3)").assertExists()
        // Beloppet måste stå ut: bytet avser gapet, inte hela positionen (issue #75). Belopp
        // under tusen — tusentalsavgränsaren kan vara vanligt eller hårt blanksteg beroende på
        // JVM/lokal (se MoneyFormatTest), och ett test som beror på vilket vore flakigt.
        composeRule.onNodeWithText("Belopp: 250,00 kr", substring = true).assertExists()
        composeRule.onNodeWithText("Avgiftsskillnad: −0,7 %").assertExists()
    }

    @Test
    fun bytesplanens_rangordning_kommer_ur_planIndex_inte_listpositionen() {
        // Regression (issue #75): numret togs ur listpositionen, så ett byte vars föregångare
        // fallit bort presenterades som "1." — fast planen är girig och sekventiell, och att
        // följa byte 1 utan byte 0 flyttar portföljen bort från målet.
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            riskProfile = RiskProfile(targetAllocation = mapOf(3 to 1.0)),
            portfolioRisk = PortfolioRiskCalc.Result(weightedAverageRisk = 5.0, includedValueKr = 10_000.0, excludedCount = 0),
            switchPlan = listOf(
                SwitchSuggestionUi(
                    recordId = 1, planIndex = 1, sellFundName = "Dyr fond", buyFundName = "Billig fond",
                    fromLevel = 5, toLevel = 3, feeDeltaPercent = -0.7, switchValueKr = 250.0,
                ),
            ),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("2. Sälj Dyr fond (nivå 5) → Köp Billig fond (nivå 3)").assertExists()
    }

    @Test
    fun riskkortet_visar_bytesplan_utan_belopp_for_forslag_inspelade_fore_beloppsfaltet() {
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            riskProfile = RiskProfile(targetAllocation = mapOf(3 to 1.0)),
            portfolioRisk = PortfolioRiskCalc.Result(weightedAverageRisk = 5.0, includedValueKr = 10_000.0, excludedCount = 0),
            switchPlan = listOf(
                SwitchSuggestionUi(
                    recordId = 1, planIndex = 0, sellFundName = "Dyr fond", buyFundName = "Billig fond",
                    fromLevel = 5, toLevel = 3, feeDeltaPercent = -0.7, switchValueKr = null,
                ),
            ),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("1. Sälj Dyr fond (nivå 5) → Köp Billig fond (nivå 3)").assertExists()
        composeRule.onNodeWithText("Belopp:", substring = true).assertDoesNotExist()
    }

    @Test
    fun genomford_markeringen_i_bytesplanen_anropar_callbacken() {
        // SET-5 (issue #80): markeringen utför inget byte, den registrerar att användaren gjorde
        // det — annars kan facit inte skilja ett följt råd från ett bara givet.
        var callback: Pair<Long, Boolean>? = null
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            riskProfile = RiskProfile(targetAllocation = mapOf(3 to 1.0)),
            portfolioRisk = PortfolioRiskCalc.Result(weightedAverageRisk = 5.0, includedValueKr = 10_000.0, excludedCount = 0),
            switchPlan = listOf(
                SwitchSuggestionUi(
                    recordId = 42, planIndex = 0, sellFundName = "Dyr fond", buyFundName = "Billig fond",
                    fromLevel = 5, toLevel = 3, feeDeltaPercent = -0.7, switchValueKr = 250.0,
                ),
            ),
        )

        composeRule.setContent {
            FonderTheme {
                HemContent(state = state, onSwitchFollowedChange = { id, followed -> callback = id to followed })
            }
        }

        composeRule.onNodeWithText("Genomförd").performScrollTo().performClick()

        assertEquals(42L to true, callback)
    }

    @Test
    fun riskkortet_visar_ingen_bytesplan_utan_forslag() {
        val state = HemUiState(
            loading = false,
            hasHoldings = true,
            riskProfile = RiskProfile(targetAllocation = mapOf(3 to 1.0)),
            portfolioRisk = PortfolioRiskCalc.Result(weightedAverageRisk = 3.0, includedValueKr = 10_000.0, excludedCount = 0),
        )

        composeRule.setContent {
            FonderTheme { HemContent(state = state) }
        }

        composeRule.onNodeWithText("Bytesplan").assertDoesNotExist()
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

        // 3 kommer från PerformanceCard-kortets dag/vecka/månad (ingen performance-data satt i
        // testtillståndet), den fjärde är riskradens "otillräcklig data".
        composeRule.onAllNodesWithText("Otillräcklig data").assertCountEquals(4)
        composeRule.onNodeWithText("1 fond(er) saknar känd risknivå", substring = true).assertExists()
    }
}
