package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.components.AppTextField

/**
 * android-ui:s [AppTextField] (UI-13) i Fonders tema i JVM: etiketten och felet visas, inskriven text
 * går till `onValueChange`, och ett fält med fel markeras som fel för skärmläsaren. Skärmdumpen visar
 * fältet i Fonders palett (fylld yta, `outlineVariant`-kontur).
 */
@RunWith(RobolectricTestRunner::class)
class AppTextFieldTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `inskriven text går till onValueChange`() {
        var typed = ""
        composeRule.setContent {
            FonderTheme { AppTextField(value = "", onValueChange = { typed = it }, label = "Antal andelar") }
        }

        composeRule.onNodeWithText("Antal andelar").assert(hasSetTextAction()).performTextInput("12,5")

        assertEquals("12,5", typed)
    }

    @Test
    fun `ett fält med fel visar felet och markeras som fel`() {
        composeRule.setContent {
            FonderTheme {
                AppTextField(
                    value = "abc",
                    onValueChange = {},
                    label = "Kurs",
                    error = "Ange ett tal"
                )
            }
        }

        composeRule.onNodeWithText("Ange ett tal").assertExists()
        composeRule.onNodeWithText("Kurs").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test
    fun `skärmdump med etikett, enhet och fel`() = composeRule.captureLightAndDark("AppTextField_variants") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppTextField(value = "30 000", onValueChange = {}, label = "Belopp", suffix = "kr", helper = "Courtage ingår")
            AppTextField(value = "25", onValueChange = {}, label = "Andel", suffix = "%", modifier = Modifier.width(100.dp))
            AppTextField(
                value = "12,x",
                onValueChange = {},
                label = "Antal andelar",
                error = "Ange ett tal"
            )
        }
    }
}
