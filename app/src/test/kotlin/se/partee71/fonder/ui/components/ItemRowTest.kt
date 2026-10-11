package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
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
 * Den delade [ItemRow] och [ProgressBar] (UI-13) i JVM: rubrik och undertext läses som en nod,
 * raden är minst 56 dp, och en knapp till höger behåller sin egen åtgärd.
 */
@RunWith(RobolectricTestRunner::class)
class ItemRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `rubrik och undertext är en nod och knappen till höger har egen åtgärd`() {
        var added = 0
        composeRule.setContent {
            FonderTheme {
                ItemRow(
                    headline = "Handelsbanken Sverige Index",
                    supporting = { RiskBadge(level = 6) },
                    trailing = { AppButton(text = "Lägg till", onClick = { added++ }, variant = AppButtonVariant.Text) },
                )
            }
        }

        composeRule.onNodeWithText("Handelsbanken Sverige Index").assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithText("Risk 6/7", substring = true).assertExists()
        composeRule.onNodeWithText("Lägg till").assertHasClickAction().performClick()

        assertEquals(1, added)
    }

    @Test
    fun `skärmdump av listrad och förloppsstapel`() =
        composeRule.captureLightAndDark("ItemRow_ProgressBar", animated = true) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ItemRow(
                    headline = "Global Indexfond A",
                    supporting = { RiskBadge(level = 4) },
                    trailing = { AppButton(text = "Lägg till", onClick = {}, variant = AppButtonVariant.Text) },
                )
                ItemRow(headline = "Tillväxtmarknad Bred", supporting = { Text("Risk 6 · 0,60 %") })
                ProgressBar()
                ProgressBar(progress = { 0.6f })
            }
        }
}
