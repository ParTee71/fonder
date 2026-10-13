package se.partee71.fonder.ui.components

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Den delade [StateChip] (BYT-4) i JVM: tillståndet står i text och läses av skärmläsaren (UI-3);
 * en förkortad text kan få en utskriven beskrivning — så läses [RiskBadge] (UI-10).
 */
@RunWith(RobolectricTestRunner::class)
class StateChipTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tillstandet star i text och lases som det star`() {
        composeRule.setContent { FonderTheme { StateChip(text = "Sålt · dag 2 av 14", tone = StateChipTone.Accent) } }

        composeRule.onNodeWithText("Sålt · dag 2 av 14").assertExists()
    }

    @Test
    fun `riskmarket ar ett neutralt chip med utskriven beskrivning`() {
        composeRule.setContent { FonderTheme { RiskBadge(level = 5) } }

        composeRule.onNodeWithText("Risk 5/7").assertExists()
        composeRule.onNodeWithContentDescription("Risknivå 5 av 7").assertContentDescriptionEquals("Risknivå 5 av 7")
    }
}
