package se.partee71.fonder.ui.riskprofil

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.usecase.RiskProfileCalc
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Instrumenterat test av Riskprofilens tillståndsdrivna innehåll (SET-3, issue #68 →
 * målfördelning i issue #71) — bygger [RiskProfilUiState] direkt i stället för att gå via ett
 * riktigt [RiskProfilViewModel]/Hilt.
 */
@RunWith(AndroidJUnit4::class)
class RiskProfilScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fullScale = (1..6).toList()

    @Test
    fun visar_forslag_nar_ingen_egen_andring_har_gjorts() {
        val state = RiskProfilUiState(availableLevels = fullScale, suggestedAllocation = RiskProfileCalc.Profile.BALANSERAD.allocation)

        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = state) }
        }

        composeRule.onNodeWithText("Enkätens förslag har fyllts i nedan.", substring = true).assertExists()
    }

    @Test
    fun forslagstext_visas_inte_efter_en_egen_andring() {
        val state = RiskProfilUiState(
            availableLevels = fullScale,
            suggestedAllocation = RiskProfileCalc.Profile.BALANSERAD.allocation,
            manualAllocationText = mapOf(3 to "100"),
        )

        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = state) }
        }

        composeRule.onNodeWithText("Enkätens förslag", substring = true).assertDoesNotExist()
    }

    @Test
    fun andring_av_ett_procentfalt_anropar_onAllocationPercentChanged() {
        var changed: Pair<Int, String>? = null
        val state = RiskProfilUiState(availableLevels = fullScale)

        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = state, onAllocationPercentChanged = { level, text -> changed = level to text }) }
        }

        composeRule.onNodeWithTag(allocationFieldTestTag(4)).performTextReplacement("100")

        assertEquals(4 to "100", changed)
    }

    @Test
    fun sparaknappen_ar_inaktiv_nar_summan_inte_ar_100() {
        val state = RiskProfilUiState(availableLevels = fullScale, manualAllocationText = mapOf(3 to "50"))

        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = state) }
        }

        composeRule.onNodeWithText("Spara").assertIsNotEnabled()
    }

    @Test
    fun sparaknappen_ar_aktiv_nar_summan_ar_100() {
        val state = RiskProfilUiState(availableLevels = fullScale, manualAllocationText = mapOf(3 to "100"))

        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = state) }
        }

        composeRule.onNodeWithText("Spara").assertIsEnabled()
    }

    @Test
    fun sparaknappen_anropar_onSave() {
        var saved = false
        val state = RiskProfilUiState(availableLevels = fullScale, manualAllocationText = mapOf(3 to "100"))

        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = state, onSave = { saved = true }) }
        }

        // Sex procentfält (en per nivå i fullScale) trycker ner Spara-knappen under den
        // initiala viewporten — performClick() dispatchar en riktig touch och kräver att
        // noden faktiskt är synlig, till skillnad från assertIsEnabled() (jfr skarmen_ar_skrollbar).
        composeRule.onNodeWithText("Spara").performScrollTo().performClick()

        assertTrue(saved)
    }

    @Test
    fun visar_summan_och_felmeddelande_vid_fel_summa() {
        val state = RiskProfilUiState(availableLevels = fullScale, manualAllocationText = mapOf(3 to "50"))

        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = state) }
        }

        composeRule.onNodeWithText("Summa: 50 %").assertExists()
        composeRule.onNodeWithText("Summan måste bli 100 %", substring = true).assertExists()
    }

    @Test
    fun ingen_felmeddelande_nar_summan_ar_100() {
        val state = RiskProfilUiState(availableLevels = fullScale, manualAllocationText = mapOf(3 to "100"))

        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = state) }
        }

        composeRule.onNodeWithText("Summa: 100 %").assertExists()
        composeRule.onNodeWithText("Summan måste bli 100 %", substring = true).assertDoesNotExist()
    }

    @Test
    fun visar_utgangspunkt_inte_optima_disclaimer() {
        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = RiskProfilUiState(availableLevels = fullScale)) }
        }

        composeRule.onNodeWithText("utgångspunkter, inte optima", substring = true).assertExists()
    }

    @Test
    fun visar_hint_text_nar_skalan_ar_tom() {
        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = RiskProfilUiState(availableLevels = emptyList())) }
        }

        composeRule.onNodeWithText("Kan inte föreslå en skala än", substring = true).assertExists()
    }

    @Test
    fun visar_alla_tre_fragorna() {
        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = RiskProfilUiState(availableLevels = fullScale)) }
        }

        composeRule.onNodeWithText("Tidshorisont").assertExists()
        composeRule.onNodeWithText("Om portföljen tappade 30 % på ett år").assertExists()
        composeRule.onNodeWithText("Primärt mål med sparandet").assertExists()
    }

    @Test
    fun skarmen_ar_skrollbar() {
        // Fast Column + verticalScroll (inte en lazy items()-lista, jfr #66) — en enkel
        // performScrollTo().assertIsDisplayed() på det sista elementet räcker (UI-5).
        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = RiskProfilUiState(availableLevels = fullScale, manualAllocationText = mapOf(3 to "100"))) }
        }

        composeRule.onNodeWithText("Spara").performScrollTo().assertIsDisplayed()
    }
}
