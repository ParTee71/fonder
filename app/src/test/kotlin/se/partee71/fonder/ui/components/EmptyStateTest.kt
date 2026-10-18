package se.partee71.fonder.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Den delade [EmptyState] i JVM med Robolectric – första Compose-testet utanför emulatorn
 * (skill testing-strategy). Rubrik och brödtext ska båda synas; skärmdumparna spelas in med
 * `recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
class EmptyStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `visar rubrik och brödtext`() {
        composeRule.setContent {
            FonderTheme {
                EmptyState(
                    title = "Ingen portfölj ännu",
                    body = "Lägg till din första fondtransaktion för att se en översikt här."
                )
            }
        }

        composeRule.onNodeWithText("Ingen portfölj ännu").assertExists()
        composeRule.onNodeWithText("Lägg till din första fondtransaktion för att se en översikt här.").assertExists()
    }

    @Test
    fun `skärmdump ljust och mörkt`() {
        composeRule.captureLightAndDark("EmptyState_default") {
            EmptyState(
                title = "Inga innehav ännu",
                body = "Lägg till din första fondtransaktion för att se värde och utveckling."
            )
        }
    }
}
