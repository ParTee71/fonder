package se.partee71.fonder.ui.beslut

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.usecase.OpenSwitchWatch
import se.partee71.fonder.domain.usecase.PendingDecisions
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Instrumenterat test av fliken Beslut (NAV-9) mot [BeslutContent] med handskrivet tillstånd.
 * Bytesplanens, omräkningens och de pågående bytenas tester flyttades hit från `HemScreenTest`
 * när de lämnade Översikten (0.61.0) — beteendet är detsamma, bara platsen är ny.
 */
@RunWith(AndroidJUnit4::class)
class BeslutScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun dyrTillBillig(recordId: Long = 1, planIndex: Int = 0, switchValueKr: Double? = 250.0, followed: Boolean = false) =
        SwitchPlanResolver.Suggestion(
            recordId = recordId, planIndex = planIndex, sellIsin = "SE0004297927", buyIsin = "SE0000581434",
            sellFundName = "Dyr fond", buyFundName = "Billig fond",
            fromLevel = 5, toLevel = 3, feeDeltaPercent = -0.7, switchValueKr = switchValueKr, followed = followed,
        )

    private fun stateWith(
        plan: List<SwitchPlanResolver.Suggestion> = emptyList(),
        watches: List<OpenSwitchWatch> = emptyList(),
        watchedSellIsins: Set<String> = emptySet(),
        canRecompute: Boolean = false,
        backgroundWorkRunning: Boolean = false,
    ) = BeslutUiState(
        loading = false,
        riskProfile = RiskProfile(targetAllocation = mapOf(3 to 1.0)),
        accountType = if (canRecompute) AccountType.ISK_KF else AccountType.DEPA_AF,
        decisions = PendingDecisions(switchPlan = plan, openWatches = watches, watchedSellIsins = watchedSellIsins),
        backgroundWorkRunning = backgroundWorkRunning,
    )

    private fun show(state: BeslutUiState) = composeRule.setContent { FonderTheme { BeslutContent(state = state) } }

    // --- Bytesplanen (HEM-8) ---

    @Test
    fun bytesplanen_visar_ratt_rangordning_belopp_och_avgiftsskillnad() {
        show(stateWith(plan = listOf(dyrTillBillig())))

        composeRule.onNodeWithText("Förslag").assertExists()
        composeRule.onNodeWithText("1. Sälj Dyr fond (nivå 5) → Köp Billig fond (nivå 3)").performScrollTo().assertIsDisplayed()
        // Beloppet måste stå ut: bytet avser gapet, inte hela positionen (issue #75). Belopp
        // under tusen — tusentalsavgränsaren kan vara vanligt eller hårt blanksteg beroende på
        // JVM/lokal (se MoneyFormatTest), och ett test som beror på vilket vore flakigt.
        composeRule.onNodeWithText("Belopp: 250,00 kr", substring = true).assertExists()
        composeRule.onNodeWithText("Avgiftsskillnad: −0,7 %").assertExists()
    }

    @Test
    fun bytesplanens_rangordning_kommer_ur_planIndex_inte_listpositionen() {
        // Regression (issue #75): numret togs ur listpositionen, så ett byte vars föregångare
        // fallit bort presenterades som "1." — fast planen är girig och sekventiell.
        show(stateWith(plan = listOf(dyrTillBillig(planIndex = 1))))

        composeRule.onNodeWithText("2. Sälj Dyr fond (nivå 5) → Köp Billig fond (nivå 3)").performScrollTo().assertExists()
    }

    @Test
    fun bytesplan_utan_belopp_for_forslag_inspelade_fore_beloppsfaltet() {
        show(stateWith(plan = listOf(dyrTillBillig(switchValueKr = null))))

        composeRule.onNodeWithText("1. Sälj Dyr fond (nivå 5) → Köp Billig fond (nivå 3)").performScrollTo().assertExists()
        composeRule.onNodeWithText("Belopp:", substring = true).assertDoesNotExist()
    }

    @Test
    fun folj_i_bytesplanen_markerar_forslaget_som_genomfort() {
        // SET-5 (issue #80) och HEM-14: "Följ" utför inget byte, den registrerar att användaren gjorde det.
        var callback: Pair<Long, Boolean>? = null
        composeRule.setContent {
            FonderTheme {
                BeslutContent(
                    state = stateWith(plan = listOf(dyrTillBillig(recordId = 42))),
                    onSwitchFollowedChange = { id, followed -> callback = id to followed },
                )
            }
        }

        composeRule.onNodeWithText("Genomförd").assertDoesNotExist()
        composeRule.onNodeWithText("Följ").performScrollTo().performClick()

        assertEquals(42L to true, callback)
    }

    @Test
    fun genomford_pa_ett_kvitterat_forslag_kan_angras() {
        var callback: Pair<Long, Boolean>? = null
        composeRule.setContent {
            FonderTheme {
                BeslutContent(
                    state = stateWith(plan = listOf(dyrTillBillig(recordId = 42, followed = true))),
                    onSwitchFollowedChange = { id, followed -> callback = id to followed },
                )
            }
        }

        composeRule.onNodeWithText("Följ").assertDoesNotExist()
        composeRule.onNodeWithText("Avstå").assertDoesNotExist()
        composeRule.onNodeWithText("Genomförd").performScrollTo().performClick()

        assertEquals(42L to false, callback)
    }

    @Test
    fun avsta_anropar_callbacken_med_forslaget() {
        var dismissed: SwitchPlanResolver.Suggestion? = null
        composeRule.setContent {
            FonderTheme {
                BeslutContent(
                    state = stateWith(plan = listOf(dyrTillBillig(recordId = 42))),
                    onDismissSuggestion = { dismissed = it },
                )
            }
        }

        composeRule.onNodeWithText("Avstå").performScrollTo().performClick()

        assertEquals(42L, dismissed?.recordId)
    }

    @Test
    fun avstadda_forslag_listas_sist_och_kan_angras() {
        var undone: SwitchPlanResolver.Suggestion? = null
        composeRule.setContent {
            FonderTheme {
                BeslutContent(
                    state = stateWith().let { it.copy(decisions = it.decisions.copy(dismissedSuggestions = listOf(dyrTillBillig(recordId = 42)))) },
                    onUndoDismissal = { undone = it },
                )
            }
        }

        composeRule.onNodeWithText("Avstådda (1)").performScrollTo().performClick()
        composeRule.onNodeWithText("Ångra").performScrollTo().performClick()

        assertEquals(42L, undone?.recordId)
    }

    @Test
    fun inga_forslag_ger_inget_forslagsavsnitt_utan_ett_tomt_tillstand() {
        show(stateWith())

        composeRule.onNodeWithText("Förslag").assertDoesNotExist()
        composeRule.onNodeWithText("Inget att besluta just nu").assertIsDisplayed()
    }

    // --- Knappen "Räkna om bytesplanen" (HEM-8, issue #88) ---

    @Test
    fun omraknningsknappen_visas_nar_en_plan_kan_ges() {
        show(stateWith(canRecompute = true))

        composeRule.onNodeWithText("Räkna om bytesplanen").performScrollTo().assertExists()
    }

    @Test
    fun omraknningsknappen_uteblir_nar_ingen_plan_kan_ges() {
        // Depå/AF eller ingen profil — knappen ska inte lova något SET-4-gaten vägrar infria.
        show(stateWith(canRecompute = false))

        composeRule.onNodeWithText("Räkna om bytesplanen").assertDoesNotExist()
    }

    @Test
    fun omraknningsknappen_ar_slackt_medan_en_korning_pagar() {
        show(stateWith(canRecompute = true, backgroundWorkRunning = true))

        composeRule.onNodeWithText("Räknar om …").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun klick_pa_omraknningsknappen_anropar_callbacken() {
        var clicks = 0
        composeRule.setContent {
            FonderTheme { BeslutContent(state = stateWith(canRecompute = true), onRecomputeSwitchPlan = { clicks++ }) }
        }

        composeRule.onNodeWithText("Räkna om bytesplanen").performScrollTo().performClick()

        assertEquals(1, clicks)
    }

    // --- Pågående byten (ANA-12) ---

    @Test
    fun kortet_for_pagaende_byte_visar_ledaren_och_oppnar_bevakningen() {
        var opened: Long? = null
        val watch = OpenSwitchWatch(
            watchId = 7, sellFundName = "Fond A", daysWaiting = 2, candidateCount = 3,
            leaderName = "Kandidat B", leaderChangeFraction = 0.021,
        )
        composeRule.setContent {
            FonderTheme { BeslutContent(state = stateWith(watches = listOf(watch)), onOpenSwitchWatch = { opened = it }) }
        }

        composeRule.onNodeWithText("Fond A → Kandidat B").assertIsDisplayed()
        composeRule.onNodeWithText("Dag 2 av 14 i väntan på likviden").assertIsDisplayed()
        composeRule.onNodeWithText("3 alternativ bevakas").assertIsDisplayed()
        composeRule.onNodeWithText("Bäst sedan säljdagen: Kandidat B", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Registrera köpet").performClick()

        assertEquals(7L, opened)
    }

    @Test
    fun utan_uppmatt_utveckling_sags_det_ut_i_stallet_for_en_tom_rad() {
        // Kandidaternas kurser fylls på av bakgrundskörningen — tills dess finns ingen ledare,
        // och en tom rad hade lästs som "inget har hänt" (ANA-4-principen).
        show(stateWith(watches = listOf(OpenSwitchWatch(watchId = 7, sellFundName = "Fond A", daysWaiting = 1, candidateCount = 2))))

        composeRule.onNodeWithText("Fond A").assertIsDisplayed()
        composeRule.onNodeWithText("Ingen utveckling uppmätt än", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun ett_okvitterat_byte_erbjuder_ingen_bevakning() {
        // Bevakningen gäller perioden **efter** säljet — erbjuds den innan bytet gjorts mäter
        // den dagar då pengarna fortfarande låg kvar.
        show(stateWith(plan = listOf(dyrTillBillig(followed = false))))

        composeRule.onNodeWithText("Bevaka alternativ").assertDoesNotExist()
    }

    @Test
    fun ett_salj_som_redan_bevakas_erbjuder_ingen_ny_bevakning() {
        // Annars hade varje tryck startat ytterligare en bevakning av samma byte.
        show(stateWith(plan = listOf(dyrTillBillig(followed = true)), watchedSellIsins = setOf("SE0004297927")))

        composeRule.onNodeWithText("Bevaka alternativ").assertDoesNotExist()
    }

    @Test
    fun ett_kvitterat_byte_utan_bevakning_erbjuder_att_starta_en() {
        var started: SwitchPlanResolver.Suggestion? = null
        composeRule.setContent {
            FonderTheme {
                BeslutContent(
                    state = stateWith(plan = listOf(dyrTillBillig(followed = true))),
                    onStartSwitchWatch = { started = it },
                )
            }
        }

        composeRule.onNodeWithText("Bevaka alternativ").performScrollTo().performClick()

        assertEquals(1L, started?.recordId)
    }
}
