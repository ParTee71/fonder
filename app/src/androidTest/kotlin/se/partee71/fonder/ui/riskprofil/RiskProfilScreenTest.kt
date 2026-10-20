package se.partee71.fonder.ui.riskprofil

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.common.AppErrors
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.TimeHorizon
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.common.EditorEvents
import se.partee71.ui.common.EditorState

/**
 * Instrumenterat test av Riskprofilens tillståndsdrivna innehåll (SET-3, UI-15) — bygger
 * [RiskProfilUiState] ur ett [EditorState] med Riskprofilens validering i stället för att gå via ett
 * riktigt [RiskProfilViewModel]/Hilt. Spara sitter i ramens topprad (`EntityEditScreen`).
 */
@RunWith(AndroidJUnit4::class)
class RiskProfilScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fullScale = (1..6).toList()

    /** Tillståndet efter att användaren ändrat formuläret till [form] (ändrat = Spara kan aktiveras). */
    private fun formState(levels: List<Int> = fullScale, form: RiskProfileForm = RiskProfileForm()): RiskProfilUiState {
        val editor = EditorState(RiskProfileForm(), riskProfileValidator { levels }, AppErrors)
        editor.update { form }
        return RiskProfilUiState(levels.toImmutableList(), editor.state.value)
    }

    @Composable
    private fun Content(
        state: RiskProfilUiState,
        onAllocationPercentChanged: (Int, String) -> Unit = { _, _ -> },
        onSave: () -> Unit = {}
    ) = RiskProfilContent(state, EditorEvents.None, onClose = {}, onAllocationPercentChanged = onAllocationPercentChanged, onSave = onSave)

    @Test
    fun visar_forslag_nar_ingen_egen_andring_har_gjorts() {
        val state = formState(form = RiskProfileForm(TimeHorizon.TRE_TILL_7_AR, DownturnReaction.GOR_INGET, PrimaryGoal.BALANSERAD))

        composeRule.setContent {
            FonderTheme { Content(state) }
        }

        composeRule.onNodeWithText("Enkätens förslag har fyllts i nedan.", substring = true).assertExists()
    }

    @Test
    fun forslagstext_visas_inte_efter_en_egen_andring() {
        val state = formState(form = RiskProfileForm(TimeHorizon.TRE_TILL_7_AR, DownturnReaction.GOR_INGET, PrimaryGoal.BALANSERAD, persistentMapOf(3 to "100")))

        composeRule.setContent {
            FonderTheme { Content(state) }
        }

        composeRule.onNodeWithText("Enkätens förslag", substring = true).assertDoesNotExist()
    }

    @Test
    fun andring_av_ett_procentfalt_anropar_onAllocationPercentChanged() {
        var changed: Pair<Int, String>? = null
        val state = formState()

        composeRule.setContent {
            FonderTheme { Content(state, onAllocationPercentChanged = { level, text -> changed = level to text }) }
        }

        composeRule.onNodeWithTag(allocationFieldTestTag(4)).performTextReplacement("100")

        assertEquals(4 to "100", changed)
    }

    @Test
    fun sparaknappen_ar_inaktiv_nar_summan_inte_ar_100() {
        val state = formState(form = RiskProfileForm(manualAllocationText = persistentMapOf(3 to "50")))

        composeRule.setContent {
            FonderTheme { Content(state) }
        }

        composeRule.onNodeWithText("Spara").assertIsNotEnabled()
    }

    @Test
    fun sparaknappen_ar_aktiv_nar_summan_ar_100() {
        val state = formState(form = RiskProfileForm(manualAllocationText = persistentMapOf(3 to "100")))

        composeRule.setContent {
            FonderTheme { Content(state) }
        }

        composeRule.onNodeWithText("Spara").assertIsEnabled()
    }

    @Test
    fun sparaknappen_anropar_onSave() {
        var saved = false
        val state = formState(form = RiskProfileForm(manualAllocationText = persistentMapOf(3 to "100")))

        composeRule.setContent {
            FonderTheme { Content(state, onSave = { saved = true }) }
        }

        // Spara sitter i ramens topprad och syns alltid, hur många procentfält skalan än har.
        composeRule.onNodeWithText("Spara").assertIsDisplayed().performClick()

        assertTrue(saved)
    }

    @Test
    fun visar_summan_och_felmeddelande_vid_fel_summa() {
        val state = formState(form = RiskProfileForm(manualAllocationText = persistentMapOf(3 to "50")))

        composeRule.setContent {
            FonderTheme { Content(state) }
        }

        composeRule.onNodeWithText("Summa: 50 %").assertExists()
        composeRule.onNodeWithText("Summan måste bli 100 %", substring = true).assertExists()
    }

    @Test
    fun ingen_felmeddelande_nar_summan_ar_100() {
        val state = formState(form = RiskProfileForm(manualAllocationText = persistentMapOf(3 to "100")))

        composeRule.setContent {
            FonderTheme { Content(state) }
        }

        composeRule.onNodeWithText("Summa: 100 %").assertExists()
        composeRule.onNodeWithText("Summan måste bli 100 %", substring = true).assertDoesNotExist()
    }

    @Test
    fun visar_utgangspunkt_inte_optima_disclaimer() {
        composeRule.setContent {
            FonderTheme { Content(formState()) }
        }

        composeRule.onNodeWithText("utgångspunkter, inte optima", substring = true).assertExists()
    }

    @Test
    fun visar_hint_text_nar_skalan_ar_tom() {
        composeRule.setContent {
            FonderTheme { Content(formState(levels = emptyList())) }
        }

        composeRule.onNodeWithText("Kan inte föreslå en skala än", substring = true).assertExists()
    }

    @Test
    fun visar_alla_tre_fragorna() {
        composeRule.setContent {
            FonderTheme { Content(formState()) }
        }

        composeRule.onNodeWithText("Tidshorisont").assertExists()
        composeRule.onNodeWithText("Om portföljen tappade 30 % på ett år").assertExists()
        composeRule.onNodeWithText("Primärt mål med sparandet").assertExists()
    }

    @Test
    fun skarmen_ar_skrollbar() {
        // Ramens Column + verticalScroll (inte en lazy items()-lista, jfr #66) — en enkel
        // performScrollTo().assertIsDisplayed() på det sista elementet räcker (UI-5).
        composeRule.setContent {
            FonderTheme { Content(formState(form = RiskProfileForm(manualAllocationText = persistentMapOf(3 to "100")))) }
        }

        composeRule.onNodeWithText("Summa: 100 %").performScrollTo().assertIsDisplayed()
    }
}
