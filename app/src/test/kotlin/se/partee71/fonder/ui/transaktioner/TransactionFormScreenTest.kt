package se.partee71.fonder.ui.transaktioner

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.SwitchDecision
import se.partee71.fonder.domain.usecase.SwitchOption
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Transaktionsformuläret med bytesväljaren (BYT-2, issue #144) i alla lägen: dold när det bara
 * finns "Inget byte", förvald planrad från Följ, obligatorisk fråga med släckt Spara och
 * förklaring, och skärmdumpar ljust + mörkt enligt mockupen ("Registrera säljet (från Följ)",
 * "Köp när ett byte väntar"). Påhittade fonder.
 */
@RunWith(RobolectricTestRunner::class)
class TransactionFormScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 10, 12)
    private val smabolag = Fund(fundId = "SEB_SB", name = "SEB Sverige Småbolag", isin = "SE_SB")
    private val avanza = Fund(fundId = "AV_GL", name = "Avanza Global", isin = "SE_AG")

    private val none = SwitchOptionUi(SwitchOption.Decide(SwitchDecision.None), SwitchOptionLabel.NoSwitch)
    private val standalone = SwitchOptionUi(SwitchOption.Decide(SwitchDecision.Standalone), SwitchOptionLabel.Standalone)
    private val planRow = SwitchOptionUi(
        SwitchOption.Decide(SwitchDecision.ForSuggestion(42, 2)),
        SwitchOptionLabel.PlanRow(planIndex = 1, sellFundName = "SEB Sverige Småbolag", buyFundName = "Spiltan Räntefond Sverige")
    )
    private val watch = SwitchOptionUi(
        SwitchOption.Decide(SwitchDecision.ClosesWatch(7)),
        SwitchOptionLabel.Watch("Swedbank Robur Ny Teknik", LocalDate.of(2026, 10, 10).toEpochDay(), 18_400.0)
    )

    /** "Registrera säljet" från Följ: fond och typ låsta, förifyllt, planraden förvald. */
    private val follow = TransactionFormUiState(
        funds = persistentListOf(smabolag),
        selectedFund = smabolag,
        type = TransactionType.SALJ,
        date = today,
        sharesText = "38,4615",
        priceText = "325.0",
        valid = true,
        prefilled = true,
        fundLocked = true,
        typeLocked = true,
        followIntro = FollowIntro(planIndex = 1, buyFundName = "Spiltan Räntefond Sverige", amountKr = 12_500.0),
        prefillAmountKr = 12_500.0,
        switchField = SwitchFieldUi(TransactionType.SALJ, persistentListOf(none, standalone, planRow), selected = planRow)
    )

    /** Köp av en fond som inte är ett bevakat alternativ medan ett byte väntar: frågan krävs. */
    private val buyRequired = TransactionFormUiState(
        funds = persistentListOf(smabolag, avanza),
        selectedFund = avanza,
        type = TransactionType.KOP,
        date = today.plusDays(2),
        sharesText = "102,5641",
        priceText = "179,40",
        valid = true,
        switchField = SwitchFieldUi(TransactionType.KOP, persistentListOf(none, watch), selected = null)
    )

    @Test
    fun `utan nagot att koppla till visas ingen valjare och Spara ar tand`() {
        val state = buyRequired.copy(switchField = SwitchFieldUi(TransactionType.KOP, persistentListOf(none), selected = none))
        composeRule.setContent { FonderTheme { TransactionFormContent(state = state) } }

        composeRule.onAllNodesWithText("Hör till byte").assertCountEquals(0)
        composeRule.onNodeWithText("Spara").assertIsEnabled()
    }

    @Test
    fun `fran Folj star forslaget overst fond och typ ar lasta och planraden ar forvald`() {
        composeRule.setContent { FonderTheme { TransactionFormContent(state = follow) } }

        composeRule.onNodeWithText(
            "Byte 2 i bytesplanen: sälj för ${MoneyFormat.kr(12_500.0)} och köp Spiltan Räntefond Sverige när likviden landat. " +
                "Gör säljet i banken först och registrera det här."
        ).assertIsDisplayed()
        composeRule.onNode(hasText("Fond") and hasText("SEB Sverige Småbolag")).assertIsNotEnabled()
        composeRule.onNodeWithText("Köp").assertIsNotEnabled()
        composeRule.onNodeWithText("Förifyllt ur beloppet ${MoneyFormat.kr(12_500.0)} och senaste kursen. Ändra till det banken bokförde.").assertIsDisplayed()
        // Fältets namn, valet och vad valet gör står i samma nod — skärmläsaren läser dem i ett svep.
        composeRule.onNode(
            hasText("Byte") and hasText("Byte 2 · Sälj SEB Sverige Småbolag → Köp Spiltan Räntefond Sverige") and
                hasText("Bytet syns sedan som Sålt på Beslut", substring = true)
        ).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Spara säljet").assertIsEnabled()
    }

    @Test
    fun `en fraga utan forval slacker Spara med en forklaring och ett val rapporteras`() {
        var picked: SwitchOption? = null
        composeRule.setContent { FonderTheme { TransactionFormContent(state = buyRequired, onSwitchSelected = { picked = it }) } }

        composeRule.onNode(hasText("Hör till byte") and hasText("Välj ett svar")).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(
            "Avanza Global är inte ett av alternativen du bevakar, så appen frågar i stället för att gissa. " +
                "Väljer du bytet stängs bevakningen som köpt med den här fonden."
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Spara").assertIsNotEnabled()
        composeRule.onNodeWithText("Svara på frågan om bytet innan du sparar.").assertIsDisplayed()

        composeRule.onNode(hasText("Välj ett svar")).performClick()
        composeRule.onNodeWithText("Bytet från Swedbank Robur Ny Teknik · sålt 2026-10-10 · ${MoneyFormat.kr(18_400.0)} väntar på köp").performClick()

        assertEquals(SwitchOption.Decide(SwitchDecision.ClosesWatch(7)), picked)
    }

    @Test
    fun `en koppling som inte kunde goras sags ut och Spara ar slackt`() {
        composeRule.setContent { FonderTheme { TransactionFormContent(state = follow.copy(linkFailed = true)) } }

        composeRule.onNodeWithText(
            "Transaktionen sparades, men bevakningen kunde inte kopplas – den var redan kopplad eller avslutad. " +
                "Gå tillbaka; säljet finns i Händelser utan koppling."
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Spara säljet").assertIsNotEnabled()
    }

    @Test
    fun `ett valt avslut sager att bytet stangs pa kopdagen`() {
        composeRule.setContent { FonderTheme { TransactionFormContent(state = buyRequired.copy(switchField = buyRequired.switchField!!.copy(selected = watch))) } }

        composeRule.onNodeWithText("Bytet stängs som köpt på köpdagen med den här transaktionen.").assertIsDisplayed()
        composeRule.onNodeWithText("Spara").assertIsEnabled()
    }

    @Test
    fun `en okand fond fran Kopte den har sags ut och fondvaljaren ar oppen`() {
        val state = buyRequired.copy(selectedFund = null, prefilled = true, typeLocked = true, fundLookupFailed = true, valid = false)
        composeRule.setContent { FonderTheme { TransactionFormContent(state = state) } }

        composeRule.onNodeWithText("Fonden kunde inte slås upp på ISIN. Välj den i listan om du redan bevakar den.").assertIsDisplayed()
        composeRule.onNodeWithText("Sälj").assertIsNotEnabled()
    }

    @Test
    fun `en fond som inte gar att sla upp utan bevakade fonder att valja bland sags ut som tomt lage`() {
        // Annars ett formulär som aldrig kan sparas (granskning #144).
        val state = TransactionFormUiState(prefilled = true, typeLocked = true, type = TransactionType.KOP, fundLookupFailed = true)
        composeRule.setContent { FonderTheme { TransactionFormContent(state = state) } }

        composeRule.onNodeWithText("Fonden kunde inte slås upp", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("Spara").assertCountEquals(0)
    }

    @Test
    fun `medan fonden ur nyckeln slas upp sags det i fondfaltet`() {
        val state = TransactionFormUiState(prefilled = true, typeLocked = true, fundLocked = true, fundLoading = true, type = TransactionType.KOP)
        composeRule.setContent { FonderTheme { TransactionFormContent(state = state) } }

        composeRule.onNodeWithText("Hämtar fonden …").assertIsDisplayed()
    }

    @Config(qualifiers = "w390dp-h1000dp-xxhdpi")
    @Test
    fun `skärmdump registrera säljet från Följ`() = composeRule.captureLightAndDark("TransactionForm_follow") {
        TransactionFormContent(state = follow)
    }

    @Config(qualifiers = "w390dp-h1000dp-xxhdpi")
    @Test
    fun `skärmdump köp när ett byte väntar`() = composeRule.captureLightAndDark("TransactionForm_buyRequired") {
        TransactionFormContent(state = buyRequired)
    }
}
