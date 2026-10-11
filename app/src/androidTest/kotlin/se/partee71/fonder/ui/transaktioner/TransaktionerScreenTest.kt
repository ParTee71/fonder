package se.partee71.fonder.ui.transaktioner

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.RealizedGainCalculator
import se.partee71.fonder.domain.usecase.RealizedSale
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Instrumenterat test av Händelsers tillståndsdrivna innehåll — transaktionslistan (TRX-1/TRX-2) och
 * säljens realiserade resultat (SLD-1…SLD-5), som fram till 0.62.0 var en egen vy, Sålda fonder
 * (filen hette då SoldFundsScreenTest). Bygger [TransaktionerUiState] direkt i stället för att gå via
 * ett riktigt [TransaktionerViewModel]/Hilt, samma mönster som [se.partee71.fonder.ui.portfolj.PortfoljScreenTest].
 */
@RunWith(AndroidJUnit4::class)
class TransaktionerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sellTx(id: Long = 1) = Transaction(
        id = id, fundId = "SHB0000442", type = TransactionType.SALJ, epochDay = 100, shares = 10.0, pricePerShare = 60.0, fee = 10.0,
    )

    private fun sale(id: Long = 1, uncoveredShares: Double = 0.0) = RealizedSale(
        transactionId = id,
        fundId = "SHB0000442",
        epochDay = 100,
        shares = 10.0,
        proceeds = 600.0,
        fee = 10.0,
        costBasis = 500.0,
        uncoveredShares = uncoveredShares,
    )

    private fun sellRow(
        fundName: String = "Fond A",
        uncoveredShares: Double = 0.0,
        fundIsin: String? = null,
        canStartSwitchWatch: Boolean = false,
    ) = TransaktionRad(
        transaction = sellTx(),
        fundName = fundName,
        sale = sale(uncoveredShares = uncoveredShares),
        fundIsin = fundIsin,
        canStartSwitchWatch = canStartSwitchWatch,
    )

    private fun state(vararg rows: TransaktionRad, filter: HandelseFilter = HandelseFilter.ALLA) = TransaktionerUiState(
        loading = false,
        allRows = rows.toList(),
        filter = filter,
        totalRealized = RealizedGainCalculator.total(rows.mapNotNull { it.sale }),
    )

    @Test
    fun tomt_tillstand_visas_utan_transaktioner() {
        composeRule.setContent { FonderTheme { TransaktionerContent(state = TransaktionerUiState(loading = false)) } }

        composeRule.onNodeWithText("Inga transaktioner").assertExists()
    }

    @Test
    fun saljfiltret_utan_salj_visar_tomt_tillstand_for_salj() {
        val buy = TransaktionRad(
            transaction = Transaction(id = 2, fundId = "F", type = TransactionType.KOP, epochDay = 100, shares = 1.0, pricePerShare = 10.0),
            fundName = "Fond K",
        )
        composeRule.setContent { FonderTheme { TransaktionerContent(state = state(buy, filter = HandelseFilter.SALJ)) } }

        composeRule.onNodeWithText("Inga sälj ännu").assertExists()
    }

    @Test
    fun saljfiltret_visar_summerat_realiserat_resultat() {
        // Två skilda sälj vars summa (300,00 kr) skiljer sig från vardera radens eget resultat
        // (90,00 kr respektive 210,00 kr) — annars kunde totalens text sammanfalla med en rads.
        val a = sellRow(fundName = "Fond A")
        val b = TransaktionRad(
            transaction = sellTx(id = 2),
            fundName = "Fond B",
            sale = sale(id = 2).copy(proceeds = 710.0, fee = 0.0, costBasis = 500.0),
        )

        composeRule.setContent { FonderTheme { TransaktionerContent(state = state(a, b, filter = HandelseFilter.SALJ)) } }

        composeRule.onNodeWithText("Totalt realiserat resultat").assertExists()
        composeRule.onNodeWithText("+30,0 % · 300,00 kr", substring = true).assertExists()
    }

    @Test
    fun saljrad_ar_stangd_som_standard_och_visar_fondnamn_och_resultat() {
        composeRule.setContent { FonderTheme { TransaktionerContent(state = state(sellRow())) } }

        composeRule.onNodeWithText("Fond A", substring = true).assertExists()
        composeRule.onNodeWithText("Sälj · vinst +90,00 kr", substring = true).assertExists()
        composeRule.onNodeWithText("10.0 andelar", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Belopp", substring = true).assertDoesNotExist()
    }

    @Test
    fun klick_expanderar_och_visar_detaljer_klick_igen_faller_ihop() {
        composeRule.setContent { FonderTheme { TransaktionerContent(state = state(sellRow())) } }

        composeRule.onNodeWithText("Fond A", substring = true).performClick()

        composeRule.onNodeWithText("10.0 andelar", substring = true).assertExists()
        composeRule.onNodeWithText("Belopp", substring = true).assertExists()
        composeRule.onNodeWithText("Avgift 10,00 kr", substring = true).assertExists()

        composeRule.onNodeWithText("Fond A", substring = true).performClick()

        composeRule.onNodeWithText("10.0 andelar", substring = true).assertDoesNotExist()
    }

    @Test
    fun osakert_resultat_markeras_i_raden_och_varningen_visas_expanderad() {
        composeRule.setContent { FonderTheme { TransaktionerContent(state = state(sellRow(uncoveredShares = 2.0))) } }

        // Markeringen står i text redan i hopfällt läge (SLD-2) – den fullständiga varningen i detaljerna.
        composeRule.onNodeWithText("osäkert", substring = true).assertExists()
        composeRule.onNodeWithText("Osäkert resultat", substring = true).assertDoesNotExist()

        composeRule.onNodeWithText("Fond A", substring = true).performClick()

        composeRule.onNodeWithText("Osäkert resultat", substring = true).assertExists()
    }

    @Test
    fun bevaka_alternativ_visas_bara_expanderat_och_bara_nar_saljet_kan_bevakas() {
        // Hopfällt läge är avsiktligt bara namn och resultat (SLD-4) — knappen ligger i den
        // utfällda delen, annars hade regeln brutits för varje rad.
        var started: TransaktionRad? = null
        composeRule.setContent {
            FonderTheme {
                TransaktionerContent(
                    state = state(sellRow(fundIsin = "SE_A", canStartSwitchWatch = true)),
                    onStartSwitchWatch = { started = it },
                )
            }
        }

        composeRule.onNodeWithText("Bevaka alternativ").assertDoesNotExist()

        composeRule.onNodeWithText("Fond A", substring = true).performClick()
        composeRule.onNodeWithText("Bevaka alternativ").performClick()

        assertEquals("SE_A", started?.fundIsin)
    }

    @Test
    fun ett_salj_utan_isin_erbjuder_ingen_bevakning() {
        composeRule.setContent { FonderTheme { TransaktionerContent(state = state(sellRow(canStartSwitchWatch = false))) } }
        composeRule.onNodeWithText("Fond A", substring = true).performClick()

        composeRule.onNodeWithText("Bevaka alternativ").assertDoesNotExist()
    }

    @Test
    fun langtryck_fragar_innan_transaktionen_tas_bort() {
        var deleted: Long? = null
        composeRule.setContent {
            FonderTheme { TransaktionerContent(state = state(sellRow()), onDeleteTransaction = { deleted = it }) }
        }

        composeRule.onNodeWithText("Fond A", substring = true).performTouchInput { longClick() }
        composeRule.onNodeWithText("Ta bort transaktion?").assertExists()
        composeRule.onNodeWithText("Ta bort").performClick()

        assertEquals(1L, deleted)
    }
}
