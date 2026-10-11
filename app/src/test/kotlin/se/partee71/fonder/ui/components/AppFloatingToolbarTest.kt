package se.partee71.fonder.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * Den delade [AppFloatingToolbar] (NAV-7, UI-14) i JVM: varje flik är en `Role.Tab` med valt-läge
 * och sitt namn som beskrivning, minst 48 × 48 dp, ett tryck väljer fliken, och märket läses som
 * en del av fliken ("Beslut, 3 nya") men bara på en ovald flik.
 */
@RunWith(RobolectricTestRunner::class)
class AppFloatingToolbarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val tabs = listOf(
        AppToolbarTab("Översikt", Icons.Outlined.Home),
        AppToolbarTab("Innehav", Icons.Outlined.PieChart),
        AppToolbarTab("Beslut", Icons.Outlined.TaskAlt, badgeCount = 3),
        AppToolbarTab("Händelser", Icons.AutoMirrored.Outlined.List),
    )

    @Test
    fun `varje flik är en vald eller ovald flik med namn och 48 dp tryckyta`() {
        composeRule.setContent { FonderTheme { AppFloatingToolbar(tabs = tabs, selectedIndex = 0, onSelect = {}) } }

        composeRule.onNodeWithContentDescription("Översikt")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        listOf("Innehav", "Händelser").forEach { label ->
            composeRule.onNodeWithContentDescription(label)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assertIsNotSelected()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
    }

    @Test
    fun `ett tryck på en flik väljer den`() {
        val selections = mutableListOf<Int>()
        composeRule.setContent {
            FonderTheme {
                var selected by remember { mutableIntStateOf(0) }
                AppFloatingToolbar(
                    tabs = tabs,
                    selectedIndex = selected,
                    onSelect = {
                        selections += it
                        selected = it
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Händelser").performClick()

        assertEquals(listOf(3), selections)
        composeRule.onNodeWithContentDescription("Händelser").assertIsSelected()
        composeRule.onNodeWithContentDescription("Översikt").assertIsNotSelected()
    }

    @Test
    fun `märket läses med fliken och visas bara när fliken inte är vald`() {
        var selected by mutableIntStateOf(0)
        composeRule.setContent { FonderTheme { AppFloatingToolbar(tabs = tabs, selectedIndex = selected, onSelect = {}) } }

        composeRule.onNodeWithContentDescription("Beslut, 3 nya").assertIsNotSelected()

        selected = 2
        composeRule.onNodeWithContentDescription("Beslut").assertIsSelected()
        composeRule.onNodeWithContentDescription("Beslut, 3 nya").assertDoesNotExist()
    }

    @Test
    fun `skärmdump ljust och mörkt`() = composeRule.captureLightAndDark("AppFloatingToolbar_default") {
        AppFloatingToolbar(tabs = tabs, selectedIndex = 0, onSelect = {})
    }
}
