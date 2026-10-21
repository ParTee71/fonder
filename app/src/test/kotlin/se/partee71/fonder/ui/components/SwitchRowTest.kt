package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
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
import se.partee71.ui.components.SwitchRow

/**
 * android-ui:s [SwitchRow] (ANA-13, issue #150) i Fonders tema i JVM: hela raden är **en** nod med
 * rollen strömbrytare, minst 48 dp hög, och ett tryck på etiketten växlar läget (UI-3); `enabled = false`
 * låser reglaget med läget synligt.
 */
@RunWith(RobolectricTestRunner::class)
class SwitchRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `raden är en strömbrytare och ett tryck på etiketten växlar läget`() {
        var checked: Boolean? = null
        composeRule.setContent {
            FonderTheme { SwitchRow(title = "Bara indexfonder", checked = false, onCheckedChange = { checked = it }) }
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
            FonderTheme { SwitchRow(title = "Bara indexfonder", checked = true, onCheckedChange = {}) }
        }

        composeRule.onNodeWithText("Bara indexfonder").assertIsOn()
    }

    @Test
    fun `ett låst reglage läses som inaktivt och växlar inte`() {
        var checked: Boolean? = null
        composeRule.setContent {
            FonderTheme { SwitchRow(title = "Bara indexfonder", checked = true, onCheckedChange = { checked = it }, enabled = false) }
        }

        composeRule.onNodeWithText("Bara indexfonder").assertIsNotEnabled().assertIsOn().performClick()

        assertEquals(null, checked)
    }

    @Test
    fun `skärmdump på och av`() = composeRule.captureLightAndDark("SwitchRow_default") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SwitchRow(title = "Bara indexfonder", checked = true, onCheckedChange = {})
            SwitchRow(
                title = "Bara indexfonder",
                checked = false,
                onCheckedChange = {},
                subtitle = "Begränsar appens förslag till indexfonder på nivån"
            )
            SwitchRow(title = "Låst reglage", checked = true, onCheckedChange = {}, enabled = false)
        }
    }
}
