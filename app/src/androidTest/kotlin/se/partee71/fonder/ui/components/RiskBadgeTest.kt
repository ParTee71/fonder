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
 * Instrumenterat test av den delade [RiskBadge] (UI-10, issue #85) — samma mönster som
 * [ProfitTakeBadgeTest]. Vaktar att risknivån alltid står i **text** (aldrig bara som färg,
 * UI-3) och att en okänd nivå skrivs ut i stället för att märkningen tyst uteblir (ANA-4).
 */
@RunWith(AndroidJUnit4::class)
class RiskBadgeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visar_nivan_och_skalan_i_text() {
        composeRule.setContent { FonderTheme { RiskBadge(level = 5) } }

        composeRule.onNodeWithText("Risk 5/7").assertExists()
        composeRule.onNodeWithContentDescription("Risknivå 5 av 7").assertExists()
    }

    @Test
    fun visar_okand_risk_i_stallet_for_ingenting() {
        composeRule.setContent { FonderTheme { RiskBadge(level = null) } }

        composeRule.onNodeWithText("Risk okänd").assertExists()
        composeRule.onNodeWithContentDescription("Risknivå okänd").assertExists()
    }

    @Test
    fun visar_forandringen_nar_ett_byte_foreslas() {
        composeRule.setContent { FonderTheme { RiskBadge(level = 6, toLevel = 3) } }

        composeRule.onNodeWithText("Risk 6 → 3 (av 7)").assertExists()
        composeRule.onNodeWithContentDescription("Risknivå 6 av 7, efter bytet 3 av 7").assertExists()
    }
}
