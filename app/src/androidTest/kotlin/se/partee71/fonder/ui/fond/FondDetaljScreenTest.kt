package se.partee71.fonder.ui.fond

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Instrumenterat test av Fonddetaljs tillståndsdrivna innehåll (issue #16) — bygger
 * [FondDetaljUiState] direkt i stället för att gå via ett riktigt [FondDetaljViewModel]/Hilt,
 * samma mönster som [se.partee71.fonder.ui.hem.HemScreenTest]/[se.partee71.fonder.ui.portfolj.PortfoljScreenTest].
 * Fokuserar på Analys-sektionen (ANA-1–ANA-4) — kurshistorik/diagram täcks inte här.
 */
@RunWith(AndroidJUnit4::class)
class FondDetaljScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Behövs för att `FondDetaljUiState.isEmpty` ska vara falskt så huvudinnehållet (inkl. Analys) renderas. */
    private val prices = listOf(FundPrice(fundId = "SHB0000442", epochDay = 100, nav = 120.0))

    private fun keyFigures(
        cagr: Double? = 0.05,
        portfolioShareFraction: Double? = 0.25,
        annualizedVolatility: Double? = 0.18,
        sharpeRatio: Double? = 0.8,
    ) = FundAnalysisCalc.KeyFigures(
        periodReturns = FundAnalysisCalc.Period.entries.map { FundAnalysisCalc.PeriodReturn(it, amount = 10.0, fraction = 0.05) },
        cagr = cagr,
        currentNav = 120.0,
        gavPerShare = 100.0,
        gavFraction = 0.2,
        portfolioShareFraction = portfolioShareFraction,
        annualizedVolatility = annualizedVolatility,
        sharpeRatio = sharpeRatio,
    )

    @Test
    fun visar_ingen_analys_sektion_utan_beraknat_resultat() {
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = FondDetaljUiState(loading = false, fundName = "Fond A", prices = prices, analysis = null))
            }
        }

        composeRule.onNodeWithText("Fond A").assertExists() // huvudinnehållet renderas
        composeRule.onNodeWithText("Analys").assertDoesNotExist()
    }

    @Test
    fun visar_gron_status_utan_triggertexter_nar_inga_signaler_ar_aktiva() {
        val analysis = FundAnalysisCalc.Analysis(
            keyFigures = keyFigures(),
            distanceFromHigh = FundAnalysisCalc.DistanceFromHighSignal(FundAnalysisCalc.SignalLevel.GRON, 0.0),
            trend = FundAnalysisCalc.TrendSignal(FundAnalysisCalc.SignalLevel.GRON),
            momentum = null,
            status = FundAnalysisCalc.SignalLevel.GRON,
        )
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = FondDetaljUiState(loading = false, fundName = "Fond A", prices = prices, analysis = analysis))
            }
        }

        composeRule.onNodeWithText("Analys").assertExists()
        composeRule.onNodeWithText("Inga signaler").assertExists()
    }

    @Test
    fun visar_rod_status_med_triggertexter_for_avstand_och_trend() {
        val analysis = FundAnalysisCalc.Analysis(
            keyFigures = keyFigures(),
            distanceFromHigh = FundAnalysisCalc.DistanceFromHighSignal(FundAnalysisCalc.SignalLevel.ROD, -0.25),
            trend = FundAnalysisCalc.TrendSignal(FundAnalysisCalc.SignalLevel.GUL),
            momentum = null,
            status = FundAnalysisCalc.SignalLevel.ROD,
        )
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = FondDetaljUiState(loading = false, fundName = "Fond A", prices = prices, analysis = analysis))
            }
        }

        composeRule.onNodeWithText("Bör ses över").assertExists()
        composeRule.onNodeWithText("från toppen (52 veckor)", substring = true).assertExists()
        composeRule.onNodeWithText("Under 200-dagars snitt", substring = true).assertExists()
    }

    @Test
    fun visar_otillrackligt_data_i_banner_nar_ingen_signal_kunde_beraknas() {
        val analysis = FundAnalysisCalc.Analysis(
            keyFigures = keyFigures(),
            distanceFromHigh = null,
            trend = null,
            momentum = null,
            status = null,
        )
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = FondDetaljUiState(loading = false, fundName = "Fond A", prices = prices, analysis = analysis))
            }
        }

        composeRule.onNodeWithText("Otillräcklig kurshistorik för säljsignal ännu").assertExists()
    }

    @Test
    fun visar_otillrackligt_data_for_ett_enskilt_nyckeltal_i_stallet_for_gissat_varde() {
        // CAGR null (innehav < 1 år) — ANA-1/ANA-4: markeras, gissas aldrig.
        val analysis = FundAnalysisCalc.Analysis(
            keyFigures = keyFigures(cagr = null),
            distanceFromHigh = FundAnalysisCalc.DistanceFromHighSignal(FundAnalysisCalc.SignalLevel.GRON, 0.0),
            trend = FundAnalysisCalc.TrendSignal(FundAnalysisCalc.SignalLevel.GRON),
            momentum = null,
            status = FundAnalysisCalc.SignalLevel.GRON,
        )
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = FondDetaljUiState(loading = false, fundName = "Fond A", prices = prices, analysis = analysis))
            }
        }

        composeRule.onNodeWithText("Årlig snittavkastning (CAGR)").assertExists()
        composeRule.onNodeWithText("Otillräcklig data").assertExists()
    }

    @Test
    fun visar_forsta_kop_och_inkopsvarde_for_ett_kvarvarande_innehav() {
        // POR-6, issue #18. netInvested < 1000 undviker tusentalsavgränsarens tvetydiga
        // blanksteg (se MoneyFormatTest).
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = FondDetaljUiState(
                        loading = false,
                        fundName = "Fond A",
                        prices = prices,
                        firstPurchaseEpochDay = java.time.LocalDate.of(2024, 3, 15).toEpochDay(),
                        netInvested = 500.0,
                        analysis = null,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Första köp 2024-03-15 · Inköpsvärde 500,00 kr", substring = true).assertExists()
    }

    @Test
    fun visar_ingen_forsta_kop_rad_utan_kvarvarande_innehav() {
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = FondDetaljUiState(loading = false, fundName = "Fond A", prices = prices, analysis = null))
            }
        }

        composeRule.onNodeWithText("Första köp", substring = true).assertDoesNotExist()
    }

    // --- Pedagogiskt lager (issue #22, ANA-5/ANA-6) ---

    @Test
    fun visar_kontextkort_nar_under_toppen_men_plus_mot_gav() {
        // Gul avståndssignal men fortfarande plus mot GAV → uppmuntrande kontext (ANA-6).
        val analysis = FundAnalysisCalc.Analysis(
            keyFigures = keyFigures().copy(gavFraction = 0.06),
            distanceFromHigh = FundAnalysisCalc.DistanceFromHighSignal(FundAnalysisCalc.SignalLevel.GUL, -0.12),
            trend = FundAnalysisCalc.TrendSignal(FundAnalysisCalc.SignalLevel.GRON),
            momentum = null,
            status = FundAnalysisCalc.SignalLevel.GUL,
        )
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = FondDetaljUiState(loading = false, fundName = "Fond A", prices = prices, analysis = analysis))
            }
        }

        composeRule.onNodeWithText("fortfarande i plus mot ditt snittpris", substring = true).assertExists()
    }

    @Test
    fun kan_falla_ut_forklaring_for_en_signal() {
        val analysis = FundAnalysisCalc.Analysis(
            keyFigures = keyFigures(),
            distanceFromHigh = null,
            trend = FundAnalysisCalc.TrendSignal(FundAnalysisCalc.SignalLevel.GUL),
            momentum = null,
            status = FundAnalysisCalc.SignalLevel.GUL,
        )
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = FondDetaljUiState(loading = false, fundName = "Fond A", prices = prices, analysis = analysis))
            }
        }

        // Förklaringen är dold tills raden fälls ut.
        composeRule.onNodeWithText("svaghetstecken på medellång sikt", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Kurs mot 200-dagars snitt").performClick()
        composeRule.onNodeWithText("svaghetstecken på medellång sikt", substring = true).assertExists()
    }

    @Test
    fun visar_ordlista_och_kan_falla_ut_en_term() {
        val analysis = FundAnalysisCalc.Analysis(
            keyFigures = keyFigures(),
            distanceFromHigh = FundAnalysisCalc.DistanceFromHighSignal(FundAnalysisCalc.SignalLevel.GRON, 0.0),
            trend = FundAnalysisCalc.TrendSignal(FundAnalysisCalc.SignalLevel.GRON),
            momentum = null,
            status = FundAnalysisCalc.SignalLevel.GRON,
        )
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = FondDetaljUiState(loading = false, fundName = "Fond A", prices = prices, analysis = analysis))
            }
        }

        composeRule.onNodeWithText("Så funkar analysen").assertExists()
        composeRule.onNodeWithText("ränta-på-ränta", substring = true).assertDoesNotExist()
        // Ordlistan ligger längst ned — scrolla in raden innan den kan klickas/fällas ut.
        composeRule.onNodeWithText("CAGR (årlig snittavkastning)").performScrollTo().performClick()
        composeRule.onNodeWithText("jämna årstakt", substring = true).assertExists()
    }

    @Test
    fun kontexttexterna_ger_aldrig_ett_direkt_saljbud() {
        // ANA-3-vakt: språket är neutralt/förklarande, aldrig en imperativ "sälj nu"/"köp mer nu".
        val analysis = FundAnalysisCalc.Analysis(
            keyFigures = keyFigures().copy(gavFraction = -0.1),
            distanceFromHigh = FundAnalysisCalc.DistanceFromHighSignal(FundAnalysisCalc.SignalLevel.ROD, -0.25),
            trend = FundAnalysisCalc.TrendSignal(FundAnalysisCalc.SignalLevel.GUL),
            momentum = FundAnalysisCalc.MomentumSignal(FundAnalysisCalc.SignalLevel.GUL, -7.0),
            status = FundAnalysisCalc.SignalLevel.ROD,
        )
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = FondDetaljUiState(loading = false, fundName = "Fond A", prices = prices, analysis = analysis))
            }
        }

        composeRule.onNodeWithText("Sälj nu", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Köp mer nu", substring = true).assertDoesNotExist()
        // Kontextkortet ska däremot finnas och vara neutralt formulerat.
        composeRule.onNodeWithText("kan det vara läge att låta tiden verka", substring = true).assertExists()
    }

    // --- Riskmått (issue #24, ANA-7) ---

    @Test
    fun visar_volatilitet_och_sharpe_nar_historiken_racker() {
        val analysis = FundAnalysisCalc.Analysis(
            keyFigures = keyFigures(annualizedVolatility = 0.18, sharpeRatio = 0.8),
            distanceFromHigh = FundAnalysisCalc.DistanceFromHighSignal(FundAnalysisCalc.SignalLevel.GRON, 0.0),
            trend = FundAnalysisCalc.TrendSignal(FundAnalysisCalc.SignalLevel.GRON),
            momentum = null,
            status = FundAnalysisCalc.SignalLevel.GRON,
        )
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = FondDetaljUiState(loading = false, fundName = "Fond A", prices = prices, analysis = analysis))
            }
        }

        composeRule.onNodeWithText("Volatilitet (årlig)").assertExists()
        composeRule.onNodeWithText("18,0 %").assertExists()
        composeRule.onNodeWithText("0,80").assertExists()
    }

    @Test
    fun visar_otillrackligt_data_for_riskmatt_utan_tillracklig_historik() {
        val analysis = FundAnalysisCalc.Analysis(
            keyFigures = keyFigures(annualizedVolatility = null, sharpeRatio = null),
            distanceFromHigh = FundAnalysisCalc.DistanceFromHighSignal(FundAnalysisCalc.SignalLevel.GRON, 0.0),
            trend = FundAnalysisCalc.TrendSignal(FundAnalysisCalc.SignalLevel.GRON),
            momentum = null,
            status = FundAnalysisCalc.SignalLevel.GRON,
        )
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = FondDetaljUiState(loading = false, fundName = "Fond A", prices = prices, analysis = analysis))
            }
        }

        composeRule.onNodeWithText("Volatilitet (årlig)").assertExists()
        // Båda riskmåtten saknar värde → markeras som otillräcklig data (ANA-4), inget gissat 0.
        assertTrue(composeRule.onAllNodesWithText("Otillräcklig data").fetchSemanticsNodes().size >= 2)
    }
}
