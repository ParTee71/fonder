package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * Den delade [AppButton] och [AppIconButton] (UI-13) i JVM: varje variant är en knapp för
 * skärmläsaren med 48 dp tryckyta, ett tryck anropar `onClick`, en avstängd knapp gör ingenting,
 * och ikonknappen läses med sin beskrivning (UI-3).
 */
@RunWith(RobolectricTestRunner::class)
class AppButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `varje variant är en knapp med 48 dp tryckyta som anropar onClick`() {
        val clicked = mutableListOf<AppButtonVariant>()
        composeRule.setContent {
            FonderTheme {
                Column {
                    AppButtonVariant.entries.forEach { variant ->
                        AppButton(text = variant.name, onClick = { clicked += variant }, variant = variant)
                    }
                }
            }
        }

        AppButtonVariant.entries.forEach { variant ->
            composeRule.onNodeWithText(variant.name)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertTouchHeightIsEqualTo(48.dp)
                .performClick()
        }

        assertEquals(AppButtonVariant.entries, clicked)
    }

    @Test
    fun `en avstängd knapp är inte aktiverad och anropar inte onClick`() {
        var clicks = 0
        composeRule.setContent {
            FonderTheme { AppButton(text = "Spara", onClick = { clicks++ }, enabled = false) }
        }

        composeRule.onNodeWithText("Spara").assertIsNotEnabled().performClick()

        assertEquals(0, clicks)
    }

    @Test
    fun `ikonknappen läses med sin beskrivning och anropar onClick`() {
        var clicks = 0
        composeRule.setContent {
            FonderTheme { AppIconButton(icon = Icons.Filled.Close, contentDescription = "Ta bort tillfälle", onClick = { clicks++ }) }
        }

        composeRule.onNodeWithContentDescription("Ta bort tillfälle")
            .assertTouchHeightIsEqualTo(48.dp)
            .performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `skärmdump av varianterna`() = composeRule.captureLightAndDark("AppButton_variants") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(text = "Spara", onClick = {})
                AppButton(text = "Följ", onClick = {}, variant = AppButtonVariant.Tonal)
                AppButton(text = "Avbryt", onClick = {}, variant = AppButtonVariant.Outlined)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(text = "Avstå", onClick = {}, variant = AppButtonVariant.Text)
                AppButton(text = "Ta bort", onClick = {}, variant = AppButtonVariant.Danger)
                AppIconButton(icon = Icons.Filled.Close, contentDescription = "Stäng", onClick = {})
            }
            AppButton(text = "Importera 12 poster", onClick = {}, enabled = false)
        }
    }
}
