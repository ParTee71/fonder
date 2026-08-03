package se.partee71.fonder.ui.portfolj

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.PortfolioExposureCalc
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
    fun otillracklig_historik_visar_otillracklig_data_ist_for_falsk_noll() {
        // En period utan tillräcklig historik ska aldrig se ut som "+0,0 % · 0,00 kr".
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1100.0)
        val state = PortfoljUiState(
            loading = false,
            holdings = listOf(holding),
            performance = mapOf(
                fond.fundId to PortfolioPerformanceCalc.HoldingPerformance(
                    day = PortfolioPerformanceCalc.PeriodResult.InsufficientHistory,
                    week = PortfolioPerformanceCalc.PeriodResult.InsufficientHistory,
                    month = null,
                ),
            ),
        )

        composeRule.setContent {
            FonderTheme { PortfoljContent(state = state, onFundClick = {}) }
        }

        composeRule.onAllNodesWithText("Otillräcklig data", useUnmergedTree = true).assertCountEquals(3)
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

    // --- Exponeringskarta (POR-9, issue #66) ---

    private val emptyDimension = PortfolioExposureCalc.Dimension(buckets = emptyList(), unknownValueKr = 0.0, unknownFraction = 0.0, unknownCount = 0)
    private val emptyIndexStatus = PortfolioExposureCalc.IndexStatusSplit(indexValueKr = 0.0, indexFraction = 0.0, activeValueKr = 0.0, activeFraction = 0.0)

    @Test
    fun exponeringskortet_visar_ratt_kategorier_och_procent() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1000.0)
        val exposure = PortfolioExposureCalc.Result(
            byType = PortfolioExposureCalc.Dimension(
                buckets = listOf(
                    PortfolioExposureCalc.Bucket("Aktiefond", 800.0, 0.8),
                    PortfolioExposureCalc.Bucket("Räntefond", 200.0, 0.2),
                ),
                unknownValueKr = 0.0, unknownFraction = 0.0, unknownCount = 0,
            ),
            byRegion = PortfolioExposureCalc.Dimension(
                buckets = listOf(PortfolioExposureCalc.Bucket("Sverige", 1000.0, 1.0)),
                unknownValueKr = 0.0, unknownFraction = 0.0, unknownCount = 0,
            ),
            indexStatus = PortfolioExposureCalc.IndexStatusSplit(indexValueKr = 300.0, indexFraction = 0.3, activeValueKr = 700.0, activeFraction = 0.7),
            includedValueKr = 1000.0,
            excludedCount = 0,
        )
        val state = PortfoljUiState(loading = false, holdings = listOf(holding), exposure = exposure)

        composeRule.setContent {
            FonderTheme { PortfoljContent(state = state, onFundClick = {}) }
        }

        composeRule.onNodeWithText("Aktiefond").assertExists()
        composeRule.onNodeWithText("80,0 %").assertExists()
        composeRule.onNodeWithText("Räntefond").assertExists()
        composeRule.onNodeWithText("20,0 %").assertExists()
        composeRule.onNodeWithText("Sverige").assertExists()
        composeRule.onNodeWithText("Indexfond").assertExists()
        composeRule.onNodeWithText("30,0 %").assertExists()
        composeRule.onNodeWithText("Aktivt förvaltad").assertExists()
        composeRule.onNodeWithText("70,0 %").assertExists()
    }

    @Test
    fun risknivadimensionen_visas_stigande_pa_niva() {
        // Störst värde ligger medvetet på den högsta nivån — en fallande värde-sortering hade
        // gett omvänd radordning; POR-9 kräver stigande på nivå i stället (issue #71).
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1000.0)
        val exposure = PortfolioExposureCalc.Result(
            byType = emptyDimension,
            byRegion = emptyDimension,
            byRiskLevel = PortfolioExposureCalc.Dimension(
                buckets = listOf(
                    PortfolioExposureCalc.Bucket("3", 100.0, 0.1),
                    PortfolioExposureCalc.Bucket("6", 900.0, 0.9),
                ),
                unknownValueKr = 0.0, unknownFraction = 0.0, unknownCount = 0,
            ),
            indexStatus = emptyIndexStatus,
            includedValueKr = 1000.0,
            excludedCount = 0,
        )
        val state = PortfoljUiState(loading = false, holdings = listOf(holding), exposure = exposure)

        composeRule.setContent {
            FonderTheme { PortfoljContent(state = state, onFundClick = {}) }
        }

        composeRule.onNodeWithText("Risknivå").assertExists()
        val riskLevelNodes = composeRule.onAllNodesWithText("3", substring = false)
        riskLevelNodes.assertCountEquals(1)
        composeRule.onNodeWithText("6").assertExists()
        composeRule.onNodeWithText("10,0 %").assertExists()
        composeRule.onNodeWithText("90,0 %").assertExists()
    }

    @Test
    fun okand_region_visas_separat_och_tydligt_markt() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1000.0)
        val exposure = PortfolioExposureCalc.Result(
            byType = emptyDimension,
            byRegion = PortfolioExposureCalc.Dimension(
                buckets = listOf(PortfolioExposureCalc.Bucket("Sverige", 600.0, 0.6)),
                unknownValueKr = 400.0, unknownFraction = 0.4, unknownCount = 1,
            ),
            indexStatus = emptyIndexStatus,
            includedValueKr = 1000.0,
            excludedCount = 0,
        )
        val state = PortfoljUiState(loading = false, holdings = listOf(holding), exposure = exposure)

        composeRule.setContent {
            FonderTheme { PortfoljContent(state = state, onFundClick = {}) }
        }

        composeRule.onNodeWithText("Sverige").assertExists()
        composeRule.onNodeWithText("Okänd region").assertExists()
        composeRule.onNodeWithText("40,0 %").assertExists()
    }

    @Test
    fun excludedCount_text_visas_nar_over_noll_och_uteblir_nar_noll() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1000.0)
        val exposureUtanExkludering = PortfolioExposureCalc.Result(
            byType = emptyDimension, byRegion = emptyDimension, indexStatus = emptyIndexStatus,
            includedValueKr = 1000.0, excludedCount = 0,
        )
        val stateUtan = PortfoljUiState(loading = false, holdings = listOf(holding), exposure = exposureUtanExkludering)

        composeRule.setContent {
            FonderTheme { PortfoljContent(state = stateUtan, onFundClick = {}) }
        }

        composeRule.onNodeWithText("räknas inte in i exponeringen", substring = true).assertDoesNotExist()
    }

    @Test
    fun excludedCount_text_visas_nar_innehav_ar_exkluderade() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1000.0)
        val exposureMedExkludering = PortfolioExposureCalc.Result(
            byType = emptyDimension, byRegion = emptyDimension, indexStatus = emptyIndexStatus,
            includedValueKr = 1000.0, excludedCount = 2,
        )
        val stateMed = PortfoljUiState(loading = false, holdings = listOf(holding), exposure = exposureMedExkludering)

        composeRule.setContent {
            FonderTheme { PortfoljContent(state = stateMed, onFundClick = {}) }
        }

        composeRule.onNodeWithText("2 innehav", substring = true).assertExists()
    }

    @Test
    fun portfolj_ar_skrollbar_med_exponeringskort_och_manga_innehav() {
        // Exponeringskortet ligger som ett `item {}` i samma `LazyColumn` som innehavsraderna
        // (inte i en fast `Column` ovanför, se PortfoljContent) — annars skulle det äta
        // skärmhöjd permanent och kunna klippa bort de sista innehavsraderna (UI-5, issue #63).
        // Till skillnad från Hems flaggade fonder (allihop under en enda icke-lazy `Column`
        // inuti ett `item {}`) är innehavsraderna här riktiga lazy `items()` — den sista raden
        // är därför inte komponerad förrän listan faktiskt skrollas dit, så
        // `onNodeWithText(...).performScrollTo()` (som kräver att noden redan finns i
        // semantikträdet) skulle aldrig hitta den. `performScrollToIndex` på den taggade listan
        // är rätt verktyg för en genuint virtualiserad lista.
        val funds = (1..10).map { i -> Holding(fund = Fund(fundId = "F$i", name = "Innehav $i"), netShares = 1.0, netInvested = 100.0, currentValue = 100.0) }
        val state = PortfoljUiState(loading = false, holdings = funds)

        composeRule.setContent {
            FonderTheme { PortfoljContent(state = state, onFundClick = {}) }
        }

        // Index 0/1 = TotalCard/ExposureCard, holdings börjar på index 2 -> sista (10:e) på index 11.
        composeRule.onNodeWithTag(PORTFOLJ_LIST_TEST_TAG).performScrollToIndex(11)
        composeRule.onNodeWithText("Innehav 10").assertIsDisplayed()
    }
}
