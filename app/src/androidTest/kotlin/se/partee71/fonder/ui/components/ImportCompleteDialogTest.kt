package se.partee71.fonder.ui.components

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
 * Isolerat Compose-test av den delade "import klar"-dialogen (issue #19, regel 4) — Excel-
 * och PDF-importflödena kan lita på att den redan är verifierad i stället för att var och en
 * testar samma stäng-/navigeringslogik igen (samma princip som [PeriodRowTest]).
 */
@RunWith(AndroidJUnit4::class)
class ImportCompleteDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visar_titel_och_antal_importerade_poster() {
        composeRule.setContent {
            FonderTheme { ImportCompleteDialog(importedCount = 3, onDismiss = {}) }
        }

        composeRule.onNodeWithText("Import klar").assertExists()
        composeRule.onNodeWithText("3 poster har importerats som transaktioner.").assertExists()
    }

    @Test
    fun stang_knapp_anropar_onDismiss() {
        var dismissed = false
        composeRule.setContent {
            FonderTheme { ImportCompleteDialog(importedCount = 1, onDismiss = { dismissed = true }) }
        }

        composeRule.onNodeWithText("Stäng").performClick()
        assertTrue(dismissed)
    }
}
