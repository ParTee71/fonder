package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import se.partee71.ui.components.AppButton
import se.partee71.ui.components.ButtonVariant
import se.partee71.ui.components.ItemRow

/**
 * android-ui:s [ItemRow] som Fonder använder den (Fondsök, Händelser, Beslut) och Fonders [ProgressBar] i
 * JVM: raden är minst 56 dp, en knapp till höger behåller sin egen åtgärd, `overline` står ovanför titeln
 * och en klickbar rad med `navigates` anropar `onClick` (Besluts Facit-rad).
 */
@RunWith(RobolectricTestRunner::class)
class ItemRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `raden är minst 56 dp och knappen till höger har egen åtgärd`() {
        var added = 0
        composeRule.setContent {
            FonderTheme {
                ItemRow(
                    title = "Handelsbanken Sverige Index",
                    modifier = Modifier.testTag("rad"),
                    below = { RiskBadge(level = 6) },
                    trailing = { AppButton(text = "Lägg till", onClick = { added++ }, variant = ButtonVariant.Text) }
                )
            }
        }

        composeRule.onNodeWithTag("rad").assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithText("Risk 6/7", substring = true).assertExists()
        composeRule.onNodeWithText("Lägg till").assertHasClickAction().performClick()

        assertEquals(1, added)
    }

    @Test
    fun `en rad som leder vidare anropar onClick`() {
        var opened = 0
        composeRule.setContent {
            FonderTheme { ItemRow(title = "2 genomförda förslag", navigates = true, onClick = { opened++ }) }
        }

        composeRule.onNodeWithText("2 genomförda förslag").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `skärmdump av listrad och förloppsstapel`() = composeRule.captureLightAndDark("ItemRow_ProgressBar", animated = true) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ItemRow(
                title = "Global Indexfond A",
                below = { RiskBadge(level = 4) },
                trailing = { AppButton(text = "Lägg till", onClick = {}, variant = ButtonVariant.Text) }
            )
            ItemRow(
                title = "Tillväxtmarknad Bred → Global Indexfond A",
                subtitle = "Risk 6 · 0,60 %",
                overline = { Text("Plats 1 i planen", style = MaterialTheme.typography.labelSmall) }
            )
            ItemRow(title = "1 genomfört förslag", trailing = { Text("+1 240 kr") }, navigates = true, onClick = {})
            ProgressBar()
            ProgressBar(progress = { 0.6f })
        }
    }
}
