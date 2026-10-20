package se.partee71.fonder.ui.bytesfonster

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.data.common.AppErrors
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.common.DetailUiState

/** Pågående bytes lägen som inte kräver en emulator — "finns inte" och läsfelet (NFR-6). */
@RunWith(RobolectricTestRunner::class)
class SwitchWatchContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `en bevakning som inte finns behaller sin egen text`() {
        composeRule.setContent {
            FonderTheme { SwitchWatchContent(state = SwitchWatchUiState(watch = DetailUiState.Error(AppErrors.notFound()))) }
        }
        composeRule.onNodeWithText("Bevakningen finns inte").assertExists()
        composeRule.onNodeWithText("Det finns inte längre.").assertDoesNotExist()
    }

    @Test
    fun `lasfel visar felet i stallet for en tom bevakning (NFR-6)`() {
        composeRule.setContent {
            FonderTheme { SwitchWatchContent(state = SwitchWatchUiState(watch = DetailUiState.Error(AppErrors.map(DataError.Database)))) }
        }
        composeRule.onNodeWithText("Kunde inte visa innehållet").assertExists()
        composeRule.onNodeWithText("Appens data gick inte att läsa. Lämna skärmen och försök igen.").assertExists()
        composeRule.onNodeWithText("Bevakningen finns inte").assertDoesNotExist()
    }
}
