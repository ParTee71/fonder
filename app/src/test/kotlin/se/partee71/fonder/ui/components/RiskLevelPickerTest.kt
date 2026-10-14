package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Den delade [RiskLevelPicker] (ANA-13, issue #150) i JVM: sju nivåer som radioval, den valda
 * läses som vald, den markerade säger vad ramen betyder, och ett tryck ger nivån.
 */
@RunWith(RobolectricTestRunner::class)
class RiskLevelPickerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `sju nivåer, den valda läses som vald radioknapp`() {
        composeRule.setContent { FonderTheme { RiskLevelPicker(selected = 4, onSelect = {}) } }

        composeRule.onAllNodesWithContentDescription("Risknivå", substring = true).assertCountEquals(7)
        composeRule.onNodeWithContentDescription("Risknivå 4 av 7")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeRule.onNodeWithContentDescription("Risknivå 3 av 7").assertIsNotSelected()
    }

    @Test
    fun `den markerade nivån säger vad ramen betyder`() {
        composeRule.setContent {
            FonderTheme { RiskLevelPicker(selected = 4, onSelect = {}, marked = 6, markedDescription = "säljfondens nivå") }
        }

        composeRule.onNodeWithContentDescription("Risknivå 6 av 7, säljfondens nivå").assertIsNotSelected()
    }

    @Test
    fun `ett tryck ger nivån`() {
        var chosen: Int? = null
        composeRule.setContent { FonderTheme { RiskLevelPicker(selected = null, onSelect = { chosen = it }) } }

        composeRule.onNodeWithContentDescription("Risknivå 2 av 7").performClick()

        assertEquals(2, chosen)
    }

    @Test
    fun `låst väljare går inte att trycka på`() {
        var chosen: Int? = null
        composeRule.setContent { FonderTheme { RiskLevelPicker(selected = 3, onSelect = { chosen = it }, enabled = false) } }

        composeRule.onNodeWithContentDescription("Risknivå 5 av 7").assertIsNotEnabled().performClick()

        assertNull(chosen)
    }

    @Test
    fun `varje nivå har en tryckyta på 48 dp`() {
        composeRule.setContent { FonderTheme { RiskLevelPicker(selected = 4, onSelect = {}) } }

        composeRule.onNodeWithContentDescription("Risknivå 1 av 7").assertTouchHeightIsEqualTo(48.dp)
    }

    @Test
    fun `skärmdump vald, markerad och båda`() = composeRule.captureLightAndDark("RiskLevelPicker_default") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RiskLevelPicker(selected = 4, onSelect = {}, marked = 6, markedDescription = "säljfondens nivå")
            RiskLevelPicker(selected = 6, onSelect = {}, marked = 6, markedDescription = "säljfondens nivå")
            RiskLevelPicker(selected = null, onSelect = {})
        }
    }
}
