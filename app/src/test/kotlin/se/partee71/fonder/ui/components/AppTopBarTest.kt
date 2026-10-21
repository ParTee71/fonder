package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
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
import se.partee71.ui.components.AppIconButton
import se.partee71.ui.components.IconButtonVariant
import se.partee71.ui.theme.Tone

/**
 * Den delade [AppTopBar] och [AppTabHeading] (UI-8, NAV-7, UI-14) i JVM: toppraden har rubrik och
 * en bakåtpil som läses "Tillbaka", fliken en rubrik som skärmläsaren läser som rubrik och sina
 * åtgärder till höger.
 */
@RunWith(RobolectricTestRunner::class)
class AppTopBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `toppraden visar rubriken och bakåtpilen går tillbaka`() {
        var backs = 0
        composeRule.setContent { FonderTheme { AppTopBar(title = "Pågående byte", onBack = { backs++ }) } }

        composeRule.onNodeWithText("Pågående byte").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Tillbaka").assertTouchHeightIsEqualTo(48.dp).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `flikens rubrik läses som rubrik och har sina åtgärder`() {
        var clicks = 0
        composeRule.setContent {
            FonderTheme {
                AppTabHeading(title = "Översikt") {
                    AppIconButton(
                        icon = R.drawable.ic_settings,
                        contentDescription = "Inställningar",
                        onClick = { clicks++ },
                        variant = IconButtonVariant.Tonal,
                        tone = Tone.NeutralPrimary
                    )
                }
            }
        }

        composeRule.onNodeWithText("Översikt").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithContentDescription("Inställningar").assertTouchHeightIsEqualTo(48.dp).performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `skärmdump ljust och mörkt`() = composeRule.captureLightAndDark("AppTopBar_variants") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppTopBar(title = "Fonddetalj", onBack = {})
            AppTabHeading(title = "Översikt") {
                AppIconButton(
                    icon = R.drawable.ic_settings,
                    contentDescription = "Inställningar",
                    onClick = {},
                    variant = IconButtonVariant.Tonal,
                    tone = Tone.NeutralPrimary
                )
            }
        }
    }
}
