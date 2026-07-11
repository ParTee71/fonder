package se.partee71.fonder.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.ui.theme.FonderTheme
import java.time.LocalDate

/**
 * Isolerat Compose-test av den delade "Värde per <datum>"-raden (POR-7, issue #27, regel 4) —
 * Portfölj och Hem kan lita på att den redan är verifierad.
 */
@RunWith(AndroidJUnit4::class)
class ValueAsOfRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visar_formaterat_datum_nar_kant() {
        composeRule.setContent {
            FonderTheme {
                ValueAsOfRow(navEpochDay = LocalDate.of(2026, 7, 10).toEpochDay())
            }
        }

        composeRule.onNodeWithText("Värde per 2026-07-10").assertExists()
    }

    @Test
    fun visar_ingenting_utan_kant_datum() {
        composeRule.setContent {
            FonderTheme {
                ValueAsOfRow(navEpochDay = null)
            }
        }

        composeRule.onNodeWithText("Värde per", substring = true).assertDoesNotExist()
    }
}
