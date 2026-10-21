package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.Copper
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.fonder.ui.theme.Gain
import se.partee71.fonder.ui.theme.Neutral
import se.partee71.fonder.ui.theme.Tertiary
import se.partee71.ui.components.InfoPill
import se.partee71.ui.theme.AppTheme
import se.partee71.ui.theme.Tone

/**
 * Tillståndsmärkena (BYT-4) – android-ui:s [InfoPill] i 8 dp-formen med Fonders toner – i JVM: tillståndet
 * står i text och läses av skärmläsaren (UI-3); en förkortad text får en utskriven beskrivning – så läses
 * [RiskBadge] (UI-10).
 */
@RunWith(RobolectricTestRunner::class)
class InfoPillTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tillståndet står i text och läses som det står`() {
        composeRule.setContent { FonderTheme { InfoPill(text = "Sålt · dag 2 av 14", tone = Tone.Copper, shape = AppTheme.shapes.smallTile) } }

        composeRule.onNodeWithText("Sålt · dag 2 av 14").assertExists()
    }

    @Test
    fun `riskmärket är en neutral pill med utskriven beskrivning`() {
        composeRule.setContent { FonderTheme { RiskBadge(level = 5) } }

        composeRule.onNodeWithText("Risk 5/7", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Risknivå 5 av 7").assertContentDescriptionEquals("Risknivå 5 av 7")
    }

    @Test
    fun `skärmdump av märkena i alla toner`() = composeRule.captureLightAndDark("InfoPill_tones") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(text = "Föreslaget", tone = Tone.Primary, shape = AppTheme.shapes.smallTile)
                InfoPill(text = "Sålt · dag 2 av 14", tone = Tone.Copper, shape = AppTheme.shapes.smallTile)
                InfoPill(text = "Köpt 7 okt", tone = Tone.Gain, shape = AppTheme.shapes.smallTile)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(text = "Avgift", tone = Tone.Tertiary, shape = AppTheme.shapes.smallTile)
                InfoPill(text = "Avstått", tone = Tone.Neutral, shape = AppTheme.shapes.smallTile)
                InfoPill(text = "Utgånget", tone = Tone.Neutral, shape = AppTheme.shapes.smallTile)
            }
        }
    }
}
