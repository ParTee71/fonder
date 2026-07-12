package se.partee71.fonder.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Isolerat Compose-test av den delade vinstsignal-badgen (S4, ANA-8, issue #26, regel 4) —
 * Portfölj (och senare ev. Fonddetalj/Hem) kan lita på att den redan är verifierad.
 */
@RunWith(AndroidJUnit4::class)
class ProfitTakeBadgeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visar_etiketten_vinstlage() {
        composeRule.setContent {
            FonderTheme { ProfitTakeBadge(gainFraction = 0.62) }
        }

        composeRule.onNodeWithText("Vinstläge").assertExists()
    }

    @Test
    fun contentDescription_innehaller_vinstprocenten() {
        composeRule.setContent {
            FonderTheme { ProfitTakeBadge(gainFraction = 0.62) }
        }

        composeRule
            .onNodeWithContentDescription("Orealiserad vinst +62,0 % mot GAV")
            .assertExists()
    }
}
