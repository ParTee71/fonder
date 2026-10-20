package se.partee71.fonder.ui.riskprofil

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.data.common.AppErrors
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.ui.theme.FonderTheme

/** Riskprofilens läs- och sparfel (NFR-6) och Spara under läsning och sparning. */
@RunWith(RobolectricTestRunner::class)
class RiskProfilContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val levels = persistentListOf(1, 2, 3, 4, 5, 6)

    @Test
    fun `en sparad profil som inte gick att lasa visas som fel i stallet for en tom enkat`() {
        composeRule.setContent {
            FonderTheme { RiskProfilContent(state = RiskProfilUiState(availableLevels = levels, loadError = AppErrors.map(DataError.Unknown))) }
        }
        composeRule.onNodeWithText("Kunde inte visa innehållet").assertExists()
        composeRule.onNodeWithText("Spara").assertDoesNotExist()
    }

    @Test
    fun `ett sparfel sags under Spara och formularet star kvar`() {
        composeRule.setContent {
            FonderTheme {
                RiskProfilContent(
                    state = RiskProfilUiState(
                        availableLevels = levels,
                        manualAllocationText = persistentMapOf(3 to "100"),
                        failure = AppErrors.map(DataError.Unknown)
                    )
                )
            }
        }
        composeRule.onNodeWithText("Något gick fel. Försök igen.").assertExists()
        composeRule.onNodeWithText("Spara").assertIsEnabled()
    }

    @Test
    fun `Spara ar slackt medan profilen sparas, utan att summan kallas fel`() {
        composeRule.setContent {
            FonderTheme {
                RiskProfilContent(state = RiskProfilUiState(availableLevels = levels, manualAllocationText = persistentMapOf(3 to "100"), saving = true))
            }
        }
        composeRule.onNodeWithText("Spara").assertIsNotEnabled()
        composeRule.onNodeWithText("Summan måste bli 100 % för att kunna sparas.").assertDoesNotExist()
    }
}
