package se.partee71.fonder.ui.facit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.usecase.SwitchOutcomeCalc
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Instrumenterat test av facit-skärmens tillståndsdrivna innehåll (SET-5, issue #80). Bygger
 * [FacitUiState] direkt i stället för att gå via ett riktigt [FacitViewModel]/Hilt — samma
 * mönster som [se.partee71.fonder.ui.transaktioner.SoldFundsScreenTest].
 */
@RunWith(AndroidJUnit4::class)
class FacitScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun record(id: Long, planIndex: Int = 0, switchValueKr: Double? = 10_000.0, followed: Boolean? = null) =
        SuggestionRecord(
            id = id,
            suggestedAtEpochDay = 20_000,
            planIndex = planIndex,
            sellIsin = "SE_SELL",
            buyIsin = "SE_BUY",
            sellNavAtSuggestion = 100.0,
            buyNavAtSuggestion = 200.0,
            switchValueKr = switchValueKr,
            followed = followed,
        )

    private fun rad(
        id: Long = 1,
        planIndex: Int = 0,
        sellFundName: String = "Såld fond",
        buyFundName: String = "Köpt fond",
        switchValueKr: Double? = 10_000.0,
        followed: Boolean = false,
        sellNavNow: Double? = 105.0,
        buyNavNow: Double? = 224.0,
    ) = FacitRad(
        recordId = id,
        planIndex = planIndex,
        suggestedAtEpochDay = 20_000,
        sellFundName = sellFundName,
        buyFundName = buyFundName,
        switchValueKr = switchValueKr,
        followed = followed,
        outcome = SwitchOutcomeCalc.evaluate(
            record = record(id, planIndex, switchValueKr, followed),
            sellNavNow = sellNavNow,
            buyNavNow = buyNavNow,
        ),
    )

    private fun state(vararg rows: FacitRad): FacitUiState {
        val outcomes = rows.map { it.outcome }
        return FacitUiState(
            loading = false,
            rows = rows.toList(),
            allSummary = SwitchOutcomeCalc.summarize(outcomes),
            followedSummary = SwitchOutcomeCalc.summarize(rows.filter { it.followed }.map { it.outcome }),
            byPlanIndex = SwitchOutcomeCalc.byPlanIndex(outcomes),
        )
    }

    @Test
    fun tomt_tillstand_visas_utan_inspelade_forslag() {
        composeRule.setContent {
            FonderTheme { FacitContent(state = FacitUiState(loading = false)) }
        }

        composeRule.onNodeWithText("Inget facit än").assertExists()
    }

    @Test
    fun visar_ett_kort_per_forslag_med_fondnamnen() {
        composeRule.setContent {
            FonderTheme {
                FacitContent(
                    state = state(
                        rad(id = 1, sellFundName = "Fond A", buyFundName = "Fond B"),
                        rad(id = 2, planIndex = 1, sellFundName = "Fond C", buyFundName = "Fond D"),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Fond A → Fond B").assertExists()
        composeRule.onNodeWithText("Fond C → Fond D").assertExists()
    }

    @Test
    fun summeringen_visar_alla_och_enbart_genomforda_som_skilda_matt() {
        // Två rader med samma utfall (+7,0 %, 70,00 kr vardera), varav en genomförd — totalen
        // för alla (140,00 kr) skiljer sig då från totalen för de genomförda (70,00 kr) enbart
        // på grund av *urvalet*. Slogs måtten ihop mätte siffran ingetdera (SET-5). Beloppen
        // hålls under tusen: tusentalsavgränsaren i sv-SE varierar mellan JVM-versioner (se
        // MoneyFormatTest) och skulle göra träffen skör, inte skarpare.
        composeRule.setContent {
            FonderTheme {
                FacitContent(
                    state = state(
                        rad(id = 1, planIndex = 0, switchValueKr = 1_000.0, followed = true),
                        // Skilda platser i planen: annars visar även "Plats 1"-raden 140,00 kr
                        // och träffen blir tvetydig.
                        rad(id = 2, planIndex = 1, switchValueKr = 1_000.0),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Alla förslag").assertExists()
        composeRule.onNodeWithText("Enbart genomförda").assertExists()
        // 140,00 kr finns bara i "alla"-totalen — raderna visar 70,00 kr var.
        composeRule.onNodeWithText("140,00 kr").assertExists()
        composeRule.onNodeWithText("2 av 2 förslag utvärderade").assertExists()
    }

    @Test
    fun snitt_per_plats_i_planen_visas() {
        composeRule.setContent {
            FonderTheme { FacitContent(state = state(rad(id = 1, planIndex = 0), rad(id = 2, planIndex = 1))) }
        }

        composeRule.onNodeWithText("Per plats i planen").assertExists()
        composeRule.onNodeWithText("Plats 1").assertExists()
        composeRule.onNodeWithText("Plats 2").assertExists()
    }

    @Test
    fun ej_utvarderad_rad_visar_otillracklig_data_i_stallet_for_noll() {
        // En saknad kurs får aldrig läsas som "bytet gav ingenting" (ANA-4-principen).
        composeRule.setContent {
            FonderTheme { FacitContent(state = state(rad(id = 1, buyNavNow = null))) }
        }

        // Texten står på flera ställen (båda summeringarna, plats-raden och själva raden) —
        // det viktiga är att den står *någonstans* i stället för en nolla.
        composeRule.onAllNodesWithText("Otillräcklig data").onFirst().assertExists()
        composeRule.onNodeWithText("0,00 kr").assertDoesNotExist()
    }

    @Test
    fun kort_ar_stangt_som_standard_och_kan_fallas_ut() {
        composeRule.setContent {
            FonderTheme { FacitContent(state = state(rad(id = 1))) }
        }

        composeRule.onNodeWithText("Plats 1 i planen").assertDoesNotExist()

        composeRule.onNodeWithText("Såld fond → Köpt fond").performClick()

        composeRule.onNodeWithText("Plats 1 i planen").assertExists()
        composeRule.onNodeWithText("Belopp: ", substring = true).assertExists()
    }

    @Test
    fun genomford_kryssrutan_anropar_callbacken() {
        var callback: Pair<Long, Boolean>? = null
        composeRule.setContent {
            FonderTheme {
                FacitContent(
                    state = state(rad(id = 7)),
                    onFollowedChange = { id, followed -> callback = id to followed },
                )
            }
        }

        composeRule.onNodeWithText("Såld fond → Köpt fond").performClick()
        composeRule.onNodeWithText("Genomförd").performClick()

        assertEquals(7L to true, callback)
    }

    @Test
    fun rad_utan_belopp_visar_procent_men_ingen_beloppsrad() {
        // Rader inspelade före issue #75 vet inte vilket belopp bytet gällde.
        composeRule.setContent {
            FonderTheme { FacitContent(state = state(rad(id = 1, switchValueKr = null))) }
        }

        composeRule.onNodeWithText("Såld fond → Köpt fond").performClick()

        // Procenten står både i summeringen och på raden — båda ska visa den, ingen ska visa
        // ett kronbelopp.
        composeRule.onAllNodesWithText("+7,0 %").onFirst().assertExists()
        composeRule.onNodeWithText("Belopp: ", substring = true).assertDoesNotExist()
    }

    @Test
    fun facit_ar_skrollbar_med_manga_forslag() {
        // UI-5: listan är genuint lazy, så sista raden är inte komponerad förrän listan
        // skrollats dit — därför performScrollToIndex mot testTaggen, inte assertExists.
        val rows = (1..15).map { n -> rad(id = n.toLong(), sellFundName = "Fond $n") }
        composeRule.setContent {
            FonderTheme { FacitContent(state = state(*rows.toTypedArray())) }
        }

        composeRule.onNodeWithTag(FACIT_LIST_TEST_TAG).performScrollToIndex(15)

        composeRule.onNodeWithText("Fond 15 → Köpt fond").assertIsDisplayed()
    }
}
