package se.partee71.fonder.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * Den delade [AppFabMenu] (NAV-8, UI-14) i JVM: stängd visar den bara knappen ("Lägg till",
 * ihopfälld), ett tryck fäller ut valen och knappen blir "Stäng meny", och ett val stänger menyn
 * innan det utförs. Bakåt stänger en öppen meny – utom med `handleBack = false`, då anroparen äger
 * bakåt (navigeringsskalet).
 */
@RunWith(RobolectricTestRunner::class)
class AppFabMenuTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val chosen = mutableListOf<String>()

    private fun items() = listOf("Ny transaktion", "Importera avräkningsnota", "Importera innehav", "Bevaka ny fond")
        .map { label -> AppFabMenuItem(label, { chosen += label }) }

    /** Bakåt som nådde anroparen (en hanterare under menyn) i stället för menyn. */
    private var backsToCaller = 0

    private fun setMenu(handleBack: Boolean = true) = composeRule.setContent {
        // Anroparens egen hantering av bakåt – bara när menyn lämnar bakåt åt den.
        if (!handleBack) BackHandler { backsToCaller++ }
        FonderTheme {
            var expanded by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth().height(500.dp), contentAlignment = Alignment.BottomEnd) {
                AppFabMenu(expanded = expanded, onExpandedChange = { expanded = it }, items = items(), handleBack = handleBack)
            }
        }
    }

    private fun pressBack() {
        // Menyns bakåthanterare slås på i nästa komposition efter trycket – vänta in den först.
        composeRule.waitForIdle()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    }

    @Test
    fun `bakåt stänger en öppen meny`() {
        setMenu()
        composeRule.onNodeWithContentDescription("Lägg till").performClick()

        pressBack()

        composeRule.onNodeWithContentDescription("Lägg till").assertExists()
        composeRule.onNodeWithText("Ny transaktion").assertDoesNotExist()
    }

    @Test
    fun `utan handleBack lämnas bakåt åt anroparen`() {
        setMenu(handleBack = false)
        composeRule.onNodeWithContentDescription("Lägg till").performClick()

        pressBack()

        composeRule.onNodeWithContentDescription("Stäng meny").assertExists()
        assertEquals(1, backsToCaller)
    }

    @Test
    fun `stängd meny visar bara knappen`() {
        setMenu()

        composeRule.onNodeWithContentDescription("Lägg till")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ihopfälld"))
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Ny transaktion").assertDoesNotExist()
    }

    @Test
    fun `ett tryck fäller ut valen och ett val stänger menyn och utförs`() {
        setMenu()

        composeRule.onNodeWithContentDescription("Lägg till").performClick()
        composeRule.onNodeWithContentDescription("Stäng meny")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Utfälld"))
        composeRule.onNodeWithText("Bevaka ny fond").assertIsDisplayed()
        composeRule.onNodeWithText("Importera avräkningsnota").assertIsDisplayed().performClick()

        assertEquals(listOf("Importera avräkningsnota"), chosen)
        composeRule.onNodeWithContentDescription("Lägg till").assertExists()
        composeRule.onNodeWithText("Importera avräkningsnota").assertDoesNotExist()
    }

    @Test
    fun `skärmdump stängd och öppen`() = composeRule.captureLightAndDark("AppFabMenu_open") {
        Box(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.BottomEnd) {
            Box(Modifier.align(Alignment.BottomStart)) {
                AppFabMenu(expanded = false, onExpandedChange = {}, items = items())
            }
            AppFabMenu(expanded = true, onExpandedChange = {}, items = items())
        }
    }
}
