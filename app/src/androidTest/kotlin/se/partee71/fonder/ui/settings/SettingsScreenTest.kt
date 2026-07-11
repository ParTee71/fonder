package se.partee71.fonder.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Instrumenterat test av Inställningars tillståndsdrivna innehåll (issue #27) — fokuserar på
 * kursuppdateringskortet (SET-2): "Senast uppdaterad" och "Uppdatera nu".
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visar_aldrig_uppdaterad_utan_kand_synktid() {
        composeRule.setContent {
            FonderTheme {
                SettingsContent(state = SettingsUiState(lastPriceSyncEpochMillis = null))
            }
        }

        composeRule.onNodeWithText("Aldrig uppdaterad").assertExists()
    }

    @Test
    fun visar_senast_uppdaterad_tidsstampel_nar_kand() {
        // 2024-03-15 12:00 UTC — matchar mönstret oavsett exakt lokal tidszon i CI.
        composeRule.setContent {
            FonderTheme {
                SettingsContent(state = SettingsUiState(lastPriceSyncEpochMillis = 1710504000000L))
            }
        }

        composeRule.onNodeWithText("Senast uppdaterad:", substring = true).assertExists()
        composeRule.onNodeWithText("Aldrig uppdaterad").assertDoesNotExist()
    }

    @Test
    fun uppdatera_nu_knappen_anropar_callback() {
        var called = false
        composeRule.setContent {
            FonderTheme {
                SettingsContent(state = SettingsUiState(), onRefreshPricesNow = { called = true })
            }
        }

        composeRule.onNodeWithText("Uppdatera nu").performClick()
        assertTrue(called)
    }
}
