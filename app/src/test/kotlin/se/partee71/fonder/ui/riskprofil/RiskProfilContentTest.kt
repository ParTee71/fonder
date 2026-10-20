package se.partee71.fonder.ui.riskprofil

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.partee71.fonder.data.common.AppErrors
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.TimeHorizon
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.testing.contracts
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.common.EditorEvents
import se.partee71.ui.common.EditorState
import se.partee71.ui.testing.runEditScreenContract

/**
 * Riskprofilen på android-ui:s `EntityEditScreen` (SET-3, UI-15, NFR-6): ramens kontrakt (Spara i
 * toppraden först när ändrat och giltigt, "Släng ändringar?" vid bakåt, sparfel som snackbar, tillbaka
 * efter sparning), läsfel med "Försök igen", ingen dubbel rubrik och skärmdumpar ljust + mörkt.
 * Påhittade svar och fördelningar.
 */
@RunWith(RobolectricTestRunner::class)
class RiskProfilContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val levels = listOf(3, 4, 5)

    /** Formuläret som ViewModel:n håller det, med Riskprofilens validering på skalan [levels]. */
    private fun editor(stored: RiskProfileForm = RiskProfileForm()) = EditorState(RiskProfileForm(), riskProfileValidator { levels }, AppErrors).apply { load(stored) }

    private fun EditorState<RiskProfileForm>.uiState() = RiskProfilUiState(levels.toImmutableList(), state.value)

    @Config(qualifiers = "w390dp-h1000dp-xxhdpi")
    @Test
    fun `riskprofilen uppfyller redigeringsramens kontrakt`() {
        composeRule.runEditScreenContract(
            environment = contracts,
            editor = editor(),
            makeInvalid = { update(RISK_PROFILE_ALLOCATION) { it.copy(manualAllocationText = persistentMapOf(3 to "50")) } },
            makeValid = { update(RISK_PROFILE_ALLOCATION) { it.copy(manualAllocationText = persistentMapOf(3 to "50", 4 to "50")) } },
            invalidMessage = "Summan måste bli 100 % för att kunna sparas."
        ) { state, events, onSave, onClose ->
            RiskProfilContent(RiskProfilUiState(levels.toImmutableList(), state), events, onClose, onSave = onSave)
        }
    }

    @Test
    fun `rubriken star bara i toppraden`() {
        composeRule.setContent { FonderTheme { RiskProfilContent(editor().uiState(), EditorEvents.None, onClose = {}) } }
        composeRule.onAllNodesWithText("Riskprofil").assertCountEquals(1)
        composeRule.onAllNodesWithText("Spara").assertCountEquals(1)
    }

    @Test
    fun `en oforandrad sparad profil gar inte att spara igen`() {
        val stored = editor(RiskProfileForm(manualAllocationText = persistentMapOf(4 to "100")))
        composeRule.setContent { FonderTheme { RiskProfilContent(stored.uiState(), EditorEvents.None, onClose = {}) } }
        composeRule.onNodeWithText("Summa: 100 %").assertExists()
        composeRule.onNodeWithText("Spara").assertIsNotEnabled()
    }

    @Test
    fun `bakat utan andringar stanger direkt utan att fraga`() {
        var closes = 0
        composeRule.setContent { FonderTheme { RiskProfilContent(editor().uiState(), EditorEvents.None, onClose = { closes++ }) } }
        composeRule.onNodeWithContentDescription("Tillbaka").performClick()
        assertEquals(1, closes)
        composeRule.onNodeWithText("Släng ändringar?").assertDoesNotExist()
    }

    @Test
    fun `slang andringar stanger utan att spara`() {
        var closes = 0
        val changed = editor().apply { update(RISK_PROFILE_HORIZON) { it.copy(horizon = TimeHorizon.OVER_15_AR) } }
        composeRule.setContent { FonderTheme { RiskProfilContent(changed.uiState(), EditorEvents.None, onClose = { closes++ }) } }
        composeRule.onNodeWithContentDescription("Tillbaka").performClick()
        composeRule.onNodeWithText("Det du ändrat sparas inte.").assertExists()
        composeRule.onNodeWithText("Släng").performClick()
        assertEquals(1, closes)
    }

    @Test
    fun `en sparad profil som inte gick att lasa visas som fel med Forsok igen`() {
        var retries = 0
        val failed = editor().apply { loadFailed(DataError.Unknown) }
        composeRule.setContent { FonderTheme { RiskProfilContent(failed.uiState(), EditorEvents.None, onClose = {}, onRetry = { retries++ }) } }
        composeRule.onNodeWithText("Tidshorisont").assertDoesNotExist()
        composeRule.onNodeWithText("Spara").assertIsNotEnabled()
        composeRule.onNodeWithText("Försök igen").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `Spara ar slackt medan profilen sparas, utan att summan kallas fel`() {
        val saving = editor().apply { update(RISK_PROFILE_ALLOCATION) { it.copy(manualAllocationText = persistentMapOf(3 to "100")) } }
        val state = saving.uiState().let { it.copy(editor = it.editor.copy(saving = true)) }
        composeRule.setContent { FonderTheme { RiskProfilContent(state, EditorEvents.None, onClose = {}) } }
        composeRule.onNodeWithText("Spara").assertIsNotEnabled()
        composeRule.onNodeWithText("Summan måste bli 100 % för att kunna sparas.").assertDoesNotExist()
    }

    @Test
    fun `en giltig andring aktiverar Spara`() {
        val changed = editor().apply { update(RISK_PROFILE_ALLOCATION) { it.copy(manualAllocationText = persistentMapOf(5 to "100")) } }
        composeRule.setContent { FonderTheme { RiskProfilContent(changed.uiState(), EditorEvents.None, onClose = {}) } }
        composeRule.onNodeWithText("Spara").assertIsEnabled()
    }

    @Config(qualifiers = "w390dp-h1000dp-xxhdpi")
    @Test
    fun `skärmdump enkät med förslag`() {
        val answered = editor().apply {
            update(RISK_PROFILE_HORIZON) { it.copy(horizon = TimeHorizon.SJU_TILL_15_AR) }
            update(RISK_PROFILE_REACTION) { it.copy(reaction = DownturnReaction.GOR_INGET) }
            update(RISK_PROFILE_GOAL) { it.copy(goal = PrimaryGoal.BALANSERAD) }
        }.uiState()
        composeRule.captureLightAndDark("RiskProfil_suggestion") { RiskProfilContent(answered, EditorEvents.None, onClose = {}) }
    }

    @Config(qualifiers = "w390dp-h1000dp-xxhdpi")
    @Test
    fun `skärmdump läsfel`() {
        val failed = editor().apply { loadFailed(DataError.Unknown) }.uiState()
        composeRule.captureLightAndDark("RiskProfil_loadError") { RiskProfilContent(failed, EditorEvents.None, onClose = {}) }
    }
}
