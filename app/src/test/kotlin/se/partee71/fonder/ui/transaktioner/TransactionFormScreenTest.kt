package se.partee71.fonder.ui.transaktioner

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import se.partee71.fonder.data.common.AppErrors
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.SwitchDecision
import se.partee71.fonder.domain.usecase.SwitchOption
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.testing.contracts
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.common.EditorEvents
import se.partee71.ui.common.EditorState
import se.partee71.ui.common.EditorUiState
import se.partee71.ui.testing.runEditScreenContract

/**
 * Transaktionsformuläret på android-ui:s `EntityEditScreen` (NAV-4, UI-15, NFR-6) med bytesväljaren
 * (BYT-2, issue #144): ramens kontrakt (Spara i toppraden först när ändrat och giltigt, "Släng
 * ändringar?" vid bakåt, sparfel som snackbar, tillbaka efter sparning), förifyllt från Följ med Spara
 * aktiv utan ändring, bytesfrågan, kopplingen som inte kunde göras, läsfel med "Försök igen", "Sälj
 * alla andelar" och översälj (TRX-5), inga bevakade fonder (TRX-6) och skärmdumpar ljust + mörkt.
 * Påhittade fonder.
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

    private val blank = TransactionForm(date = today)

    /**
     * Formuläret som ViewModel:n håller det: laddat med [stored] och, med [changed], ändrat av
     * användaren till det värdet. Bytesfrågan är besvarad enligt [switchAnswered]; innehavet [owned].
     */
    private fun editor(
        stored: TransactionForm = blank,
        changed: TransactionForm? = null,
        switchAnswered: Boolean = true,
        owned: Double? = null,
        linkFailed: Boolean = false,
        saveUnchanged: Boolean = false
    ): EditorState<TransactionForm> = EditorState(
        stored,
        transactionFormValidator(switchAnswered = { switchAnswered }, ownedShares = { owned }, linkFailed = { linkFailed }),
        AppErrors,
        saveUnchanged = saveUnchanged
    ).apply {
        load(stored)
        if (changed != null) update(TRANSACTION_FUND, TRANSACTION_SHARES, TRANSACTION_PRICE, TRANSACTION_FEE) { changed }
    }

    private fun EditorState<TransactionForm>.ui() = state.value

    private val followForm = TransactionForm(fund = smabolag, type = TransactionType.SALJ, date = today, sharesText = "38,4615", priceText = "325.0")

    /** "Registrera säljet" från Följ: fond och typ låsta, förifyllt, planraden förvald – får sparas oförändrat. */
    private val follow = TransactionFormUiState(
        editor = editor(followForm, saveUnchanged = true).ui(),
        funds = persistentListOf(smabolag),
        valid = true,
        prefilled = true,
        fundLocked = true,
        typeLocked = true,
        followIntro = FollowIntro(planIndex = 1, buyFundName = "Spiltan Räntefond Sverige", amountKr = 12_500.0),
        prefillAmountKr = 12_500.0,
        switchField = SwitchFieldUi(TransactionType.SALJ, persistentListOf(none, standalone, planRow), selected = planRow)
    )

    private val buyForm = TransactionForm(fund = avanza, date = today.plusDays(2), sharesText = "102,5641", priceText = "179,40")

    /** Köp av en fond som inte är ett bevakat alternativ medan ett byte väntar: frågan krävs. */
    private val buyRequired = TransactionFormUiState(
        editor = editor(changed = buyForm, switchAnswered = false).ui(),
        funds = persistentListOf(smabolag, avanza),
        valid = true,
        switchField = SwitchFieldUi(TransactionType.KOP, persistentListOf(none, watch), selected = null)
    )

    /** Samma köp med avslutet valt. */
    private val buyAnswered = buyRequired.copy(
        editor = editor(changed = buyForm.copy(chosenSwitch = watch.option)).ui(),
        switchField = buyRequired.switchField!!.copy(selected = watch)
    )

    private val sellForm = TransactionForm(fund = smabolag, type = TransactionType.SALJ, date = today, sharesText = "37,5214", priceText = "152,40")

    /** Sälj av hela innehavet med "Sälj alla andelar" (TRX-5). */
    private val sellAll = TransactionFormUiState(
        editor = editor(changed = sellForm, owned = 37.5214).ui(),
        funds = persistentListOf(smabolag, avanza),
        valid = true,
        switchField = SwitchFieldUi(TransactionType.SALJ, persistentListOf(none), selected = none),
        ownedShares = 37.5214
    )

    private fun show(state: TransactionFormUiState, onClose: () -> Unit = {}, onWatchFund: () -> Unit = {}, onSellAll: () -> Unit = {}, onRetry: () -> Unit = {}, onSwitchSelect: (SwitchOption) -> Unit = {}) {
        composeRule.setContent {
            FonderTheme {
                TransactionFormContent(
                    state = state,
                    events = EditorEvents.None,
                    onClose = onClose,
                    onWatchFund = onWatchFund,
                    onSellAll = onSellAll,
                    onRetry = onRetry,
                    onSwitchSelect = onSwitchSelect
                )
            }
        }
    }

    @Config(qualifiers = "w390dp-h1000dp-xxhdpi")
    @Test
    fun `transaktionsformularet uppfyller redigeringsramens kontrakt`() {
        composeRule.runEditScreenContract(
            environment = contracts,
            editor = editor(),
            makeInvalid = { update(TRANSACTION_SHARES) { it.copy(fund = avanza, sharesText = "0", priceText = "179,40") } },
            makeValid = { update(TRANSACTION_SHARES) { it.copy(sharesText = "10") } },
            invalidMessage = "Ange ett antal andelar större än 0."
        ) { state, events, onSave, onClose ->
            TransactionFormContent(TransactionFormUiState(editor = state, funds = persistentListOf(smabolag, avanza)), events, onClose, onSave = onSave)
        }
    }

    @Test
    fun `rubriken och Spara star bara i toppraden`() {
        show(TransactionFormUiState(editor = editor().ui(), funds = persistentListOf(smabolag)))

        composeRule.onAllNodesWithText("Ny transaktion").assertCountEquals(1)
        composeRule.onAllNodesWithText("Spara").assertCountEquals(1)
        composeRule.onNodeWithText("Spara").assertIsNotEnabled()
    }

    @Test
    fun `utan nagot att koppla till visas ingen valjare och Spara ar tand`() {
        show(buyAnswered.copy(switchField = SwitchFieldUi(TransactionType.KOP, persistentListOf(none), selected = none)))

        composeRule.onAllNodesWithText("Hör till byte").assertCountEquals(0)
        composeRule.onNodeWithText("Spara").assertIsEnabled()
    }

    @Test
    fun `fran Folj star forslaget overst fond och typ ar lasta och Spara saljet ar aktiv utan andring`() {
        show(follow)

        composeRule.onAllNodesWithText("Registrera säljet").assertCountEquals(1)
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
    fun `bakat fran ett oforandrat forifyllt salj stanger direkt`() {
        var closes = 0
        show(follow, onClose = { closes++ })

        composeRule.onNodeWithContentDescription("Tillbaka").performClick()
        assertEquals(1, closes)
        composeRule.onNodeWithText("Släng ändringar?").assertDoesNotExist()
    }

    @Test
    fun `en fraga utan forval slacker Spara med en forklaring och ett val rapporteras`() {
        var picked: SwitchOption? = null
        show(buyRequired, onSwitchSelect = { picked = it })

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
    fun `en koppling som inte kunde goras sags overst och Spara ar slackt`() {
        show(follow.copy(editor = editor(followForm, linkFailed = true, saveUnchanged = true).ui(), linkFailed = true))

        composeRule.onNodeWithText(
            "Transaktionen sparades, men bevakningen kunde inte kopplas – den var redan kopplad eller avslutad. " +
                "Gå tillbaka; säljet finns i Händelser utan koppling."
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Spara säljet").assertIsNotEnabled()
    }

    @Test
    fun `Spara ar slackt medan transaktionen sparas`() {
        show(buyAnswered.copy(editor = buyAnswered.editor.copy(saving = true)))

        composeRule.onNodeWithText("Spara").assertIsNotEnabled()
        // Inmatningen står kvar medan det sparar (#185).
        composeRule.onNodeWithText("102,5641").assertIsDisplayed()
    }

    @Test
    fun `ett valt avslut sager att bytet stangs pa kopdagen`() {
        show(buyAnswered)

        composeRule.onNodeWithText("Bytet stängs som köpt på köpdagen med den här transaktionen.").assertIsDisplayed()
        composeRule.onNodeWithText("Spara").assertIsEnabled()
    }

    @Test
    fun `en okand fond fran Kopte den har sags ut och fondvaljaren ar oppen`() {
        val stored = TransactionForm(date = today)
        show(buyRequired.copy(editor = editor(stored).ui(), prefilled = true, typeLocked = true, fundLookupFailed = true, valid = false))

        composeRule.onNodeWithText("Fonden kunde inte slås upp på ISIN. Välj den i listan om du redan bevakar den.").assertIsDisplayed()
        composeRule.onNodeWithText("Sälj").assertIsNotEnabled()
    }

    @Test
    fun `utan bevakade fonder visas ett tomt lage med Bevaka en fond och ingen Spara`() {
        // TRX-6
        var watches = 0
        var closes = 0
        show(TransactionFormUiState(editor = editor().ui()), onClose = { closes++ }, onWatchFund = { watches++ })

        composeRule.onNodeWithText("Inga bevakade fonder").assertIsDisplayed()
        composeRule.onNodeWithText("Bevaka en fond först, så kan du registrera köp och sälj i den.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Spara").assertCountEquals(0)
        composeRule.onNodeWithText("Bevaka en fond").performClick()
        assertEquals(1, watches)
        composeRule.onNodeWithContentDescription("Tillbaka").performClick()
        assertEquals(1, closes)
    }

    @Test
    fun `en fond som inte gar att sla upp utan bevakade fonder att valja bland sags ut som tomt lage`() {
        // Annars ett formulär som aldrig kan sparas (granskning #144).
        val state = TransactionFormUiState(editor = editor(TransactionForm(type = TransactionType.KOP)).ui(), prefilled = true, typeLocked = true, fundLookupFailed = true)
        show(state)

        composeRule.onNodeWithText("Fonden kunde inte slås upp", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Bevaka en fond").assertIsDisplayed()
        composeRule.onAllNodesWithText("Spara").assertCountEquals(0)
    }

    @Test
    fun `medan formularet lases visas inga falt och Spara ar slackt`() {
        show(TransactionFormUiState(editor = EditorUiState(TransactionForm(type = TransactionType.KOP), loading = true), prefilled = true, typeLocked = true, fundLocked = true))

        composeRule.onAllNodesWithText("Registrera köpet").assertCountEquals(1)
        composeRule.onNodeWithText("Antal andelar").assertDoesNotExist()
        composeRule.onNodeWithText("Inga bevakade fonder").assertDoesNotExist()
        composeRule.onNodeWithText("Spara").assertIsNotEnabled()
    }

    @Test
    fun `ett lasfel visas med Forsok igen i stallet for formularet`() {
        var retries = 0
        val failed = editor().apply { loadFailed(DataError.Network) }.ui()
        show(TransactionFormUiState(editor = failed), onRetry = { retries++ })

        composeRule.onNodeWithText("Antal andelar").assertDoesNotExist()
        composeRule.onNodeWithText("Inga bevakade fonder").assertDoesNotExist()
        composeRule.onNodeWithText("Spara").assertIsNotEnabled()
        composeRule.onNodeWithText("Försök igen").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `vid salj star innehavet och Salj alla andelar fyller i det`() {
        // TRX-5
        var sellAllClicks = 0
        show(sellAll, onSellAll = { sellAllClicks++ })

        composeRule.onNodeWithText("Du äger 37,5214 andelar").assertIsDisplayed()
        composeRule.onNodeWithText("Sälj alla andelar").performClick()
        assertEquals(1, sellAllClicks)
    }

    @Test
    fun `utan andelar i fonden finns ingen Salj alla andelar`() {
        show(sellAll.copy(ownedShares = 0.0))

        composeRule.onNodeWithText("Du äger 0 andelar").assertIsDisplayed()
        composeRule.onNodeWithText("Sälj alla andelar").assertDoesNotExist()
    }

    @Test
    fun `vid kop visas inget innehav`() {
        show(buyAnswered)

        composeRule.onNodeWithText("Du äger", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Sälj alla andelar").assertDoesNotExist()
    }

    @Test
    fun `fler andelar an innehavet ar ett faltfel och slacker Spara`() {
        // TRX-5
        show(sellAll.copy(editor = editor(changed = sellForm.copy(sharesText = "40"), owned = 37.5214).ui(), valid = false))

        composeRule.onNodeWithText("Du kan inte sälja fler andelar än du äger.").assertIsDisplayed()
        composeRule.onNodeWithText("Spara").assertIsNotEnabled()
    }

    @Config(qualifiers = "w390dp-h1000dp-xxhdpi")
    @Test
    fun `skärmdump registrera säljet från Följ`() = composeRule.captureLightAndDark("TransactionForm_follow") {
        TransactionFormContent(state = follow, events = EditorEvents.None, onClose = {})
    }

    @Config(qualifiers = "w390dp-h1000dp-xxhdpi")
    @Test
    fun `skärmdump köp när ett byte väntar`() = composeRule.captureLightAndDark("TransactionForm_buyRequired") {
        TransactionFormContent(state = buyRequired, events = EditorEvents.None, onClose = {})
    }

    @Config(qualifiers = "w390dp-h1000dp-xxhdpi")
    @Test
    fun `skärmdump sälj alla andelar`() = composeRule.captureLightAndDark("TransactionForm_sellAll") {
        TransactionFormContent(state = sellAll, events = EditorEvents.None, onClose = {})
    }

    @Config(qualifiers = "w390dp-h1000dp-xxhdpi")
    @Test
    fun `skärmdump inga bevakade fonder`() = composeRule.captureLightAndDark("TransactionForm_noFunds") {
        TransactionFormContent(state = TransactionFormUiState(editor = editor().ui()), events = EditorEvents.None, onClose = {})
    }
}
