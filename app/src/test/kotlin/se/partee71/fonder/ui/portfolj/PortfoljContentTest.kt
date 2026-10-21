package se.partee71.fonder.ui.portfolj

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.data.common.AppErrors
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.common.ListUiState
import se.partee71.ui.common.toListUiState

/**
 * Innehav utan innehav och med läsfel i JVM med Robolectric: det tomma lägets "Ny transaktion" och
 * "Importera avräkningsnota" (POR-10), läsfelets "Försök igen" (NFR-6) och skärmdumpar ljust + mörkt
 * enligt mockupen. Innehaven själva testas instrumenterat i `androidTest/…/ui/portfolj/PortfoljScreenTest`.
 */
@RunWith(RobolectricTestRunner::class)
class PortfoljContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val empty = PortfoljUiState(list = ListUiState.Empty)
    private val failed = PortfoljUiState(list = ListUiState.Error(AppErrors.map(DataError.Database)))

    @Test
    fun `tomt läge öppnar formuläret och avräkningsnotan (POR-10)`() {
        var opened = ""
        composeRule.setContent {
            FonderTheme {
                PortfoljContent(
                    state = empty,
                    onFundClick = {},
                    onNewTransaction = { opened += "ny;" },
                    onImportOrders = { opened += "import;" }
                )
            }
        }
        composeRule.onNodeWithText("Inga innehav ännu").assertIsDisplayed()
        composeRule.onNodeWithText("Ny transaktion").performScrollTo().performClick()
        composeRule.onNodeWithText("Importera avräkningsnota").performScrollTo().performClick()
        assertEquals("ny;import;", opened)
    }

    @Test
    fun `läsfelets Försök igen läser om innehaven (NFR-6)`() {
        var retries = 0
        composeRule.setContent { FonderTheme { PortfoljContent(state = failed, onFundClick = {}, onRetry = { retries++ }) } }
        composeRule.onNodeWithText("Kunde inte visa innehållet").assertIsDisplayed()
        composeRule.onNodeWithText("Försök igen").performScrollTo().performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `skärmdump av Innehav utan innehav`() = composeRule.captureLightAndDark("PortfoljScreen_tom") {
        PortfoljContent(state = empty, onFundClick = {})
    }

    @Test
    fun `skärmdump av Innehav med innehav`() = composeRule.captureLightAndDark("PortfoljScreen_innehav") {
        PortfoljContent(
            state = PortfoljUiState(
                list = persistentListOf(
                    Holding(fund = Fund(fundId = "SE_GLOBAL", name = "Global Indexfond A"), netShares = 120.0, netInvested = 30_000.0, currentValue = 34_250.0),
                    Holding(fund = Fund(fundId = "SE_SMB", name = "Sverige Småbolag"), netShares = 40.0, netInvested = 12_000.0, currentValue = 11_380.0)
                ).toListUiState()
            ),
            onFundClick = {}
        )
    }

    @Test
    fun `skärmdump av Innehav med läsfel`() = composeRule.captureLightAndDark("PortfoljScreen_fel") {
        PortfoljContent(state = failed, onFundClick = {})
    }
}
