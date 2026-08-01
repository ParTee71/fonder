package se.partee71.fonder.ui.riskprofil

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Instrumenterat test av Riskprofilens tillståndsdrivna innehåll (SET-3, issue #68) — bygger
 * [RiskProfilUiState] direkt i stället för att gå via ett riktigt [RiskProfilViewModel]/Hilt.
 */
@RunWith(AndroidJUnit4::class)
class RiskProfilScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visar_forslag_nar_nivan_ar_foreslagen_men_inte_egenvald() {
        val state = RiskProfilUiState(availableLevels = (1..6).toList(), suggestedLevel = 4, manualLevel = null)

        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = state) }
        }

        composeRule.onNodeWithText("Förslag: nivå 4").assertExists()
    }

    @Test
    fun forslagstext_visas_inte_efter_ett_eget_val() {
        val state = RiskProfilUiState(availableLevels = (1..6).toList(), suggestedLevel = 4, manualLevel = 2)

        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = state) }
        }

        composeRule.onNodeWithText("Förslag:", substring = true).assertDoesNotExist()
    }

    @Test
    fun klick_pa_en_niva_anropar_onLevelSelected() {
        var selected: Int? = null
        val state = RiskProfilUiState(availableLevels = (1..6).toList())

        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = state, onLevelSelected = { selected = it }) }
        }

        composeRule.onNodeWithText("3").performClick()

        assertTrue(selected == 3)
    }

    @Test
    fun sparaknappen_ar_inaktiv_utan_en_vald_niva_och_aktiv_med_en() {
        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = RiskProfilUiState(availableLevels = (1..6).toList())) }
        }
        composeRule.onNodeWithText("Spara").assertIsNotEnabled()

        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = RiskProfilUiState(availableLevels = (1..6).toList(), manualLevel = 3)) }
        }
        composeRule.onNodeWithText("Spara").assertIsEnabled()
    }

    @Test
    fun sparaknappen_anropar_onSave() {
        var saved = false
        val state = RiskProfilUiState(availableLevels = (1..6).toList(), manualLevel = 3)

        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = state, onSave = { saved = true }) }
        }

        composeRule.onNodeWithText("Spara").performClick()

        assertTrue(saved)
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
            FonderTheme { RiskProfilContent(state = RiskProfilUiState(availableLevels = (1..6).toList())) }
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
            FonderTheme { RiskProfilContent(state = RiskProfilUiState(availableLevels = (1..6).toList(), manualLevel = 3)) }
        }

        composeRule.onNodeWithText("Spara").performScrollTo().assertIsDisplayed()
    }
}
