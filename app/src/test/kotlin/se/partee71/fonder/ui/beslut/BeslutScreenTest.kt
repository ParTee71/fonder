package se.partee71.fonder.ui.beslut

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.RiskProfileAnswers
import se.partee71.fonder.domain.model.TimeHorizon
import se.partee71.fonder.domain.usecase.OpenSwitchWatch
import se.partee71.fonder.domain.usecase.PendingDecisions
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Fliken Beslut (NAV-9) i JVM med Robolectric: rubrikraden mot riskprofilen, tomt läge, vägen till
 * Facit och skärmdumpar ljust + mörkt. Förslagens och bevakningarnas egna interaktioner testas
 * instrumenterat i `androidTest/…/ui/beslut/BeslutScreenTest` (flyttade från Hem).
 */
@RunWith(RobolectricTestRunner::class)
class BeslutScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val balanced = RiskProfile(
        targetAllocation = mapOf(4 to 1.0),
        answers = RiskProfileAnswers(TimeHorizon.SJU_TILL_15_AR, DownturnReaction.GOR_INGET, PrimaryGoal.BALANSERAD),
    )

    /** Mockupens läge: ett pågående byte, två förslag (ett med Följ/Avstå, ett kvitterat) och sju följda i facit. */
    private val full = BeslutUiState(
        loading = false,
        riskProfile = balanced,
        accountType = AccountType.ISK_KF,
        followedCount = 7,
        decisions = PendingDecisions(
            openWatches = listOf(
                OpenSwitchWatch(
                    watchId = 3, sellFundName = "Sverige Småbolag", daysWaiting = 2, candidateCount = 3,
                    leaderName = "Global Indexfond A", leaderChangeFraction = 0.012,
                ),
            ),
            switchPlan = listOf(
                SwitchPlanResolver.Suggestion(
                    recordId = 11, planIndex = 0, sellIsin = "SE_TM", buyIsin = "SE_GI",
                    sellFundName = "Tillväxtmarknad Bred", buyFundName = "Global Indexfond A",
                    fromLevel = 6, toLevel = 4, feeDeltaPercent = -0.9, switchValueKr = 300.0,
                ),
                SwitchPlanResolver.Suggestion(
                    recordId = 12, planIndex = 1, sellIsin = "SE_SB", buyIsin = "SE_RF",
                    sellFundName = "Sverige Småbolag", buyFundName = "Räntefond Kort",
                    fromLevel = 5, toLevel = 2, feeDeltaPercent = -0.2, switchValueKr = 450.0, followed = true,
                ),
            ),
        ),
    )

    /** Allt i planen avstått (HEM-14): inget att besluta, men raden för att ångra finns kvar. */
    private val withDismissed = full.copy(
        decisions = PendingDecisions(dismissedSuggestions = listOf(full.decisions.switchPlan.first())),
    )

    @Test
    fun `rubrikraden visar riskprofil mal och kontotyp och Andra oppnar riskprofilen`() {
        var opened = 0
        composeRule.setContent { FonderTheme { BeslutContent(state = full, onOpenRiskProfile = { opened++ }) } }

        composeRule.onNodeWithText("Mot riskprofil 4,00 · Balanserad tillväxt · ISK/KF").assertIsDisplayed()
        composeRule.onNodeWithText("Ändra").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `utan profil sags det ut i stallet for en gissad niva`() {
        composeRule.setContent { FonderTheme { BeslutContent(state = BeslutUiState(loading = false)) } }

        composeRule.onNodeWithText("Ingen riskprofil satt").assertIsDisplayed()
    }

    @Test
    fun `tomt lage visar tomt tillstand men behaller vagen till facit`() {
        var facit = 0
        composeRule.setContent {
            FonderTheme { BeslutContent(state = BeslutUiState(loading = false, followedCount = 1), onOpenFacit = { facit++ }) }
        }

        composeRule.onNodeWithText("Inget att besluta just nu").assertIsDisplayed()
        composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG).performScrollToNode(hasText("Facit · 1 följt förslag"))
        composeRule.onNodeWithText("Facit · 1 följt förslag").performClick()

        assertEquals(1, facit)
    }

    @Test
    fun `avsnitten visas i mockupens ordning med facit sist`() {
        composeRule.setContent { FonderTheme { BeslutContent(state = full) } }

        composeRule.onNodeWithText("Pågående byte").assertIsDisplayed()
        composeRule.onNodeWithText("Sverige Småbolag → Global Indexfond A").assertIsDisplayed()
        // Lat lista: det som ligger under skärmkanten komponeras först när listan skrollats dit (UI-5).
        listOf("Förslag", "Hur gick det?", "Facit · 7 följda förslag").forEach { text ->
            composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG).performScrollToNode(hasText(text))
            composeRule.onNodeWithText(text).assertIsDisplayed()
        }
        composeRule.onNodeWithText("Inget att besluta just nu").assertDoesNotExist()
    }

    @Test
    fun `folj och avsta visas pa ett okvitterat forslag och anropar sina callbacks`() {
        // HEM-14: "Följ" är kvitteringen (SET-5), "Avstå" döljer paret.
        var followed: Pair<Long, Boolean>? = null
        var dismissed: SwitchPlanResolver.Suggestion? = null
        composeRule.setContent {
            FonderTheme {
                BeslutContent(
                    state = full,
                    onSwitchFollowedChange = { id, value -> followed = id to value },
                    onDismissSuggestion = { dismissed = it },
                )
            }
        }

        composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG).performScrollToNode(hasText("Avstå"))
        // Bara det okvitterade förslaget har knapparna; det kvitterade visar "Genomförd".
        composeRule.onAllNodesWithText("Följ").assertCountEquals(1)
        composeRule.onNodeWithText("Avstå").performClick()
        composeRule.onNodeWithText("Följ").performClick()

        assertEquals(11L, dismissed?.recordId)
        assertEquals(11L to true, followed)
    }

    @Test
    fun `ett kvitterat forslag visar Genomford i stallet for Folj och Avsta`() {
        var followed: Pair<Long, Boolean>? = null
        composeRule.setContent {
            FonderTheme { BeslutContent(state = full, onSwitchFollowedChange = { id, value -> followed = id to value }) }
        }

        composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG).performScrollToNode(hasText("Genomförd"))
        composeRule.onNodeWithText("Genomförd").performClick()

        // Kvitteringen ångras på samma sätt som i Facit och Fonddetalj.
        assertEquals(12L to false, followed)
    }

    @Test
    fun `avstadda forslag ligger sist och kan angras`() {
        var undone: SwitchPlanResolver.Suggestion? = null
        composeRule.setContent {
            FonderTheme { BeslutContent(state = withDismissed, onUndoDismissal = { undone = it }) }
        }

        composeRule.onNodeWithText("Inget att besluta just nu").assertIsDisplayed()
        composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG).performScrollToNode(hasText("Avstådda (1)"))
        composeRule.onNodeWithText("Avstådda (1)").performClick()
        composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG).performScrollToNode(hasText("Ångra"))
        composeRule.onNodeWithText("1. Sälj Tillväxtmarknad Bred (nivå 6) → Köp Global Indexfond A (nivå 4)").assertIsDisplayed()
        composeRule.onNodeWithText("Ångra").performClick()

        assertEquals(11L, undone?.recordId)
    }

    @Test
    fun `utan avstadda forslag visas ingen avstadda-rad`() {
        composeRule.setContent { FonderTheme { BeslutContent(state = full) } }

        composeRule.onNodeWithText("Avstådda", substring = true).assertDoesNotExist()
    }

    @Test
    fun `skärmdump med byte förslag och facit`() = composeRule.captureLightAndDark("BeslutScreen_full") {
        BeslutContent(state = full)
    }

    @Test
    fun `skärmdump med ett avstått förslag`() = composeRule.captureLightAndDark("BeslutScreen_dismissed") {
        BeslutContent(state = withDismissed)
    }

    @Test
    fun `skärmdump utan något att besluta`() = composeRule.captureLightAndDark("BeslutScreen_empty") {
        BeslutContent(state = BeslutUiState(loading = false, riskProfile = balanced, accountType = AccountType.ISK_KF))
    }
}
