package se.partee71.fonder.ui.fond

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    ) = FundAnalysisCalc.KeyFigures(
        periodReturns = FundAnalysisCalc.Period.entries.map { FundAnalysisCalc.PeriodReturn(it, amount = 10.0, fraction = 0.05) },
        cagr = cagr,
        currentNav = 120.0,
        gavPerShare = 100.0,
        gavFraction = 0.2,
        portfolioShareFraction = portfolioShareFraction,
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
}
