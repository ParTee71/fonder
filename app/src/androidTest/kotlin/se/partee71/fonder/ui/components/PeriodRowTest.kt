package se.partee71.fonder.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Isolerat Compose-test av den delade period-raden (issue #14, regel 4) — Hem och Portfölj
 * kan lita på att den redan är verifierad i stället för att var och en testar samma
 * renderingslogik igen.
 */
@RunWith(AndroidJUnit4::class)
class PeriodRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visar_belopp_och_procent_for_positiv_forandring() {
        composeRule.setContent {
            FonderTheme {
                PeriodRow(label = "Idag", amount = 120.0, fraction = 0.05)
            }
        }

        composeRule.onNodeWithText("Idag").assertExists()
        // "kr"-beloppets minustecken kan variera med JVM/lokal (se MoneyFormatTest) — matchar
        // därför bara den äkta minus-symbolen från percentSigned, plus beloppets siffror.
        composeRule.onNodeWithText("+5,0 % · ", substring = true).assertExists()
        composeRule.onNodeWithText("120,00 kr", substring = true).assertExists()
    }

    @Test
    fun visar_negativt_belopp_med_minustecken() {
        composeRule.setContent {
            FonderTheme {
                PeriodRow(label = "Senaste veckan", amount = -50.0, fraction = -0.02)
            }
        }

        composeRule.onNodeWithText("−2,0 % · ", substring = true).assertExists()
        composeRule.onNodeWithText("50,00 kr", substring = true).assertExists()
    }

    @Test
    fun visar_otillrackligt_data_nar_belopp_saknas() {
        composeRule.setContent {
            FonderTheme {
                PeriodRow(label = "Senaste månaden", amount = null, fraction = null)
            }
        }

        composeRule.onNodeWithText("Otillräcklig data").assertExists()
    }

    @Test
    fun visar_bara_procent_utan_kr_belopp_nar_amount_ar_null_men_fraction_finns() {
        // issue #16 (ANA-1): CAGR/portföljandel har inget kr-belopp, bara en procent.
        composeRule.setContent {
            FonderTheme {
                PeriodRow(label = "CAGR", amount = null, fraction = 0.082)
            }
        }

        composeRule.onNodeWithText("CAGR").assertExists()
        composeRule.onNodeWithText("+8,2 %").assertExists()
    }

    @Test
    fun visar_delvis_osaker_markering_nar_partial_ar_sant() {
        composeRule.setContent {
            FonderTheme {
                PeriodRow(label = "Senaste månaden", amount = 10.0, fraction = 0.01, partial = true)
            }
        }

        composeRule.onNodeWithText("Delvis osäker — någon fond saknar historik").assertExists()
    }
}
