package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
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
 * Den delade [CheckRow] (UI-13) i JVM: hela raden – etikett och undertext – är **en** nod med
 * rollen kryssruta och minst 48 dp hög, och ett tryck på undertexten växlar värdet (UI-3).
 */
@RunWith(RobolectricTestRunner::class)
class CheckRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `raden är en kryssruta och ett tryck på undertexten växlar värdet`() {
        var checked: Boolean? = null
        composeRule.setContent {
            FonderTheme {
                CheckRow(
                    checked = false,
                    onCheckedChange = { checked = it },
                    label = "Global Indexfond A",
                    supportingText = "ISIN SE0000000001",
                )
            }
        }

        composeRule.onNodeWithText("Global Indexfond A")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertHeightIsAtLeast(48.dp)
            .assertIsOff()
        composeRule.onNodeWithText("ISIN SE0000000001").performClick()

        assertEquals(true, checked)
    }

    @Test
    fun `ett markerat värde läses som på`() {
        composeRule.setContent {
            FonderTheme { CheckRow(checked = true, onCheckedChange = {}, label = "Ta med") }
        }

        composeRule.onNodeWithText("Ta med").assertIsOn()
    }

    @Test
    fun `skärmdump med och utan undertext`() = composeRule.captureLightAndDark("CheckRow_default") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CheckRow(
                checked = true,
                onCheckedChange = {},
                label = "Global Indexfond A",
                supportingText = "ISIN SE0000000001 · avrakningsnota-0812.pdf",
                labelStyle = MaterialTheme.typography.titleSmall,
            )
            CheckRow(checked = false, onCheckedChange = {}, label = "Tillväxtmarknad Bred")
        }
    }
}
