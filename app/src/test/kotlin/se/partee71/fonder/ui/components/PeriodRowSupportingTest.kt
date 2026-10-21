package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * [PeriodRow]s dämpade stödrader (facit, SET-5/issue #197) i JVM med Robolectric: en rad under
 * etiketten och en under värdet, för en träffandel, en procent och ett färdigformaterat värde. Utan
 * parametrarna ritas raden som förut — det vaktar skärmdumparna i [ComponentScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
class PeriodRowSupportingTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `stodrader under etiketten och under vardet visas`() {
        composeRule.setContent {
            FonderTheme {
                Column {
                    PeriodRow(
                        label = "Alla förslag",
                        amount = null,
                        fraction = null,
                        valueText = "3 av 5 bättre",
                        valueTone = 1.0,
                        supportingLabel = "5 utvärderade av 6",
                        supportingValue = "+1 240,00 kr"
                    )
                    PeriodRow(label = "1–6 månader · 2 st", amount = null, fraction = 0.012, supportingValue = "median · 2 av 2 bättre")
                    PeriodRow(label = "Meravkastning", amount = 120.0, fraction = 0.03, stackValue = true, supportingLabel = "mätt till 2025-12-29 · 89 dagar")
                }
            }
        }

        composeRule.onNodeWithText("Alla förslag").assertExists()
        composeRule.onNodeWithText("5 utvärderade av 6").assertExists()
        composeRule.onNodeWithText("3 av 5 bättre").assertExists()
        composeRule.onNodeWithText("+1 240,00 kr").assertExists()
        composeRule.onNodeWithText("+1,2 %").assertExists()
        composeRule.onNodeWithText("median · 2 av 2 bättre").assertExists()
        composeRule.onNodeWithText("mätt till 2025-12-29 · 89 dagar").assertExists()
        composeRule.onNodeWithText("+3,0 %").assertExists()
    }

    @Test
    fun `stodraden under etiketten star kvar nar vardet saknas`() {
        // Ett ej utvärderat förslag: värdet ersätts av texten, förklaringen står under etiketten.
        composeRule.setContent {
            FonderTheme {
                PeriodRow(
                    label = "Meravkastning",
                    amount = null,
                    fraction = null,
                    insufficientText = "Ej utvärderad ännu",
                    supportingLabel = "väntar på kurs samma dag för båda fonderna"
                )
            }
        }

        composeRule.onNodeWithText("Ej utvärderad ännu").assertExists()
        composeRule.onNodeWithText("väntar på kurs samma dag för båda fonderna").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Otillräcklig data").fetchSemanticsNodes().size)
    }

    @Test
    fun `stodraden under vardet star kvar nar vardet saknas`() {
        // supportingValue tappades förut i grenen för otillräcklig data.
        composeRule.setContent {
            FonderTheme {
                PeriodRow(
                    label = "Alla förslag",
                    amount = null,
                    fraction = null,
                    insufficientText = "Ej utvärderad ännu",
                    supportingValue = "+120,00 kr"
                )
            }
        }

        composeRule.onNodeWithText("Ej utvärderad ännu").assertExists()
        composeRule.onNodeWithText("+120,00 kr").assertExists()
    }
}
