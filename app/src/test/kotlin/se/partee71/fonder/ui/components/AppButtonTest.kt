package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import se.partee71.fonder.R
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.fonder.ui.theme.NeutralPrimary
import se.partee71.ui.components.AppButton
import se.partee71.ui.components.AppButtonStyle
import se.partee71.ui.components.AppIconButton
import se.partee71.ui.components.ButtonVariant
import se.partee71.ui.components.IconButtonVariant
import se.partee71.ui.components.LocalAppButtonStyle
import se.partee71.ui.components.R as UiR
import se.partee71.ui.theme.Tone

/**
 * android-ui:s [AppButton] och [AppIconButton] under [FonderTheme] (UI-13) i JVM: Fonders knappstil
 * (40 dp, formmorf, tonad = primärbehållaren) ges av temat, varje variant är en knapp för skärmläsaren
 * med 48 dp tryckyta, ett tryck anropar `onClick`, en avstängd knapp gör ingenting, och ikonknappen
 * läses med sin beskrivning (UI-3).
 */
@RunWith(RobolectricTestRunner::class)
class AppButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `FonderTheme ger knapparna 40 dp, formmorf och den tonade i primärbehållaren`() {
        var style: AppButtonStyle? = null
        composeRule.setContent { FonderTheme { style = LocalAppButtonStyle.current } }

        assertEquals(40.dp, style?.minHeight)
        assertEquals(true, style?.morph)
        assertEquals(Tone.Primary, style?.secondaryTone)
    }

    @Test
    fun `varje variant är en knapp med 48 dp tryckyta som anropar onClick`() {
        val clicked = mutableListOf<ButtonVariant>()
        composeRule.setContent {
            FonderTheme {
                Column {
                    ButtonVariant.entries.forEach { variant ->
                        AppButton(text = variant.name, onClick = { clicked += variant }, variant = variant)
                    }
                }
            }
        }

        ButtonVariant.entries.forEach { variant ->
            composeRule.onNodeWithText(variant.name)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertTouchHeightIsEqualTo(48.dp)
                .performClick()
        }

        assertEquals(ButtonVariant.entries, clicked)
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
            FonderTheme { AppIconButton(icon = UiR.drawable.ic_close, contentDescription = "Ta bort tillfälle", onClick = { clicks++ }) }
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
                AppButton(text = "Följ", onClick = {}, variant = ButtonVariant.Secondary)
                AppButton(text = "Avbryt", onClick = {}, variant = ButtonVariant.Outlined)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(text = "Avstå", onClick = {}, variant = ButtonVariant.Text)
                AppButton(text = "Ta bort", onClick = {}, variant = ButtonVariant.Destructive)
                AppIconButton(icon = UiR.drawable.ic_close, contentDescription = "Stäng", onClick = {})
                AppIconButton(
                    icon = R.drawable.ic_settings,
                    contentDescription = "Inställningar",
                    onClick = {},
                    variant = IconButtonVariant.Tonal,
                    tone = Tone.NeutralPrimary
                )
            }
            AppButton(text = "Importera 12 poster", onClick = {}, enabled = false)
        }
    }
}
