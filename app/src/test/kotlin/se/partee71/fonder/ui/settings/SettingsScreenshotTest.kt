package se.partee71.fonder.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.testing.captureLightAndDark

/**
 * Skärmdump ljust + mörkt av Inställningar: korten överst (tema, riskprofil, kontotyp, jämförelse, facit).
 * Vaktar kortens kontur, rubriker och knappar (UI-13).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val state = SettingsUiState(accountType = AccountType.ISK_KF, chosenBenchmarkName = "Global Indexfond A", priceRefreshWorking = true)

    @Test
    fun `skärmdump av korten överst`() = composeRule.captureLightAndDark("SettingsScreen_top", animated = true) {
        SettingsContent(state = state)
    }
}
