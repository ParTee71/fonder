package se.partee71.fonder.ui.fondsok

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCompany
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.testing.contracts
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.common.ListUiState
import se.partee71.ui.testing.runListScreenContract

/**
 * Fondsök på listramen i JVM med Robolectric: ramens kontrakt (laddning, "Inga fonder hittades", fel med
 * fondsökens egen rubrik och "Försök igen", NFR-6, NAV-3), träffraderna med risknivå (UI-10) och knappen
 * med fondens namn (UI-3), och en skärmdump ljust + mörkt enligt mockupen. Påhittade fonder.
 */
@RunWith(RobolectricTestRunner::class)
class FundSearchContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val globalA = Fund(fundId = "1", name = "Globalfond A")
    private val hallbar = Fund(fundId = "2", name = "Global Hållbar C")
    private val tillvaxt = Fund(fundId = "3", name = "Globalfond Tillväxt")
    private val ranta = Fund(fundId = "4", name = "Global Räntefond")
    private val smabolag = Fund(fundId = "5", name = "Global Småbolag D")

    private val content = FundSearchUiState(
        list = ListUiState.Content(persistentListOf(globalA, hallbar, tillvaxt, ranta, smabolag)),
        query = "global",
        companies = persistentListOf(FundCompany(id = "SHB", name = "Handelsbanken")),
        addedFundIds = persistentSetOf(globalA.fundId),
        riskLevels = persistentMapOf(globalA.fundId to 4, hallbar.fundId to 4, tillvaxt.fundId to 5, ranta.fundId to 2)
    )

    @Test
    fun `Fondsök följer listramens kontrakt utan lägg till och med egen felrubrik`() {
        composeRule.runListScreenContract(
            environment = contracts,
            item = globalA,
            itemText = "Globalfond A",
            emptyTitle = "Inga fonder hittades",
            addLabel = null,
            errorTitle = "Kunde inte hämta fondlistan"
        ) { state, _, onRetry ->
            FundSearchContent(state = FundSearchUiState(list = state), onRetry = onRetry)
        }
    }

    @Test
    fun `träffraden visar risknivån och knappen läses med fondens namn`() {
        val added = mutableListOf<String>()
        composeRule.setContent { FonderTheme { FundSearchContent(state = content, onAddFund = { added += it.name }) } }

        composeRule.onNodeWithText("Risk 5/7").assertIsDisplayed()
        composeRule.onNodeWithText("Risk okänd").assertIsDisplayed()
        // En redan bevakad fond har en bock i stället för knappen.
        composeRule.onNodeWithContentDescription("Tillagd").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Lägg till Global Hållbar C").performClick()
        assertEquals(listOf("Global Hållbar C"), added)
    }

    @Test
    fun `fälten står kvar när sökningen inte ger några träffar`() {
        composeRule.setContent {
            FonderTheme { FundSearchContent(state = content.copy(list = ListUiState.Empty)) }
        }
        composeRule.onNodeWithText("Sök fond").assertIsDisplayed()
        composeRule.onNodeWithText("Inga fonder hittades").assertIsDisplayed()
    }

    @Test
    fun `skärmdump av Fondsök på listramen`() = composeRule.captureLightAndDark("FundSearch_content") {
        FundSearchContent(state = content)
    }
}
