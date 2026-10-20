package se.partee71.fonder.ui.fond

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

/** Fonddetaljens lägen som inte kräver en emulator — läsfelet (NFR-6). */
@RunWith(RobolectricTestRunner::class)
class FondDetaljContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `lasfel visar felet i stallet for en tom fond (NFR-6)`() {
        composeRule.setContent {
            FonderTheme { FondDetaljContent(state = FondDetaljUiState(fund = DetailUiState.Error(AppErrors.map(DataError.Database)))) }
        }
        composeRule.onNodeWithText("Kunde inte visa innehållet").assertExists()
        composeRule.onNodeWithText("Appens data gick inte att läsa. Lämna skärmen och försök igen.").assertExists()
        composeRule.onNodeWithText("Ingen kurshistorik", substring = true).assertDoesNotExist()
    }
}
