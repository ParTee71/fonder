package se.partee71.fonder.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.ui.theme.FonderTheme

/** Instrumenterat test av den delade bakgrundsindikatorn (NAV-6, issue #27). */
@RunWith(AndroidJUnit4::class)
class WorkerStatusIconTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visas_nar_isRunning_ar_sant() {
        composeRule.setContent {
            FonderTheme {
                WorkerStatusIcon(isRunning = true)
            }
        }

        composeRule.onNodeWithContentDescription("Uppdaterar kurser i bakgrunden").assertExists()
    }

    @Test
    fun visas_inte_nar_isRunning_ar_falskt() {
        composeRule.setContent {
            FonderTheme {
                WorkerStatusIcon(isRunning = false)
            }
        }

        composeRule.onNodeWithContentDescription("Uppdaterar kurser i bakgrunden").assertDoesNotExist()
    }
}
