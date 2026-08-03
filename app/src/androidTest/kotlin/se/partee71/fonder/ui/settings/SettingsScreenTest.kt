package se.partee71.fonder.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.model.AccountType
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

    // --- Riskprofil-ingång (SET-3, issue #68) ---

    @Test
    fun temavaljaren_visar_alla_tre_lagen_via_delad_ChoiceChipRow() {
        // Regression efter extraheringen till den delade ChoiceChipRow (issue #68) —
        // temaväljaren ska fungera precis som innan omskrivningen.
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState()) }
        }

        composeRule.onNodeWithText("Ljust").assertExists()
        composeRule.onNodeWithText("Mörkt").assertExists()
        composeRule.onNodeWithText("Auto").assertExists()
    }

    @Test
    fun riskprofil_knappen_anropar_callback() {
        var called = false
        composeRule.setContent {
            FonderTheme {
                SettingsContent(state = SettingsUiState(), onOpenRiskProfile = { called = true })
            }
        }

        composeRule.onNodeWithText("Öppna riskprofil").performClick()
        assertTrue(called)
    }

    // --- Kontotyp (SET-4, issue #70) ---

    @Test
    fun kontotypvaljaren_visar_bada_alternativen() {
        composeRule.setContent {
            FonderTheme { SettingsContent(state = SettingsUiState()) }
        }

        composeRule.onNodeWithText("ISK/KF").assertExists()
        composeRule.onNodeWithText("Depå/AF").assertExists()
    }

    @Test
    fun kontotypvaljaren_anropar_onAccountTypeSelected() {
        var selected: AccountType? = null
        composeRule.setContent {
            FonderTheme {
                SettingsContent(state = SettingsUiState(), onAccountTypeSelected = { selected = it })
            }
        }

        composeRule.onNodeWithText("ISK/KF").performClick()

        assertEquals(AccountType.ISK_KF, selected)
    }

    @Test
    fun tomningsmeddelandet_visas_ur_tillstandet_och_gar_att_kvittera() {
        // Regression (issue #78): meddelandet speglades lokalt via ett LaunchedEffect och
        // ViewModel:ens flagga nollställdes aldrig, så engångshändelsen spelades upp igen vid
        // varje rotation och varje återbesök. Nu läses den ur tillståndet och kvitteras.
        var dismissed = false
        composeRule.setContent {
            FonderTheme {
                SettingsContent(
                    state = SettingsUiState(databaseCleared = true),
                    onClearedMessageDismissed = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Databasen har tömts.").assertExists()
        // Farozonen ligger sist i en `verticalScroll`-kolumn (UI-5). Utan scroll är knappen
        // komponerad men klippt, och klicket landar utanför den — `assertExists` hade passerat
        // och `performClick` kastat inget, men callbacken uteblev.
        composeRule.onNodeWithText("Stäng").performScrollTo().performClick()
        assertTrue(dismissed)
    }

    @Test
    fun tomningsmeddelandet_visas_inte_utan_tomd_databas() {
        composeRule.setContent {
            FonderTheme {
                SettingsContent(state = SettingsUiState(databaseCleared = false))
            }
        }

        composeRule.onNodeWithText("Databasen har tömts.").assertDoesNotExist()
    }
}
