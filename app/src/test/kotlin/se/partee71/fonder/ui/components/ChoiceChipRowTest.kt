package se.partee71.fonder.ui.components

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Den delade [ChoiceChipRow] som sammanhängande knappgrupp (UI-12) i JVM: samma beteende som
 * chipraden hade – ett valt alternativ, klick ger [onSelect], radioval för skärmläsaren – och
 * alternativ utanför skärmen nås genom att raden skrollar (UI-5).
 */
@RunWith(RobolectricTestRunner::class)
class ChoiceChipRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val options = listOf(
        "Mindre än ett år",
        "Ett till tre år",
        "Tre till tio år",
        "Mer än tio år",
        "Vet inte än",
        "Ingen plan"
    )

    @Test
    fun `det valda alternativet är markerat och läses som radioval`() {
        composeRule.setContent {
            FonderTheme { ChoiceChipRow(options, selected = "Ett till tre år", optionLabel = { it }, onSelect = {}) }
        }

        composeRule.onNodeWithText("Ett till tre år")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeRule.onNodeWithText("Mindre än ett år").assertIsNotSelected()
    }

    @Test
    fun `ett alternativ utanför skärmen nås genom att skrolla och väljs med ett tryck`() {
        var chosen: String? = null
        composeRule.setContent {
            FonderTheme { ChoiceChipRow(options, selected = null, optionLabel = { it }, onSelect = { chosen = it }) }
        }

        composeRule.onNodeWithText("Ingen plan").performScrollTo().performClick()

        assertEquals("Ingen plan", chosen)
    }

    @Test
    fun `varje knapp har en tryckyta på 48 dp trots 40 dp synlig höjd`() {
        composeRule.setContent {
            FonderTheme { ChoiceChipRow(options, selected = "Ett till tre år", optionLabel = { it }, onSelect = {}) }
        }

        // ToggleButtons Surface lägger minimumInteractiveComponentSize på tryckytan (accessibility-compose).
        composeRule.onNodeWithText("Mindre än ett år").assertTouchHeightIsEqualTo(48.dp)
    }
}
