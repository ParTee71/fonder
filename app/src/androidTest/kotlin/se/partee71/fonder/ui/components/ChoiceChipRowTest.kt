package se.partee71.fonder.ui.components

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Isolerat Compose-test av den delade val-chipraden (issue #68, regel 4) — extraherad ur
 * Inställningars tidigare privata `ThemeChip`. Riskprofilens enkät och temaväljaren kan lita
 * på att den redan är verifierad.
 */
@RunWith(AndroidJUnit4::class)
class ChoiceChipRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visar_alla_alternativ_och_markerar_det_valda() {
        composeRule.setContent {
            FonderTheme {
                ChoiceChipRow(
                    options = listOf("A", "B", "C"),
                    selected = "B",
                    optionLabel = { it },
                    onSelect = {},
                )
            }
        }

        composeRule.onNodeWithText("A").assertIsNotSelected()
        composeRule.onNodeWithText("B").assertIsSelected()
        composeRule.onNodeWithText("C").assertIsNotSelected()
    }

    @Test
    fun klick_pa_ett_alternativ_anropar_onSelect_med_det_alternativet() {
        var selected: String? = null
        composeRule.setContent {
            FonderTheme {
                ChoiceChipRow(
                    options = listOf("A", "B", "C"),
                    selected = null,
                    optionLabel = { it },
                    onSelect = { selected = it },
                )
            }
        }

        composeRule.onNodeWithText("C").performClick()

        assert(selected == "C")
    }

    @Test
    fun inget_alternativ_markerat_nar_selected_ar_null() {
        composeRule.setContent {
            FonderTheme {
                ChoiceChipRow(
                    options = listOf(1, 2, 3),
                    selected = null,
                    optionLabel = { it.toString() },
                    onSelect = {},
                )
            }
        }

        composeRule.onNodeWithText("1").assertIsNotSelected()
        composeRule.onNodeWithText("2").assertIsNotSelected()
        composeRule.onNodeWithText("3").assertIsNotSelected()
    }
}
