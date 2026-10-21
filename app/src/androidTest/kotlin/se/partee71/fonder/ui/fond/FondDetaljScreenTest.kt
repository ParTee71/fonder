package se.partee71.fonder.ui.fond

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.MovingAverageCalc
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.common.DetailUiState

/**
 * Instrumenterat test av Fonddetaljs tillståndsdrivna innehåll (issue #16) — bygger
 * [FondDetaljUiState] direkt i stället för att gå via ett riktigt [FondDetaljViewModel]/Hilt,
 * samma mönster som [se.partee71.fonder.ui.hem.HemScreenTest]/[se.partee71.fonder.ui.portfolj.PortfoljScreenTest].
 *
 * Täcker analysen (ANA-1–ANA-7) och, sedan issue #85, kortets nya ordning: bytesavsnittet
 * överst (ANA-10), risknivån i rubriken (UI-10), jämförelsediagrammet vid utfällning (ANA-11)
 * och att den radvisa kurstabellen är borta (NAV-2). Analysens nyckeltal och ordlistan ligger
 * hopfällda, så testerna fäller ut sektionen innan de letar efter en rad i den.
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
        riskWindowYears: Int? = 3
    ) = FundAnalysisCalc.KeyFigures(
        periodReturns = FundAnalysisCalc.Period.entries.map { FundAnalysisCalc.PeriodReturn(it, amount = 10.0, fraction = 0.05) },
        cagr = cagr,
        currentNav = 120.0,
        gavPerShare = 100.0,
        gavFraction = 0.2,
        portfolioShareFraction = portfolioShareFraction,
        annualizedVolatility = annualizedVolatility,
        sharpeRatio = sharpeRatio,
        riskWindowYears = riskWindowYears
    )

    /** En analys med grön status och [keyFigures] om testet inte anger annat. */
    private fun analysis(
        keyFigures: FundAnalysisCalc.KeyFigures = keyFigures(),
        distanceFromHigh: FundAnalysisCalc.DistanceFromHighSignal? = FundAnalysisCalc.DistanceFromHighSignal(FundAnalysisCalc.SignalLevel.GRON, 0.0),
        trend: FundAnalysisCalc.TrendSignal? = FundAnalysisCalc.TrendSignal(FundAnalysisCalc.SignalLevel.GRON),
        momentum: FundAnalysisCalc.MomentumSignal? = null,
        status: FundAnalysisCalc.SignalLevel? = FundAnalysisCalc.SignalLevel.GRON
    ) = FundAnalysisCalc.Analysis(
        keyFigures = keyFigures,
        distanceFromHigh = distanceFromHigh,
        trend = trend,
        momentum = momentum,
        status = status,
        profitTake = null
    )

    /** Fonddetaljen för "Fond A" med kurserna ovan och [analysis]. */
    private fun showFund(analysis: FundAnalysisCalc.Analysis?) {
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = FondDetaljUiState(fund = DetailUiState.Content(FundDetail(fundName = "Fond A", prices = prices.toImmutableList(), analysis = analysis))))
            }
        }
    }

    /** Analysens nyckeltal ligger hopfällda (ANA-10) — fäll ut sektionen innan raderna söks. */
    private fun expandAnalysis() {
        composeRule.onNodeWithText("Analys").performScrollTo().performClick()
    }

    @Test
    fun visar_ingen_analys_sektion_utan_beraknat_resultat() {
        showFund(analysis = null)

        composeRule.onNodeWithText("Fond A").assertExists() // huvudinnehållet renderas
        composeRule.onNodeWithText("Analys").assertDoesNotExist()
    }

    @Test
    fun visar_gron_status_utan_triggertexter_nar_inga_signaler_ar_aktiva() {
        showFund(analysis())

        composeRule.onNodeWithText("Analys").assertExists()
        composeRule.onNodeWithText("Inga signaler").assertExists()
    }

    @Test
    fun visar_rod_status_med_triggertexter_for_avstand_och_trend() {
        showFund(analysis(distanceFromHigh = FundAnalysisCalc.DistanceFromHighSignal(FundAnalysisCalc.SignalLevel.ROD, -0.25), trend = FundAnalysisCalc.TrendSignal(FundAnalysisCalc.SignalLevel.GUL), status = FundAnalysisCalc.SignalLevel.ROD))

        composeRule.onNodeWithText("Bör ses över").assertExists()
        composeRule.onNodeWithText("från toppen (52 veckor)", substring = true).assertExists()
        composeRule.onNodeWithText("Under 200-dagars snitt", substring = true).assertExists()
    }

    @Test
    fun visar_otillrackligt_data_i_banner_nar_ingen_signal_kunde_beraknas() {
        showFund(analysis(distanceFromHigh = null, trend = null, status = null))

        composeRule.onNodeWithText("Otillräcklig kurshistorik för säljsignal ännu").assertExists()
    }

    @Test
    fun visar_otillrackligt_data_for_ett_enskilt_nyckeltal_i_stallet_for_gissat_varde() {
        // CAGR null (innehav < 1 år) — ANA-1/ANA-4: markeras, gissas aldrig.
        showFund(analysis(keyFigures = keyFigures(cagr = null)))

        expandAnalysis()
        composeRule.onNodeWithText("Årlig snittavkastning (CAGR)").performScrollTo().assertExists()
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
                        fund = DetailUiState.Content(
                            FundDetail(
                                fundName = "Fond A",
                                prices = prices.toImmutableList(),
                                firstPurchaseEpochDay = java.time.LocalDate.of(2024, 3, 15).toEpochDay(),
                                netInvested = 500.0,
                                analysis = null
                            )
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithText("Första köp 2024-03-15 · Inköpsvärde 500,00 kr", substring = true).assertExists()
    }

    @Test
    fun visar_200_dagarssnittet_i_kursdiagrammet_nar_historiken_racker() {
        // ANA-14: snittet räknas ur hela historiken, så också förvalda 1 mån har en snittlinje.
        // Längre än fönstret (MovingAverageCalc.WINDOW_DAYS = 290) så att snittserien inte är tom.
        val history = (0L..320L).map { FundPrice(fundId = "SHB0000442", epochDay = 20_000 + it, nav = 100.0 + it) }
        assertTrue(MovingAverageCalc.series(history.map { it.epochDay to it.nav }).isNotEmpty())
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = FondDetaljUiState(
                        fund = DetailUiState.Content(
                            FundDetail(
                                fundName = "Fond A",
                                prices = history.sortedByDescending { it.epochDay }.toImmutableList(),
                                movingAverage = MovingAverageCalc.series(history.map { it.epochDay to it.nav }).toImmutableList(),
                                analysis = null
                            )
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithText("200-dagarssnitt").performScrollTo().assertExists()
    }

    @Test
    fun visar_ingen_forsta_kop_rad_utan_kvarvarande_innehav() {
        showFund(analysis = null)

        composeRule.onNodeWithText("Första köp", substring = true).assertDoesNotExist()
    }

    // --- Pedagogiskt lager (issue #22, ANA-5/ANA-6) ---

    @Test
    fun visar_kontextkort_nar_under_toppen_men_plus_mot_gav() {
        // Gul avståndssignal men fortfarande plus mot GAV → uppmuntrande kontext (ANA-6).
        showFund(analysis(keyFigures = keyFigures().copy(gavFraction = 0.06), distanceFromHigh = FundAnalysisCalc.DistanceFromHighSignal(FundAnalysisCalc.SignalLevel.GUL, -0.12), status = FundAnalysisCalc.SignalLevel.GUL))

        composeRule.onNodeWithText("fortfarande i plus mot ditt snittpris", substring = true).assertExists()
    }

    @Test
    fun kan_falla_ut_forklaring_for_en_signal() {
        showFund(analysis(distanceFromHigh = null, trend = FundAnalysisCalc.TrendSignal(FundAnalysisCalc.SignalLevel.GUL), status = FundAnalysisCalc.SignalLevel.GUL))

        // Förklaringen är dold tills raden fälls ut.
        composeRule.onNodeWithText("svaghetstecken på medellång sikt", substring = true).assertDoesNotExist()
        expandAnalysis()
        composeRule.onNodeWithText("Kurs mot 200-dagars snitt").performScrollTo().performClick()
        composeRule.onNodeWithText("svaghetstecken på medellång sikt", substring = true).assertExists()
    }

    @Test
    fun visar_ordlista_och_kan_falla_ut_en_term() {
        showFund(analysis())

        composeRule.onNodeWithText("Så funkar analysen").assertExists()
        composeRule.onNodeWithText("ränta-på-ränta", substring = true).assertDoesNotExist()
        // Ordlistan ligger längst ned och är hopfälld (ANA-10) — fäll ut den, scrolla sedan in
        // raden innan den kan klickas.
        composeRule.onNodeWithText("Så funkar analysen").performScrollTo().performClick()
        composeRule.onNodeWithText("CAGR (årlig snittavkastning)").performScrollTo().performClick()
        composeRule.onNodeWithText("jämna årstakt", substring = true).assertExists()
    }

    @Test
    fun kontexttexterna_ger_aldrig_ett_direkt_saljbud() {
        // ANA-3-vakt: språket är neutralt/förklarande, aldrig en imperativ "sälj nu"/"köp mer nu".
        showFund(analysis(keyFigures = keyFigures().copy(gavFraction = -0.1), distanceFromHigh = FundAnalysisCalc.DistanceFromHighSignal(FundAnalysisCalc.SignalLevel.ROD, -0.25), trend = FundAnalysisCalc.TrendSignal(FundAnalysisCalc.SignalLevel.GUL), momentum = FundAnalysisCalc.MomentumSignal(FundAnalysisCalc.SignalLevel.GUL, -7.0), status = FundAnalysisCalc.SignalLevel.ROD))

        composeRule.onNodeWithText("Sälj nu", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Köp mer nu", substring = true).assertDoesNotExist()
        // Kontextkortet ska däremot finnas och vara neutralt formulerat.
        composeRule.onNodeWithText("kan det vara läge att låta tiden verka", substring = true).assertExists()
    }

    @Test
    fun sedan_kop_har_egen_forklaring_som_skiljer_fondkurs_fran_egen_avkastning() {
        showFund(analysis())

        // Förklaringen är dold tills raden fälls ut, och förtydligar att talet inte är den egna avkastningen.
        composeRule.onNodeWithText("inte din egen avkastning", substring = true).assertDoesNotExist()
        expandAnalysis()
        composeRule.onNodeWithText("Sedan köp").performScrollTo().performClick()
        composeRule.onNodeWithText("inte din egen avkastning", substring = true).assertExists()
    }

    // --- Riskmått (issue #24, ANA-7) ---

    @Test
    fun visar_volatilitet_och_sharpe_nar_historiken_racker() {
        showFund(analysis(keyFigures = keyFigures(annualizedVolatility = 0.18, sharpeRatio = 0.8, riskWindowYears = 3)))

        expandAnalysis()
        // Fönstret som måtten räknats på står i etiketten (issue #195).
        composeRule.onNodeWithText("Volatilitet (3 år)").performScrollTo().assertExists()
        composeRule.onNodeWithText("Sharpe-kvot (3 år)").performScrollTo().assertExists()
        composeRule.onNodeWithText("18,0 %").assertExists()
        composeRule.onNodeWithText("0,80").assertExists()
    }

    @Test
    fun visar_ettarsfonstret_i_etiketten_nar_historiken_inte_nar_tre_ar() {
        showFund(analysis(keyFigures = keyFigures(annualizedVolatility = 0.18, sharpeRatio = 0.8, riskWindowYears = 1)))

        expandAnalysis()
        composeRule.onNodeWithText("Volatilitet (1 år)").performScrollTo().assertExists()
        composeRule.onNodeWithText("Sharpe-kvot (1 år)").performScrollTo().assertExists()
    }

    @Test
    fun visar_otillrackligt_data_for_riskmatt_utan_tillracklig_historik() {
        showFund(analysis(keyFigures = keyFigures(annualizedVolatility = null, sharpeRatio = null, riskWindowYears = null)))

        expandAnalysis()
        // Utan fönster (för kort historik) står den allmänna etiketten kvar.
        composeRule.onNodeWithText("Volatilitet (årlig)").performScrollTo().assertExists()
        // Båda riskmåtten saknar värde → markeras som otillräcklig data (ANA-4), inget gissat 0.
        assertTrue(composeRule.onAllNodesWithText("Otillräcklig data").fetchSemanticsNodes().size >= 2)
    }

    @Test
    fun visar_odefinierad_sharpe_for_en_fond_utan_kursrorelse() {
        // Fönstret finns men kursen har inte rört sig: volatilitet 0, Sharpe odefinierad — inte
        // otillräcklig data (ANA-7).
        showFund(analysis(keyFigures = keyFigures(annualizedVolatility = 0.0, sharpeRatio = null, riskWindowYears = 3)))

        expandAnalysis()
        composeRule.onNodeWithText("Sharpe-kvot (3 år)").performScrollTo().assertExists()
        composeRule.onNodeWithText("Ej definierad (ingen kursrörelse)").assertExists()
    }

    // --- Billigare alternativ (ANA-9, issue #59) ---

    private val alternative = FeeComparisonCalc.Alternative(
        candidate = FundMetadata(
            isin = "SE0000581434", name = "Länsförsäkringar Sverige Index", orderbookId = "12345",
            totalFee = 0.21, managementFee = 0.2, category = "Sverige", fundType = "EQUITY_FUND",
            companyName = "Länsförsäkringar", risk = null, indexFund = true, startDateEpochDay = null,
            minimumBuy = null, tags = emptyList()
        ),
        candidateFeePercent = 0.21,
        // Under 1000 kr undviker tusentalsavgränsarens tvetydiga blanksteg (se MoneyFormatTest).
        annualSavingsKr = 780.0
    )

    @Test
    fun visar_inget_kort_utan_feeComparison() {
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = FondDetaljUiState(fund = DetailUiState.Content(FundDetail(fundName = "Fond A", prices = prices.toImmutableList())), feeComparison = null))
            }
        }

        composeRule.onNodeWithText("Billigare alternativ", ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun visar_laddar_text_medan_jamforelsen_pagar() {
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = FondDetaljUiState(fund = DetailUiState.Content(FundDetail(fundName = "Fond A", prices = prices.toImmutableList())), feeComparison = FeeComparisonUiState.Loading)
                )
            }
        }

        composeRule.onNodeWithText("Billigare alternativ", ignoreCase = true).assertExists()
        composeRule.onNodeWithText("Letar efter billigare alternativ", substring = true).assertExists()
    }

    @Test
    fun visar_kunde_inte_jamforas_text() {
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = FondDetaljUiState(fund = DetailUiState.Content(FundDetail(fundName = "Fond A", prices = prices.toImmutableList())), feeComparison = FeeComparisonUiState.Unavailable)
                )
            }
        }

        composeRule.onNodeWithText("Kunde inte jämföras", substring = true).assertExists()
    }

    @Test
    fun visar_redan_bland_de_billigaste_text() {
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = FondDetaljUiState(
                        fund = DetailUiState.Content(
                            FundDetail(
                                fundName = "Fond A",
                                prices = prices.toImmutableList()
                            )
                        ),
                        feeComparison = FeeComparisonUiState.NoCheaperAlternative
                    )
                )
            }
        }

        composeRule.onNodeWithText("Redan bland de billigaste", substring = true).assertExists()
    }

    @Test
    fun visar_alternativ_med_namn_och_arsbesparing() {
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = FondDetaljUiState(
                        fund = DetailUiState.Content(
                            FundDetail(
                                fundName = "Fond A",
                                prices = prices.toImmutableList()
                            )
                        ),
                        feeComparison = FeeComparisonUiState.Found(persistentListOf(alternative))
                    )
                )
            }
        }

        composeRule.onNodeWithText("Länsförsäkringar Sverige Index").assertExists()
        composeRule.onNodeWithText("780,00 kr", substring = true).assertExists()
    }

    @Test
    fun kan_falla_ut_avgiften_for_ett_alternativ() {
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = FondDetaljUiState(
                        fund = DetailUiState.Content(
                            FundDetail(
                                fundName = "Fond A",
                                prices = prices.toImmutableList()
                            )
                        ),
                        feeComparison = FeeComparisonUiState.Found(persistentListOf(alternative))
                    )
                )
            }
        }

        // Avgiften (förklaringen) är dold tills raden fälls ut.
        composeRule.onNodeWithText("0,21 %", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Länsförsäkringar Sverige Index").performScrollTo().performClick()
        composeRule.onNodeWithText("0,21 %", substring = true).assertExists()
    }

    // --- Bytesbeslutet överst, foldouts och risknivå (ANA-10/ANA-11/UI-10, issue #85) ---

    private val planSuggestion = SwitchPlanResolver.Suggestion(
        recordId = 1,
        planIndex = 0,
        sellIsin = "SE0004297927",
        buyIsin = "SE0000581434",
        sellFundName = "Fond A",
        buyFundName = "Fond B",
        fromLevel = 5,
        toLevel = 4,
        feeDeltaPercent = -0.8,
        switchValueKr = 400.0
    )

    private fun holdingState(
        analysis: FundAnalysisCalc.Analysis? = null,
        riskLevel: Int? = null,
        switchPlan: List<SwitchPlanResolver.Suggestion> = emptyList(),
        feeComparison: FeeComparisonUiState? = null,
        comparisons: Map<String, ComparisonUiState> = emptyMap(),
        recordedFeeSwitches: Map<String, RecordedFeeSwitch> = emptyMap()
    ) = FondDetaljUiState(
        fund = DetailUiState.Content(
            FundDetail(
                fundName = "Fond A",
                isin = "SE0004297927",
                prices = prices.toImmutableList(),
                analysis = analysis
            )
        ),
        riskLevel = riskLevel,
        switchPlan = switchPlan.toImmutableList(),
        feeComparison = feeComparison,
        comparisons = comparisons.toImmutableMap(),
        recordedFeeSwitches = recordedFeeSwitches.toImmutableMap()
    )

    @Test
    fun visar_ingen_kurstabell_langre() {
        // NAV-2: den radvisa kurstabellen (datum + kurs) är borttagen — diagrammet visar samma sak.
        composeRule.setContent {
            FonderTheme { FondDetaljContent(state = holdingState(analysis = analysis())) }
        }

        composeRule.onNodeWithText("1970-04-11").assertDoesNotExist()
        composeRule.onNodeWithText("120,00 kr").assertDoesNotExist()
    }

    @Test
    fun visar_bytesavsnittet_med_risknniva_i_rubriken() {
        composeRule.setContent {
            FonderTheme { FondDetaljContent(state = holdingState(analysis = analysis(), riskLevel = 5)) }
        }

        composeRule.onNodeWithText("Ska du byta?").assertExists()
        composeRule.onNodeWithText("Risk 5/7").assertExists()
    }

    @Test
    fun visar_okand_risk_i_stallet_for_ingen_markning() {
        composeRule.setContent {
            FonderTheme { FondDetaljContent(state = holdingState(analysis = analysis(), riskLevel = null)) }
        }

        composeRule.onNodeWithText("Risk okänd").assertExists()
    }

    @Test
    fun visar_bytesplanens_forslag_med_riktning_och_riskdelta() {
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = holdingState(analysis = analysis(), riskLevel = 5, switchPlan = listOf(planSuggestion)))
            }
        }

        composeRule.onNodeWithText("1. Byt till Fond B").assertExists()
        composeRule.onNodeWithText("Risk 5 → 4 (av 7)").assertExists()
    }

    @Test
    fun visar_kop_hit_riktningen_nar_fonden_ar_kopkandidaten() {
        // Den öppnade fonden (SE0004297927) är köpkandidat: raden namnger säljfonden, och
        // riskpilen följer **bytet** — från säljfondens nivå till den här fondens — inte den
        // betraktande fondens perspektiv. Annars visades ett byte som höjer risken som en
        // sänkning.
        val incoming = planSuggestion.copy(
            sellIsin = "SE0000581434",
            buyIsin = "SE0004297927",
            sellFundName = "Fond B",
            buyFundName = "Fond A",
            fromLevel = 4,
            toLevel = 5
        )
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = holdingState(analysis = analysis(), switchPlan = listOf(incoming)))
            }
        }

        composeRule.onNodeWithText("1. Byt hit från Fond B").assertExists()
        composeRule.onNodeWithText("Risk 4 → 5 (av 7)").assertExists()
    }

    @Test
    fun bytesforslagets_belopp_ar_dolt_tills_raden_falls_ut() {
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(state = holdingState(analysis = analysis(), switchPlan = listOf(planSuggestion)))
            }
        }

        composeRule.onNodeWithText("Belopp: 400,00 kr", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("1. Byt till Fond B").performScrollTo().performClick()
        composeRule.onNodeWithText("Belopp: 400,00 kr", substring = true).assertExists()
    }

    @Test
    fun utfallt_forslag_begar_kandidatens_kurshistorik() {
        // ANA-11: historiken hämtas lazily, alltså först när raden fälls ut.
        val expanded = mutableListOf<String>()
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = holdingState(analysis = analysis(), switchPlan = listOf(planSuggestion)),
                    onSuggestionExpand = { expanded += it }
                )
            }
        }

        assertTrue(expanded.isEmpty())
        composeRule.onNodeWithText("1. Byt till Fond B").performScrollTo().performClick()
        assertEquals(listOf("SE0000581434"), expanded)
    }

    @Test
    fun utfallt_forslag_visar_jamforelsediagram_med_bada_fonderna() {
        val comparison = ComparisonUiState.Ready(points = persistentListOf(90L to 100.0, 100L to 105.0))
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = holdingState(
                        analysis = analysis(),
                        switchPlan = listOf(planSuggestion),
                        comparisons = mapOf("SE0000581434" to comparison)
                    )
                )
            }
        }

        composeRule.onNodeWithText("1. Byt till Fond B").performScrollTo().performClick()
        composeRule.onNodeWithText("Din fond").assertExists()
        composeRule.onNodeWithText("startar på 100", substring = true).assertExists()
    }

    @Test
    fun utfallt_forslag_utan_hamtbar_historik_sager_det_i_stallet_for_tomt_diagram() {
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = holdingState(
                        analysis = analysis(),
                        switchPlan = listOf(planSuggestion),
                        comparisons = mapOf("SE0000581434" to ComparisonUiState.Unavailable)
                    )
                )
            }
        }

        composeRule.onNodeWithText("1. Byt till Fond B").performScrollTo().performClick()
        composeRule.onNodeWithText("Kunde inte hämta kurshistorik", substring = true).assertExists()
    }

    @Test
    fun sager_varfor_inget_byte_foreslas_for_ett_innehav() {
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = holdingState(analysis = analysis(), feeComparison = FeeComparisonUiState.NoCheaperAlternative)
                )
            }
        }

        composeRule.onNodeWithText("Riskprofilens bytesplan föreslår inget byte", substring = true).assertExists()
    }

    @Test
    fun avstadda_forslag_doljs_och_antalet_sags_ut_med_vagen_till_angra() {
        // HEM-14: ett avstått förslag visas inte här heller — men det sägs ut, och ett avstått byte
        // läses inte som att planen inte föreslår något.
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = holdingState(
                        analysis = analysis(),
                        feeComparison = FeeComparisonUiState.Found(persistentListOf())
                    ).copy(dismissedPlanCount = 1, dismissedFeeCount = 1)
                )
            }
        }

        composeRule.onNodeWithText("2 förslag du avstått från visas inte här", substring = true).performScrollTo().assertExists()
        composeRule.onNodeWithText("Riskprofilens bytesplan föreslår inget byte", substring = true).assertDoesNotExist()
    }

    @Test
    fun sager_att_bytesforslag_bara_ges_for_agda_fonder() {
        composeRule.setContent {
            FonderTheme { FondDetaljContent(state = FondDetaljUiState(fund = DetailUiState.Content(FundDetail(fundName = "Fond A", prices = prices.toImmutableList())))) }
        }

        composeRule.onNodeWithText("bara för fonder du äger", substring = true).assertExists()
    }

    @Test
    fun analysens_nyckeltal_ligger_hopfallda_tills_sektionen_falls_ut() {
        composeRule.setContent {
            FonderTheme { FondDetaljContent(state = holdingState(analysis = analysis())) }
        }

        composeRule.onNodeWithText("Årlig snittavkastning (CAGR)").assertDoesNotExist()
        expandAnalysis()
        composeRule.onNodeWithText("Årlig snittavkastning (CAGR)").performScrollTo().assertExists()
    }

    @Test
    fun folj_pa_bytesforslaget_gar_utan_att_fallas_ut() {
        // ANA-10/BYT-2: Följ öppnar säljformuläret där beslutet fattas, utan att först fälla ut diagrammet.
        var followed: Long? = null
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = holdingState(analysis = analysis(), switchPlan = listOf(planSuggestion)),
                    onFollow = { followed = it.recordId }
                )
            }
        }

        composeRule.onNodeWithText("Följ").performScrollTo().performClick()

        assertEquals(1L, followed)
    }

    @Test
    fun ett_redan_genomfort_byte_erbjuds_inte_folj() {
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = holdingState(analysis = analysis(), switchPlan = listOf(planSuggestion.copy(followed = true)))
                )
            }
        }

        composeRule.onNodeWithText("Följ").assertDoesNotExist()
        composeRule.onNodeWithText("Genomförd").assertDoesNotExist()
    }

    // --- Kvittering av avgiftsbytet (ANA-9/SET-5, issue #91) ---

    @Test
    fun avgiftsalternativ_utan_inspelad_rad_har_ingen_folj() {
        // Listan räknas om live vid varje skärmöppning, raden skrivs av bakgrundsskanningen —
        // ett alternativ som just dykt upp har inget att kvittera mot. En kryssruta som tyst
        // inte skriver någonstans vore värre än ingen alls.
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = holdingState(
                        analysis = analysis(),
                        feeComparison = FeeComparisonUiState.Found(persistentListOf(alternative))
                    )
                )
            }
        }

        composeRule.onNodeWithText("Länsförsäkringar Sverige Index").performScrollTo().assertExists()
        composeRule.onNodeWithText("Följ").assertDoesNotExist()
    }

    @Test
    fun avgiftsalternativ_med_inspelad_rad_kan_foljas() {
        var followed: Long? = null
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = holdingState(
                        analysis = analysis(),
                        feeComparison = FeeComparisonUiState.Found(persistentListOf(alternative)),
                        recordedFeeSwitches = mapOf("SE0000581434" to RecordedFeeSwitch(recordId = 42, followed = false))
                    ),
                    onFollowFee = { followed = it }
                )
            }
        }

        composeRule.onNodeWithText("Följ").performScrollTo().performClick()

        // Följer avgiftsradens eget id, inte bytesplanens.
        assertEquals(42L, followed)
    }

    @Test
    fun ett_redan_genomfort_avgiftsbyte_erbjuds_inte_folj() {
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = holdingState(
                        analysis = analysis(),
                        feeComparison = FeeComparisonUiState.Found(persistentListOf(alternative)),
                        recordedFeeSwitches = mapOf("SE0000581434" to RecordedFeeSwitch(recordId = 42, followed = true))
                    )
                )
            }
        }

        composeRule.onNodeWithText("Länsförsäkringar Sverige Index").performScrollTo().assertExists()
        composeRule.onNodeWithText("Följ").assertDoesNotExist()
    }
}
