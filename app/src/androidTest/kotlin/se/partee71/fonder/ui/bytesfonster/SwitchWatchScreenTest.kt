package se.partee71.fonder.ui.bytesfonster

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import se.partee71.fonder.ui.diagram.CHART_PERIOD_ROW_TEST_TAG
import se.partee71.fonder.ui.theme.FonderTheme
import java.time.LocalDate

/**
 * Instrumenterat test av bevakningsskärmens tillståndsdrivna innehåll (ANA-12, issue #114).
 * Bygger [SwitchWatchUiState] direkt i stället för att gå via ViewModel/Hilt — samma mönster
 * som [se.partee71.fonder.ui.facit.FacitScreenTest].
 */
@RunWith(AndroidJUnit4::class)
class SwitchWatchScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val soldAt = LocalDate.of(2026, 8, 3).toEpochDay()

    private fun row(
        candidateId: Long = 1,
        isin: String = "SE_A",
        name: String = "Kandidat A",
        riskLevel: Int? = 4,
        changeFraction: Double? = 0.032,
        changeKr: Double? = 320.0,
        partial: Boolean = false,
        historyUnavailable: Boolean = false,
        manual: Boolean = false,
    ) = CandidateRow(
        candidateId = candidateId,
        isin = isin,
        name = name,
        riskLevel = riskLevel,
        feePercent = 0.42,
        changeFraction = changeFraction,
        changeKr = changeKr,
        partial = partial,
        historyUnavailable = historyUnavailable,
        manual = manual,
    )

    private fun state(
        rows: List<CandidateRow> = listOf(row()),
        closed: Boolean = false,
        expired: Boolean = false,
        boughtFundName: String? = null,
        // Utan kurvor som standard: diagrammet är högt, och en `LazyColumn` komponerar inte
        // rader utanför skärmen — de flesta testerna handlar om raderna, inte om diagrammet,
        // och ska inte behöva skrolla förbi det. Diagrammet har ett eget test nedan.
        candidateSeries: List<Pair<String, List<Pair<Long, Double>>>> = emptyList(),
        sellSeries: List<Pair<Long, Double>> = emptyList(),
    ) = SwitchWatchUiState(
        loading = false,
        sellFundName = "Såld fond",
        soldAtEpochDay = soldAt,
        proceedsKr = 10_000.0,
        daysWaiting = 3,
        expired = expired,
        closed = closed,
        boughtFundName = boughtFundName,
        rows = rows,
        sellSeries = sellSeries,
        candidateSeries = candidateSeries,
    )

    @Test
    fun visar_saljfond_belopp_och_dag_i_vantan() {
        composeRule.setContent { FonderTheme { SwitchWatchContent(state = state()) } }

        composeRule.onNodeWithText("Sålt Såld fond · 2026-08-03").assertIsDisplayed()
        // Substring på etiketten, inte på hela beloppet: sv-SE grupperar med hårt blanksteg,
        // och ett testvärde skrivet med vanligt mellanslag hade fallit på det.
        composeRule.onNodeWithText("Belopp:", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Dag 3 av ${SwitchWatchCalc.WATCH_TTL_DAYS}", substring = true).assertIsDisplayed()
    }

    @Test
    fun kandidatraden_visar_namn_risk_avgift_och_utveckling_sedan_saljdagen() {
        composeRule.setContent { FonderTheme { SwitchWatchContent(state = state()) } }

        composeRule.onNodeWithText("Kandidat A").assertIsDisplayed()
        composeRule.onNodeWithText("Sedan säljdagen").assertIsDisplayed()
        composeRule.onNodeWithText("+3,2 %", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Avgift", substring = true).assertIsDisplayed()
        // Risknivån alltid som siffra i text, aldrig som enbart en färg (UI-10).
        composeRule.onNodeWithContentDescription("Risknivå 4 av 7", substring = true).assertIsDisplayed()
    }

    @Test
    fun saljfonden_och_alla_alternativ_ritas_i_ett_gemensamt_diagram() {
        // Ett diagram (regel 4 — delade FundLineChart), inte ett per kandidat: periodväljaren
        // finns då exakt en gång, vilket är det som går att adressera i testet.
        composeRule.setContent {
            FonderTheme {
                SwitchWatchContent(
                    state = state(
                        rows = listOf(row(candidateId = 1, isin = "SE_A"), row(candidateId = 2, isin = "SE_B", name = "Kandidat B")),
                        sellSeries = listOf(soldAt to 200.0, soldAt + 3 to 198.0),
                        candidateSeries = listOf(
                            "Kandidat A" to listOf(soldAt to 100.0, soldAt + 3 to 103.2),
                            "Kandidat B" to listOf(soldAt to 50.0, soldAt + 3 to 52.0),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(CHART_PERIOD_ROW_TEST_TAG).assertExists()
    }

    @Test
    fun utan_hamtbar_historik_alls_sags_det_ut_i_stallet_for_ett_tomt_diagram() {
        composeRule.setContent { FonderTheme { SwitchWatchContent(state = state()) } }

        composeRule.onNodeWithText("Kunde inte hämta kurshistorik", substring = true).assertIsDisplayed()
    }

    @Test
    fun en_kandidat_utan_hamtbar_historik_sags_ut_i_stallet_for_att_visas_som_noll() {
        val state = state(
            rows = listOf(row(changeFraction = null, changeKr = null, historyUnavailable = true)),
        )

        composeRule.setContent { FonderTheme { SwitchWatchContent(state = state) } }

        composeRule.onNodeWithText("Kandidat A").assertIsDisplayed()
        composeRule.onNodeWithText("Ingen källa kunde ge kursen", substring = true).assertIsDisplayed()
    }

    @Test
    fun kopte_den_har_lamnar_kandidatens_isin() {
        var bought: String? = null

        composeRule.setContent {
            FonderTheme { SwitchWatchContent(state = state(), onBought = { bought = it }) }
        }
        composeRule.onNodeWithText("Köpte den här").performClick()

        assertEquals("SE_A", bought)
    }

    @Test
    fun bara_handplockade_alternativ_gar_att_ta_bort() {
        composeRule.setContent {
            FonderTheme {
                SwitchWatchContent(
                    state = state(
                        rows = listOf(
                            row(candidateId = 1, isin = "SE_A", name = "Appens förslag", manual = false),
                            row(candidateId = 2, isin = "SE_B", name = "Eget val", manual = true),
                        ),
                    ),
                )
            }
        }

        // Exakt en "Ta bort" — appens egna förslag hör till planen och ska inte gå att plocka bort.
        composeRule.onNodeWithText("Ta bort").assertIsDisplayed()
    }

    @Test
    fun en_avslutad_bevakning_visar_kopet_och_erbjuder_inga_atgarder() {
        composeRule.setContent {
            FonderTheme {
                SwitchWatchContent(state = state(closed = true, boughtFundName = "Kandidat A"))
            }
        }

        composeRule.onNodeWithText("Köpte Kandidat A").assertIsDisplayed()
        composeRule.onNodeWithText("Köpte den här").assertDoesNotExist()
        composeRule.onNodeWithText("Avbryt bevakningen").assertDoesNotExist()
        composeRule.onNodeWithText("Lägg till alternativ").assertDoesNotExist()
    }

    @Test
    fun en_utgangen_bevakning_sags_ut() {
        composeRule.setContent { FonderTheme { SwitchWatchContent(state = state(expired = true)) } }

        composeRule.onNodeWithText("äldre än ${SwitchWatchCalc.WATCH_TTL_DAYS} dygn", substring = true).assertIsDisplayed()
    }

    @Test
    fun utan_kandidater_visas_en_uppmaning_i_stallet_for_tom_yta() {
        composeRule.setContent {
            FonderTheme { SwitchWatchContent(state = state(rows = emptyList())) }
        }

        composeRule.onNodeWithText("Inga alternativ bevakas än", substring = true).assertIsDisplayed()
        // Kortet med säljfond och belopp gäller även utan alternativ och ska inte tryckas bort.
        composeRule.onNodeWithText("Sålt Såld fond · 2026-08-03").assertIsDisplayed()
    }

    @Test
    fun utan_kand_malniva_sags_orsaken_och_inget_forsok_igen() {
        composeRule.setContent {
            FonderTheme {
                SwitchWatchContent(state = state(rows = emptyList()).copy(emptyReason = SwitchWatchEmptyReason.NO_TARGET_LEVEL))
            }
        }

        composeRule.onNodeWithText("Appen känner inte till risknivån för Såld fond", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Försök igen").assertDoesNotExist()
    }

    @Test
    fun inga_kopbara_pa_nivan_namner_nivan() {
        composeRule.setContent {
            FonderTheme {
                SwitchWatchContent(
                    state = state(rows = emptyList()).copy(targetLevel = 5, emptyReason = SwitchWatchEmptyReason.NONE_AVAILABLE),
                )
            }
        }

        composeRule.onNodeWithText("Ingen av de bästa fonderna på risknivå 5", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Försök igen").assertDoesNotExist()
    }

    @Test
    fun misslyckat_uppslag_erbjuder_forsok_igen() {
        var retries = 0
        composeRule.setContent {
            FonderTheme {
                SwitchWatchContent(
                    state = state(rows = emptyList()).copy(emptyReason = SwitchWatchEmptyReason.LOOKUP_FAILED),
                    onRetryAutoFill = { retries++ },
                )
            }
        }

        composeRule.onNodeWithText("Det gick inte att kontrollera", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Försök igen").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun en_bevakning_som_inte_finns_visas_som_saknad() {
        composeRule.setContent {
            FonderTheme { SwitchWatchContent(state = SwitchWatchUiState(loading = false, missing = true)) }
        }

        composeRule.onNodeWithText("Bevakningen finns inte").assertIsDisplayed()
    }

    @Test
    fun taket_pa_antal_alternativ_sags_ut_som_meddelande() {
        composeRule.setContent {
            FonderTheme {
                SwitchWatchContent(state = state().copy(message = SwitchWatchMessage.CandidateLimitReached))
            }
        }

        composeRule.onNodeWithText(
            "Du kan bevaka högst ${SwitchWatchCalc.MAX_CANDIDATES} alternativ åt gången.",
        ).assertIsDisplayed()
    }

    @Test
    fun nivavaljaren_visar_vald_niva_och_saljfondens_niva_och_ger_nytt_val() {
        var chosen: Int? = null
        composeRule.setContent {
            FonderTheme {
                SwitchWatchContent(
                    state = state().copy(targetLevel = 4, sellLevel = 6),
                    onSelectTargetLevel = { chosen = it },
                )
            }
        }

        composeRule.onNodeWithText("Köp på risknivå").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Risknivå 4 av 7").assertIsSelected()
        // Ramen bär en betydelse och får aldrig vara ensam om den (UI-3).
        composeRule.onNodeWithContentDescription("Risknivå 6 av 7, säljfondens nivå").assertIsDisplayed()
        composeRule.onNodeWithText("Ramen på 6 är säljfondens egen nivå", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Risknivå 2 av 7").performClick()

        assertEquals(2, chosen)
    }

    @Test
    fun forklaringen_foljer_riskprofilen_och_rubriken_namner_niva_och_filter() {
        // Issue #150: profilens nivå med gapet, och ramen på säljfondens nivå. Neutral text (granskningen):
        // skärmen vet inte om nivån förvaldes av profilen eller valdes av användaren.
        val profile = mutableStateOf(state().copy(targetLevel = 4, sellLevel = 6, profileSuggestedLevel = 4, profileGapPp = 9))
        composeRule.setContent {
            FonderTheme { SwitchWatchContent(state = profile.value) }
        }

        composeRule.onNodeWithText(
            "Nivå 4: din riskprofil har 9 procentenheter för lite där. Ramen på 6 är säljfondens egen nivå.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Sedan säljdagen · nivå 4").assertExists()

        // Användaren har valt en annan nivå, med filtret på.
        profile.value = profile.value.copy(targetLevel = 2, indexOnly = true)
        composeRule.onNodeWithText("Vald nivå 2. Riskprofilen saknar mest på nivå 4.").assertIsDisplayed()
        composeRule.onNodeWithText("Sedan säljdagen · nivå 2 · indexfonder").assertExists()
    }

    @Test
    fun bara_indexfonder_vaxlar_filtret() {
        var indexOnly: Boolean? = null
        composeRule.setContent {
            FonderTheme {
                SwitchWatchContent(
                    state = state().copy(targetLevel = 4, indexOnly = true),
                    onIndexOnlyChanged = { indexOnly = it },
                )
            }
        }

        composeRule.onNodeWithText("Bara indexfonder").assertIsOn().performClick()

        assertEquals(false, indexOnly)
    }

    @Test
    fun inga_kopbara_indexfonder_sags_med_filtret_som_orsak() {
        composeRule.setContent {
            FonderTheme {
                SwitchWatchContent(
                    state = state(rows = emptyList()).copy(
                        targetLevel = 2,
                        indexOnly = true,
                        emptyReason = SwitchWatchEmptyReason.NONE_AVAILABLE,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Inga köpbara indexfonder på nivå 2").assertIsDisplayed()
        composeRule.onNodeWithText("Stäng av ”Bara indexfonder”", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Ingen av de bästa fonderna på risknivå", substring = true).assertDoesNotExist()
    }

    @Test
    fun en_stangd_bevakning_visar_ingen_nivavaljare() {
        composeRule.setContent {
            FonderTheme { SwitchWatchContent(state = state(closed = true).copy(targetLevel = 4)) }
        }

        composeRule.onNodeWithText("Köp på risknivå").assertDoesNotExist()
        composeRule.onNodeWithText("Bara indexfonder").assertDoesNotExist()
    }
}
