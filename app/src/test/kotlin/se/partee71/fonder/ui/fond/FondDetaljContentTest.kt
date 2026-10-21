package se.partee71.fonder.ui.fond

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import java.time.LocalDate
import kotlin.math.sin
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.partee71.fonder.data.common.AppErrors
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.domain.usecase.TaxCalc
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.testing.contracts
import se.partee71.fonder.testing.isDrawn
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.common.DetailUiState
import se.partee71.ui.components.ENTITY_REFRESH_TAG
import se.partee71.ui.testing.runDetailScreenContract

/**
 * Fonddetalj på detaljramen i JVM med Robolectric: ramens kontrakt (laddning, läsfel med "Försök igen",
 * "finns inte längre" utan knapp, innehåll med tillbakapil, NFR-6), fondens namn i toppraden (NAV-2),
 * dra ned för att uppdatera (UI-11) och skärmdumpar ljust + mörkt av innehåll, laddning och fel enligt
 * mockupen. Påhittade fonder och kurser.
 */
@RunWith(RobolectricTestRunner::class)
class FondDetaljContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val end = LocalDate.of(2026, 10, 9).toEpochDay()

    /** Tre månaders påhittade kurser kring 300 kr med en svag uppåttrend, senaste först (som ViewModeln levererar). */
    private val prices = (0L until 90L).map { day ->
        FundPrice(fundId = FUND_ID, epochDay = end - 89 + day, nav = 300.0 + 0.4 * day + 4 * sin(day / 3.0), currency = "SEK")
    }.sortedByDescending { it.epochDay }.toImmutableList()

    private val fund = FundDetail(
        fundName = "Global Indexfond A",
        isin = "SE0000000001",
        prices = prices,
        firstPurchaseEpochDay = end - 60,
        netInvested = 25_000.0,
        purchaseEpochDays = persistentListOf(end - 60)
    )

    private val suggestion = SwitchPlanResolver.Suggestion(
        planIndex = 0,
        sellIsin = "SE0000000001",
        sellFundName = "Global Indexfond A",
        buyIsin = "SE0000000002",
        buyFundName = "Sverige Småbolag B",
        fromLevel = 4,
        toLevel = 5,
        switchValueKr = 10_000.0,
        feeDeltaPercent = -0.1,
        recordId = 1L
    )

    private val content = FondDetaljUiState(
        fund = DetailUiState.Content(fund),
        riskLevel = 4,
        switchPlan = persistentListOf(suggestion),
        feeComparison = FeeComparisonUiState.NoCheaperAlternative
    )

    @Test
    fun `Fonddetalj följer detaljramens kontrakt utan Redigera`() {
        composeRule.runDetailScreenContract(
            environment = contracts,
            value = fund,
            valueText = "Global Indexfond A",
            editable = false
        ) { state, onRetry, _, onBack ->
            FondDetaljContent(state = FondDetaljUiState(fund = state), onBack = onBack, onRetry = onRetry)
        }
    }

    @Test
    fun `toppraden visar Fond medan fonden laddas och sedan fondens namn (NAV-2)`() {
        var state by mutableStateOf(FondDetaljUiState())
        composeRule.setContent { FonderTheme { FondDetaljContent(state = state) } }
        composeRule.onNodeWithText("Fond").assertIsDisplayed()

        state = content
        composeRule.onNodeWithText("Global Indexfond A").assertIsDisplayed()
        composeRule.onNodeWithText("Fond").assertDoesNotExist()
        // Märkningarna och utgångsläget står kvar överst i innehållet.
        composeRule.onNodeWithText("Risk 4/7").assertIsDisplayed()
        composeRule.onNodeWithText("Första köp", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Ska du byta?").assertIsDisplayed()
    }

    @Test
    fun `läsfel visar ramens fel med Försök igen i stället för en tom fond (NFR-6)`() {
        var retries = 0
        composeRule.setContent {
            FonderTheme {
                FondDetaljContent(
                    state = FondDetaljUiState(fund = DetailUiState.Error(AppErrors.map(DataError.Database))),
                    onRetry = { retries++ }
                )
            }
        }
        composeRule.onNodeWithText("Kunde inte hämta uppgifterna").assertIsDisplayed()
        composeRule.onNodeWithText("Appens data gick inte att läsa. Lämna skärmen och försök igen.").assertIsDisplayed()
        composeRule.onNodeWithText("Ingen kurshistorik", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Försök igen").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `en fond utan kurshistorik visar det tomma läget under toppraden`() {
        composeRule.setContent {
            FonderTheme { FondDetaljContent(state = FondDetaljUiState(fund = DetailUiState.Content(fund.copy(prices = persistentListOf())))) }
        }
        composeRule.onNodeWithText("Ingen kurshistorik än").assertIsDisplayed()
        composeRule.onNodeWithText("Ingen kurshistorik hämtad än för den här fonden.").assertIsDisplayed()
        // Fondens namn står bara i toppraden, inte en gång till som det tomma lägets rubrik.
        composeRule.onAllNodesWithText("Global Indexfond A").assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Tillbaka").assertIsDisplayed()
    }

    @Test
    fun `dra ned hämtar om fondens kurs (UI-11)`() {
        var refreshes = 0
        composeRule.setContent { FonderTheme { FondDetaljContent(state = content, onRefresh = { refreshes++ }) } }
        composeRule.onNodeWithTag(ENTITY_REFRESH_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertEquals(1, refreshes)
    }

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xxhdpi")
    fun `skärmdump av Fonddetalj på detaljramen`() = composeRule.captureLightAndDark(
        "FondDetalj_content",
        ready = { isDrawn(hasContentDescription("Kursdiagram", substring = true)) }
    ) {
        FondDetaljContent(state = content)
    }

    @Test
    @Config(qualifiers = "w390dp-h800dp-xxhdpi")
    fun `skärmdump av Fonddetalj under laddning`() = composeRule.captureLightAndDark("FondDetalj_loading", animated = true) {
        FondDetaljContent(state = FondDetaljUiState())
    }

    @Test
    @Config(qualifiers = "w390dp-h800dp-xxhdpi")
    fun `skärmdump av Fonddetaljs läsfel`() = composeRule.captureLightAndDark("FondDetalj_error") {
        FondDetaljContent(state = FondDetaljUiState(fund = DetailUiState.Error(AppErrors.map(DataError.Database))))
    }

    // --- Skatt på ett byte i depå/AF (ANA-15, issue #199) ---

    /** Mockupens innehav: värt 40 500 kr mot anskaffningsvärdet 25 000 kr → vinst 15 500 kr. */
    private val depaContent = FondDetaljUiState(
        fund = DetailUiState.Content(fund.copy(holdingValue = 40_500.0)),
        riskLevel = 4,
        feeComparison = FeeComparisonUiState.NoCheaperAlternative,
        switchTax = TaxCalc.switchTax(value = 40_500.0, costBasis = 25_000.0),
        depa = true
    )

    @Test
    fun `depa visar skatten pa ett byte i stallet for att planen inte foreslar nagot`() {
        composeRule.setContent { FonderTheme { FondDetaljContent(state = depaContent) } }
        val tax = depaContent.switchTax!!
        composeRule.onNodeWithText("Riskprofilens bytesplan ges bara i ISK/KF. I en depå kostar ett byte skatt på vinsten:").assertExists()
        composeRule.onNodeWithText("Skatt om du byter allt").assertExists()
        composeRule.onNodeWithText("vinst ${MoneyFormat.krWhole(15_500.0)} × 30 %").assertExists()
        composeRule.onNodeWithText("ca ${MoneyFormat.krWhole(-4_650.0)}").assertExists()
        composeRule.onNodeWithText("Skatt per ${MoneyFormat.krWhole(10_000.0)} som byts").assertExists()
        composeRule.onNodeWithText("ca ${MoneyFormat.krWhole(-tax.taxPerExampleAmount)}").assertExists()
        composeRule.onNodeWithText("Riskprofilens bytesplan föreslår inget", substring = true).assertDoesNotExist()
    }

    @Test
    fun `depa utan kant varde sager att planen bara ges i ISK och visar ingen skatt`() {
        composeRule.setContent { FonderTheme { FondDetaljContent(state = depaContent.copy(switchTax = null, depa = true)) } }
        composeRule.onNodeWithText("Riskprofilens bytesplan ges bara i ISK/KF.").assertExists()
        composeRule.onNodeWithText("Skatt om du byter allt").assertDoesNotExist()
        composeRule.onNodeWithText("Riskprofilens bytesplan föreslår inget", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w390dp-h1400dp-xxhdpi")
    fun `skärmdump av Fonddetalj i depå med skatten på ett byte`() = composeRule.captureLightAndDark(
        "FondDetalj_depa",
        ready = { isDrawn(hasContentDescription("Kursdiagram", substring = true)) }
    ) {
        FondDetaljContent(state = depaContent)
    }

    private companion object {
        const val FUND_ID = "SHB0000442"
    }
}
