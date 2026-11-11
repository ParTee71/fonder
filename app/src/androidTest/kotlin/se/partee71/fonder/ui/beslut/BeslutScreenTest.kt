package se.partee71.fonder.ui.beslut

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.usecase.CheaperAlternative
import se.partee71.fonder.domain.usecase.OpenSwitchWatch
import se.partee71.fonder.domain.usecase.PendingDecisions
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.domain.usecase.SwitchRow
import se.partee71.fonder.ui.components.FundFacts
import se.partee71.fonder.ui.components.PULL_TO_REFRESH_TEST_TAG
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Instrumenterat test av fliken Beslut (NAV-9) mot [BeslutContent] med handskrivet tillstånd.
 * Bytesplanens och de pågående bytenas tester flyttades hit från `HemScreenTest` när de lämnade
 * Översikten (0.61.0). Sedan issue #240: råden i klarspråk, infokortet (BYT-5) och dra för att
 * uppdatera (UI-11) i stället för omräkningsknappen.
 */
@RunWith(AndroidJUnit4::class)
class BeslutScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun dyrTillBillig(recordId: Long = 1, planIndex: Int = 0, switchValueKr: Double? = 250.0, followed: Boolean = false) = SwitchPlanResolver.Suggestion(
        recordId = recordId, planIndex = planIndex, sellIsin = "SE0004297927", buyIsin = "SE0000581434",
        sellFundName = "Dyr fond", buyFundName = "Billig fond",
        fromLevel = 5, toLevel = 3, feeDeltaPercent = -0.7, switchValueKr = switchValueKr, followed = followed
    )

    private fun stateWith(
        plan: List<SwitchPlanResolver.Suggestion> = emptyList(),
        watches: List<OpenSwitchWatch> = emptyList(),
        watchedSellIsins: Set<String> = emptySet(),
        cheaper: List<CheaperAlternative> = emptyList(),
        dismissed: List<SwitchPlanResolver.Suggestion> = emptyList(),
        canRecompute: Boolean = false
    ) = BeslutUiState(
        riskProfile = RiskProfile(targetAllocation = mapOf(3 to 1.0)),
        accountType = if (canRecompute) AccountType.ISK_KF else AccountType.DEPA_AF
    ).withDecisions(
        PendingDecisions(
            switchPlan = plan,
            openWatches = watches,
            watchedSellIsins = watchedSellIsins,
            cheaperAlternatives = cheaper,
            dismissedSuggestions = dismissed
        ).withSwitches(emptyList(), LocalDate.now())
    )

    private fun show(state: BeslutUiState) = composeRule.setContent { FonderTheme { BeslutContent(state = state) } }

    // --- Bytesplanen (HEM-8) ---

    @Test
    fun bytesplanen_visar_steget_beloppet_och_vart_pengarna_flyttas() {
        show(stateWith(plan = listOf(dyrTillBillig())))

        composeRule.onNodeWithText("Så kommer du närmare målet").assertExists()
        composeRule.onNodeWithText("Sänker risken · steg 1").performScrollTo().assertIsDisplayed()
        // Beloppet måste stå ut: bytet avser gapet, inte hela positionen (issue #75). Belopp
        // under tusen — tusentalsavgränsaren kan vara vanligt eller hårt blanksteg beroende på
        // JVM/lokal (se MoneyFormatTest), och ett test som beror på vilket vore flakigt.
        composeRule.onNodeWithText("Dyr fond → Billig fond, 250 kr").assertExists()
        // Utan riskkort (ingen portfölj i tillståndet) sägs bara vart pengarna flyttas — aldrig en gissad andel.
        composeRule.onNodeWithText("Flyttar pengar från nivå 5 till nivå 3. Dessutom 0,70 % lägre avgift per år.").assertExists()
    }

    @Test
    fun bytesplanens_steg_kommer_ur_planIndex_inte_listpositionen() {
        // Regression (issue #75): numret togs ur listpositionen, så ett byte vars föregångare
        // fallit bort presenterades som steg 1 — fast planen är girig och sekventiell.
        show(stateWith(plan = listOf(dyrTillBillig(planIndex = 1))))

        composeRule.onNodeWithText("Sänker risken · steg 2").performScrollTo().assertExists()
    }

    @Test
    fun bytesplan_utan_belopp_for_forslag_inspelade_fore_beloppsfaltet() {
        show(stateWith(plan = listOf(dyrTillBillig(switchValueKr = null))))

        composeRule.onNodeWithText("Dyr fond → Billig fond").performScrollTo().assertExists()
        composeRule.onNodeWithText("Dyr fond → Billig fond,", substring = true).assertDoesNotExist()
    }

    @Test
    fun jag_har_salt_oppnar_saljformularet_for_forslaget() {
        // HEM-14/BYT-2: "Jag har sålt" utför inget byte och kvitterar ingenting — den öppnar säljformuläret.
        var followed: Long? = null
        composeRule.setContent {
            FonderTheme {
                BeslutContent(
                    state = stateWith(plan = listOf(dyrTillBillig(recordId = 42))),
                    onFollow = { followed = it.recordId }
                )
            }
        }

        composeRule.onNodeWithText("Genomförd").assertDoesNotExist()
        composeRule.onNodeWithText("Jag har sålt").performScrollTo().performClick()

        assertEquals(42L, followed)
    }

    @Test
    fun ett_kvitterat_forslag_har_varken_jag_har_salt_hoppa_over_eller_kryssruta() {
        // Kvitteringen ändras bara i Facit (SET-5).
        composeRule.setContent {
            FonderTheme { BeslutContent(state = stateWith(plan = listOf(dyrTillBillig(recordId = 42, followed = true)))) }
        }

        composeRule.onNodeWithText("Jag har sålt").assertDoesNotExist()
        composeRule.onNodeWithText("Hoppa över").assertDoesNotExist()
        composeRule.onNodeWithText("Genomförd").assertDoesNotExist()
    }

    @Test
    fun hoppa_over_anropar_callbacken_med_forslaget() {
        var dismissed: SwitchPlanResolver.Suggestion? = null
        composeRule.setContent {
            FonderTheme {
                BeslutContent(
                    state = stateWith(plan = listOf(dyrTillBillig(recordId = 42))),
                    onDismissSuggestion = { dismissed = it }
                )
            }
        }

        composeRule.onNodeWithText("Hoppa över").performScrollTo().performClick()

        assertEquals(42L, dismissed?.recordId)
    }

    @Test
    fun overhoppade_forslag_listas_sist_och_kan_angras() {
        var undone: SwitchPlanResolver.Suggestion? = null
        composeRule.setContent {
            FonderTheme {
                BeslutContent(
                    state = stateWith(dismissed = listOf(dyrTillBillig(recordId = 42))),
                    onUndoDismissal = { undone = it }
                )
            }
        }

        composeRule.onNodeWithText("Avslutade och överhoppade (1)").performScrollTo().performClick()
        composeRule.onNodeWithText("Överhoppat").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ångra").performScrollTo().performClick()

        assertEquals(42L, undone?.recordId)
    }

    // --- Infokortet (BYT-5, issue #240) ---

    @Test
    fun infokortet_oppnas_fran_utropstecknet_och_stangs_med_Stang() {
        val row = SwitchRow.Suggested(dyrTillBillig(recordId = 42))
        composeRule.setContent {
            var info by remember { mutableStateOf<SuggestionInfo?>(null) }
            FonderTheme {
                BeslutContent(
                    state = stateWith(plan = listOf(row.suggestion)).copy(info = info),
                    onShowInfo = { info = SuggestionInfo(it, FundFacts(name = "Billig fond", risk = 3, company = "Fondbolaget AB"), emptyList()) },
                    onHideInfo = { info = null }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Mer om fonderna: Dyr fond → Billig fond").performScrollTo().performClick()
        composeRule.onNodeWithText("Fondbolaget AB").assertIsDisplayed()
        composeRule.onNodeWithText("Inga andra köpbara fonder hittades på nivå 3.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Stäng").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.onAllNodesWithText("Fondbolaget AB").fetchSemanticsNodes().isEmpty() }
    }

    // --- Dra för att uppdatera (UI-11) ---

    @Test
    fun en_dragning_nedat_raknar_om_planen_nar_gaten_tillater() {
        var refreshes = 0
        composeRule.setContent {
            FonderTheme { BeslutContent(state = stateWith(plan = listOf(dyrTillBillig()), canRecompute = true), onRefresh = { refreshes++ }) }
        }

        composeRule.onNodeWithTag(PULL_TO_REFRESH_TEST_TAG).performTouchInput { swipeDown() }

        // waitUntil: callbacken kommer först när utdragningen animerat klart (se PullToRefreshContainerTest).
        composeRule.waitUntil(timeoutMillis = 5_000) { refreshes == 1 }
        assertEquals(1, refreshes)
    }

    @Test
    fun utan_gaten_finns_ingen_dragning_utan_en_text() {
        // Depå/AF — ingen gest som lovar något SET-4-gaten vägrar infria.
        show(stateWith(plan = listOf(dyrTillBillig()), canRecompute = false))

        composeRule.onNodeWithTag(PULL_TO_REFRESH_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Byten föreslås när du satt ett mål och kontotypen ISK/KF.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Räkna om bytesplanen").assertDoesNotExist()
    }

    // --- Billigare alternativ (ANA-9) som egna förslag ---

    private fun billigareAlternativ(followed: Boolean = false) = CheaperAlternative(
        suggestion = dyrTillBillig(recordId = 77, switchValueKr = 500.0, followed = followed).copy(kind = SuggestionKind.FEE),
        sellFeePercent = 1.40,
        buyFeePercent = 0.40
    )

    @Test
    fun billigare_alternativ_visar_paret_avgifterna_och_besparingen_och_kan_hoppas_over() {
        var dismissed: SwitchPlanResolver.Suggestion? = null
        composeRule.setContent {
            FonderTheme {
                BeslutContent(
                    state = stateWith(cheaper = listOf(billigareAlternativ())),
                    onDismissSuggestion = { dismissed = it }
                )
            }
        }

        composeRule.onNodeWithText("Sparar avgift · påverkar inte risken").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Dyr fond → Billig fond").performScrollTo().assertIsDisplayed()
        // 1,40 % − 0,40 % av 500 kr = 5 kr per år (under tusen: se tusentalsavgränsaren ovan).
        composeRule.onNodeWithText("Samma sorts fond, 1,40 % → 0,40 % i avgift. Ca 5 kr/år.").assertExists()
        composeRule.onNodeWithText("Hoppa över").performScrollTo().performClick()

        assertEquals(SuggestionKind.FEE, dismissed?.kind)
        assertEquals(77L, dismissed?.recordId)
    }

    @Test
    fun ett_kvitterat_billigare_alternativ_visar_varken_folj_eller_kryssruta() {
        composeRule.setContent {
            FonderTheme {
                BeslutContent(
                    state = stateWith(cheaper = listOf(billigareAlternativ(followed = true)))
                )
            }
        }

        composeRule.onNodeWithText("Jag har sålt").assertDoesNotExist()
        composeRule.onNodeWithText("Genomförd").assertDoesNotExist()
    }

    @Test
    fun inga_forslag_ger_inget_forslagsavsnitt_utan_ett_tomt_tillstand() {
        show(stateWith())

        composeRule.onNodeWithText("Så kommer du närmare målet").assertDoesNotExist()
        composeRule.onNodeWithText("Inga byten att besluta om").performScrollTo().assertIsDisplayed()
    }

    // --- Sålda byten (ANA-12, BYT-4) ---

    @Test
    fun kortet_for_pagaende_byte_visar_ledaren_och_oppnar_bevakningen() {
        var opened: Long? = null
        val watch = OpenSwitchWatch(
            watchId = 7,
            sellFundName = "Fond A",
            daysWaiting = 2,
            candidateCount = 3,
            leaderName = "Kandidat B",
            leaderChangeFraction = 0.021
        )
        composeRule.setContent {
            FonderTheme { BeslutContent(state = stateWith(watches = listOf(watch)), onOpenSwitchWatch = { opened = it }) }
        }

        composeRule.onNodeWithText("Fond A → ?").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sålt · dag 2 av 14 · välj köpfond").assertIsDisplayed()
        composeRule.onNodeWithText("Bäst hittills: Kandidat B", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Välj köpfond").performScrollTo().performClick()

        assertEquals(7L, opened)
    }

    @Test
    fun utan_uppmatt_utveckling_sags_det_ut_i_stallet_for_en_tom_rad() {
        // Kandidaternas kurser fylls på av bakgrundskörningen — tills dess finns ingen ledare,
        // och en tom rad hade lästs som "inget har hänt" (ANA-4-principen).
        show(stateWith(watches = listOf(OpenSwitchWatch(watchId = 7, sellFundName = "Fond A", daysWaiting = 1, candidateCount = 2))))

        composeRule.onNodeWithText("Fond A → ?").performScrollTo().assertIsDisplayed()
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
                    onStartSwitchWatch = { started = it }
                )
            }
        }

        composeRule.onNodeWithText("Bevaka alternativ").performScrollTo().performClick()

        assertEquals(1L, started?.recordId)
    }
}
