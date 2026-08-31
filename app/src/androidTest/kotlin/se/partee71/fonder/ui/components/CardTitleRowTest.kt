package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.ui.theme.FonderTheme

/** Instrumenterat test av kortens delade rubrikrad med väntesnurra (NAV-6). */
@RunWith(AndroidJUnit4::class)
class CardTitleRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rubriken_visas_utan_snurra_i_vila() {
        composeRule.setContent {
            FonderTheme {
                CardTitleRow(title = "Fondavgifter per år", working = false)
            }
        }

        composeRule.onNodeWithText("Fondavgifter per år").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Uppdaterar Fondavgifter per år").assertDoesNotExist()
    }

    @Test
    fun snurran_visas_nar_kortets_data_bearbetas() {
        composeRule.setContent {
            FonderTheme {
                CardTitleRow(title = "Fondavgifter per år", working = true)
            }
        }

        // Rubriken finns kvar: snurran ersätter aldrig innehållet, den kompletterar det.
        composeRule.onNodeWithText("Fondavgifter per år").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Uppdaterar Fondavgifter per år").assertIsDisplayed()
    }

    @Test
    fun snurrorna_gar_att_skilja_at_nar_flera_kort_arbetar() {
        // Beskrivningen formuleras ur rubriken just för det här: en gemensam text ("Uppdaterar")
        // hade inte sagt en skärmläsaranvändare vilket av korten som väntar på data.
        composeRule.setContent {
            FonderTheme {
                Column {
                    CardTitleRow(title = "Totalt värde", working = true)
                    CardTitleRow(title = "Riskprofil", working = true)
                }
            }
        }

        composeRule.onNodeWithContentDescription("Uppdaterar Totalt värde").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Uppdaterar Riskprofil").assertIsDisplayed()
    }
}
