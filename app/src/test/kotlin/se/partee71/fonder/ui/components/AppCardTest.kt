package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
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
 * Det delade [AppCard] (UI-13) i JVM: ett kort med `onClick` är **en** klickbar nod där rubrik och
 * belopp läses i ett svep, ett kort utan har ingen egen åtgärd – och de tre varianterna fotograferas
 * i ljust och mörkt.
 */
@RunWith(RobolectricTestRunner::class)
class AppCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `ett klickbart kort är en nod och ett tryck på innehållet anropar onClick`() {
        var clicks = 0
        composeRule.setContent {
            FonderTheme {
                AppCard(onClick = { clicks++ }) {
                    Text("Global Indexfond A")
                    Text("214 300 kr")
                }
            }
        }

        composeRule.onNodeWithText("Global Indexfond A", substring = true).assertHasClickAction()
        composeRule.onNodeWithText("214 300 kr", substring = true).performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `ett kort utan onClick har ingen egen åtgärd`() {
        composeRule.setContent {
            FonderTheme { AppCard { Text("Portföljens värde") } }
        }

        composeRule.onNodeWithText("Portföljens värde")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `skärmdump av varianterna`() = composeRule.captureLightAndDark("AppCard_variants") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text("Portföljens värde", style = MaterialTheme.typography.bodySmall)
                Text("412 860 kr", style = MaterialTheme.typography.headlineMedium)
            }
            AppCard(modifier = Modifier.fillMaxWidth(), variant = AppCardVariant.Filled, onClick = {}) {
                Text("Tillväxtmarknad Bred", style = MaterialTheme.typography.titleSmall)
                Text("Avgift 372 kr per år", style = MaterialTheme.typography.bodySmall)
            }
            AppCard(modifier = Modifier.fillMaxWidth(), variant = AppCardVariant.Error) {
                Text("Farozon", style = MaterialTheme.typography.titleSmall)
                Text("Det här går inte att ångra.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
