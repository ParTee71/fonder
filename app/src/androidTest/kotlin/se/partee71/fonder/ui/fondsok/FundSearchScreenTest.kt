package se.partee71.fonder.ui.fondsok

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.common.toListUiState

/**
 * Instrumenterat test av fondsökens träfflista (UI-10, issue #85) — bygger
 * [FundSearchUiState] direkt i stället för att gå via ViewModel/Hilt, samma mönster som
 * [se.partee71.fonder.ui.portfolj.PortfoljScreenTest]. Vaktar att risknivån syns på raden och
 * att en fond utan cachad metadata märks som okänd i stället för att lämnas omärkt.
 */
@RunWith(AndroidJUnit4::class)
class FundSearchScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fondA = Fund(fundId = "SHB0000442", name = "Handelsbanken Sverige Index")
    private val fondB = Fund(fundId = "SHB0000627", name = "Handelsbanken Global Index")

    @Test
    fun visar_risknivan_pa_traffraden() {
        composeRule.setContent {
            FonderTheme {
                FundSearchContent(
                    state = FundSearchUiState(
                        list = persistentListOf(fondA).toListUiState(),
                        riskLevels = persistentMapOf(fondA.fundId to 6)
                    )
                )
            }
        }

        composeRule.onNodeWithText("Handelsbanken Sverige Index").assertExists()
        composeRule.onNodeWithText("Risk 6/7").assertExists()
    }

    @Test
    fun visar_okand_risk_for_fond_som_saknas_i_metadatacachen() {
        composeRule.setContent {
            FonderTheme {
                FundSearchContent(
                    state = FundSearchUiState(
                        list = persistentListOf(fondA, fondB).toListUiState(),
                        riskLevels = persistentMapOf(fondA.fundId to 6)
                    )
                )
            }
        }

        composeRule.onNodeWithText("Risk 6/7").assertExists()
        composeRule.onNodeWithText("Risk okänd").assertExists()
    }
}
