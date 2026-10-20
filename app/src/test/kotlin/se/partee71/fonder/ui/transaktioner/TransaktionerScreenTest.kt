package se.partee71.fonder.ui.transaktioner

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.RealizedGainCalculator
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.testing.contracts
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.common.ListUiState
import se.partee71.ui.common.toListUiState
import se.partee71.ui.testing.runListScreenContract

/**
 * Fliken Händelser i JVM med Robolectric: listramens kontrakt (laddning, tomt med "Ny transaktion" och
 * "Importera avräkningsnota", fel med "Försök igen", NFR-6, TRX-7), filterraden (TRX-3), kortet med årets
 * realiserade resultat (TRX-4), säljraden med vinst, förlust och osäkert resultat (SLD-1/SLD-2), och
 * skärmdumpar ljust + mörkt i "Alla", "Sälj" och utan transaktioner enligt mockupen. Radernas utfällning, bevakning och radering testas
 * instrumenterat i `androidTest/…/ui/transaktioner/TransaktionerScreenTest`.
 */
@RunWith(RobolectricTestRunner::class)
class TransaktionerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun day(month: Int, dayOfMonth: Int, year: Int = 2026) = LocalDate.of(year, month, dayOfMonth).toEpochDay()

    /** Mockupens lista med påhittade fonder: ett sälj med vinst, ett med förlust, ett osäkert och några köp. */
    private val transactions = listOf(
        Transaction(id = 9, fundId = "SB", type = TransactionType.SALJ, epochDay = day(10, 2), shares = 100.0, pricePerShare = 300.0),
        Transaction(id = 8, fundId = "GI", type = TransactionType.KOP, epochDay = day(9, 25), shares = 10.0, pricePerShare = 200.0),
        Transaction(id = 7, fundId = "TM", type = TransactionType.SALJ, epochDay = day(9, 1), shares = 50.0, pricePerShare = 90.0, fee = 20.0),
        Transaction(id = 6, fundId = "RF", type = TransactionType.SALJ, epochDay = day(8, 20), shares = 30.0, pricePerShare = 35.0),
        Transaction(id = 5, fundId = "RF", type = TransactionType.KOP, epochDay = day(8, 12), shares = 10.0, pricePerShare = 100.0),
        Transaction(id = 4, fundId = "GI", type = TransactionType.KOP, epochDay = day(7, 25), shares = 10.0, pricePerShare = 200.0),
        Transaction(id = 3, fundId = "TM", type = TransactionType.KOP, epochDay = day(3, 4), shares = 50.0, pricePerShare = 100.0),
        Transaction(id = 2, fundId = "SB", type = TransactionType.KOP, epochDay = day(5, 10, year = 2024), shares = 100.0, pricePerShare = 278.6)
    )

    private val names = mapOf("SB" to "Sverige Småbolag", "GI" to "Global Indexfond A", "TM" to "Tillväxtmarknad Bred", "RF" to "Räntefond Kort")

    private fun state(filter: HandelseFilter = HandelseFilter.ALLA): TransaktionerUiState {
        val sales = RealizedGainCalculator.compute(transactions)
        val salesById = sales.associateBy { it.transactionId }
        return TransaktionerUiState(
            list = transactions.map { tx -> TransaktionRad(transaction = tx, fundName = names.getValue(tx.fundId), sale = salesById[tx.id]) }.toListUiState(),
            filter = filter,
            yearRealized = RealizedGainCalculator.totalForYear(sales, 2026),
            totalRealized = RealizedGainCalculator.total(sales)
        )
    }

    @Test
    fun `Händelser följer listramens kontrakt utan lägg till men med vägarna till första transaktionen`() {
        val row = TransaktionRad(transaction = transactions.first(), fundName = "Sverige Småbolag")
        composeRule.runListScreenContract(
            environment = contracts,
            item = row,
            itemText = "Sverige Småbolag",
            emptyTitle = "Inga transaktioner",
            addLabel = null,
            emptyActions = listOf("Ny transaktion", "Importera avräkningsnota")
        ) { state, _, onRetry ->
            TransaktionerContent(state = TransaktionerUiState(list = state), onRetry = onRetry)
        }
    }

    @Test
    fun `det tomma lägets knappar öppnar formuläret och avräkningsnotan och filtret står kvar`() {
        var opened = ""
        composeRule.setContent {
            FonderTheme {
                TransaktionerContent(
                    state = TransaktionerUiState(list = ListUiState.Empty),
                    onNewTransaction = { opened += "ny;" },
                    onImportOrders = { opened += "import;" }
                )
            }
        }
        composeRule.onNode(hasText("Alla") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsDisplayed()
        composeRule.onNodeWithText("Ny transaktion").performClick()
        composeRule.onNodeWithText("Importera avräkningsnota").performClick()
        assertEquals("ny;import;", opened)
    }

    @Test
    fun `ett filter utan träffar visar sitt eget tomma läge utan knappar`() {
        val onlyBuys = transactions.filter { it.type == TransactionType.KOP }
            .map { TransaktionRad(transaction = it, fundName = names.getValue(it.fundId)) }
        composeRule.setContent {
            FonderTheme { TransaktionerContent(state = TransaktionerUiState(list = onlyBuys.toListUiState(), filter = HandelseFilter.SALJ)) }
        }
        composeRule.onNodeWithText("Inga sälj ännu").assertIsDisplayed()
        composeRule.onNodeWithText("Ny transaktion").assertDoesNotExist()
    }

    @Test
    fun `filterraden har Alla Kop och Salj och ett val rapporteras`() {
        var selected: HandelseFilter? = null
        composeRule.setContent { FonderTheme { TransaktionerContent(state = state(), onFilterSelected = { selected = it }) } }

        // Etiketterna "Köp"/"Sälj" står även i radernas undertext – filtret är radiovalen.
        fun choice(label: String) = composeRule.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        choice("Alla").assertIsSelected()
        choice("Köp").assertIsDisplayed()
        choice("Sälj").performClick()

        assertEquals(HandelseFilter.SALJ, selected)
    }

    @Test
    fun `kortet visar arets realiserade resultat och saljraderna resultatet i undertexten`() {
        composeRule.setContent { FonderTheme { TransaktionerContent(state = state()) } }

        composeRule.onNodeWithText("Realiserat resultat i år").assertIsDisplayed()
        // 30 000 − 27 860 = 2 140 kr vinst.
        composeRule.onNodeWithText("Sälj · vinst +2", substring = true).assertIsDisplayed()
        // 4 500 − 20 − 5 000 = −520 kr förlust.
        composeRule.onNodeWithText("Sälj · förlust −520,00 kr", substring = true).assertIsDisplayed()
        // 30 andelar sålda men bara 10 köpta: osäkert, i text (SLD-2).
        composeRule.onNodeWithText("· osäkert", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Osäkert — något sälj saknar matchande köp i historiken.").assertIsDisplayed()
        // Totalen sedan start står bara i säljfiltret.
        composeRule.onNodeWithText("Totalt realiserat resultat").assertDoesNotExist()
    }

    @Test
    fun `en saljrad laser upp om den ar ihopfalld eller utfalld`() {
        composeRule.setContent { FonderTheme { TransaktionerContent(state = state(HandelseFilter.SALJ)) } }

        val row = composeRule.onNode(hasText("Sälj · förlust −520,00 kr", substring = true).and(hasStateDescription("Ihopfälld")), useUnmergedTree = false)
        row.assertExists().performClick()
        composeRule.onNode(hasText("Sälj · förlust −520,00 kr", substring = true).and(hasStateDescription("Utfälld"))).assertExists()
    }

    @Test
    fun `saljfiltret visar totalen sedan start och kopfiltret inget realiserat resultat`() {
        composeRule.setContent { FonderTheme { TransaktionerContent(state = state(HandelseFilter.SALJ)) } }
        composeRule.onNodeWithText("Totalt realiserat resultat").assertIsDisplayed()
        composeRule.onNodeWithText("Global Indexfond A").assertDoesNotExist()
    }

    @Test
    fun `kopfiltret visar inget realiserat resultat`() {
        composeRule.setContent { FonderTheme { TransaktionerContent(state = state(HandelseFilter.KOP)) } }
        composeRule.onNodeWithText("Realiserat resultat i år").assertDoesNotExist()
        composeRule.onNodeWithText("Sälj · vinst", substring = true).assertDoesNotExist()
    }

    @Test
    fun `listan renderas lat – rad 200 komponeras forst nar man skrollat dit`() {
        // Ett månadssparande ger hundratals transaktioner; de ska inte komponeras i förväg.
        val many = (1..300).map { n ->
            TransaktionRad(
                transaction = Transaction(id = n.toLong(), fundId = "F$n", type = TransactionType.KOP, epochDay = day(1, 1) - n, shares = 1.0, pricePerShare = 100.0),
                fundName = "Fond nr $n"
            )
        }
        composeRule.setContent {
            FonderTheme { TransaktionerContent(state = TransaktionerUiState(list = many.toListUiState())) }
        }

        composeRule.onNodeWithText("Fond nr 1").assertIsDisplayed()
        composeRule.onNodeWithText("Fond nr 200").assertDoesNotExist()

        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Fond nr 200"))
        composeRule.onNodeWithText("Fond nr 200").assertIsDisplayed()
        composeRule.onNodeWithText("Fond nr 1").assertDoesNotExist()
    }

    @Test
    fun `skärmdump av Händelser i Alla`() = composeRule.captureLightAndDark("HandelserScreen_alla") {
        TransaktionerContent(state = state())
    }

    @Test
    fun `skärmdump av Händelser i Sälj`() = composeRule.captureLightAndDark("HandelserScreen_salj") {
        TransaktionerContent(state = state(HandelseFilter.SALJ))
    }

    @Test
    fun `skärmdump av Händelser utan transaktioner`() = composeRule.captureLightAndDark("HandelserScreen_tomt") {
        TransaktionerContent(state = TransaktionerUiState(list = ListUiState.Empty))
    }
}
