package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Den delade [SwitchRow] (ANA-13, issue #150) i JVM: hela raden är **en** nod med rollen
 * strömbrytare, minst 48 dp hög, och ett tryck på etiketten växlar läget (UI-3).
 */
@RunWith(RobolectricTestRunner::class)
class SwitchRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `raden är en strömbrytare och ett tryck på etiketten växlar läget`() {
        var checked: Boolean? = null
        composeRule.setContent {
            FonderTheme { SwitchRow(checked = false, onCheckedChange = { checked = it }, label = "Bara indexfonder") }
        }

        composeRule.onNodeWithText("Bara indexfonder")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertHeightIsAtLeast(48.dp)
            .assertIsOff()
            .performClick()

        assertEquals(true, checked)
    }

    @Test
    fun `ett påslaget läge läses som på`() {
        composeRule.setContent {
            FonderTheme { SwitchRow(checked = true, onCheckedChange = {}, label = "Bara indexfonder") }
        }

        composeRule.onNodeWithText("Bara indexfonder").assertIsOn()
    }

    @Test
    fun `skärmdump på och av`() = composeRule.captureLightAndDark("SwitchRow_default") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SwitchRow(checked = true, onCheckedChange = {}, label = "Bara indexfonder")
            SwitchRow(
                checked = false,
                onCheckedChange = {},
                label = "Bara indexfonder",
                supportingText = "Begränsar appens förslag till indexfonder på nivån"
            )
        }
    }
}
