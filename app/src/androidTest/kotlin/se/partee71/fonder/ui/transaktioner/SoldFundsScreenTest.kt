package se.partee71.fonder.ui.transaktioner

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.usecase.RealizedSale
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Instrumenterat test av Sålda fonder-skärmens tillståndsdrivna innehåll (issue #21) —
 * totalsumman (SLD-3) och att korten är hopfällbara med fondnamn + resultat synligt som
 * standard (SLD-4). Bygger [SoldFundsUiState] direkt i stället för att gå via ett riktigt
 * [SoldFundsViewModel]/Hilt, samma mönster som [se.partee71.fonder.ui.portfolj.PortfoljScreenTest].
 */
@RunWith(AndroidJUnit4::class)
class SoldFundsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sale(uncoveredShares: Double = 0.0) = RealizedSale(
        transactionId = 1,
        fundId = "SHB0000442",
        epochDay = 100,
        shares = 10.0,
        proceeds = 600.0,
        fee = 10.0,
        costBasis = 500.0,
        uncoveredShares = uncoveredShares,
    )

    @Test
    fun tomt_tillstand_visas_utan_salj() {
        composeRule.setContent {
            FonderTheme { SoldFundsContent(state = SoldFundsUiState(loading = false)) }
        }

        composeRule.onNodeWithText("Inga sälj ännu").assertExists()
    }

    @Test
    fun totalkort_visar_summerat_realiserat_resultat() {
        val state = SoldFundsUiState(
            loading = false,
            rows = listOf(SoldFundRad(sale = sale(), fundName = "Fond A")),
            totalRealizedGain = 90.0,
            totalRealizedGainFraction = 0.18,
        )

        composeRule.setContent {
            FonderTheme { SoldFundsContent(state = state) }
        }

        composeRule.onNodeWithText("Totalt realiserat resultat").assertExists()
        composeRule.onNodeWithText("90,00 kr · +18,0 %", substring = true).assertExists()
    }

    @Test
    fun kort_ar_stangt_som_standard_och_visar_bara_fondnamn_och_resultat() {
        val state = SoldFundsUiState(loading = false, rows = listOf(SoldFundRad(sale = sale(), fundName = "Fond A")))

        composeRule.setContent {
            FonderTheme { SoldFundsContent(state = state) }
        }

        composeRule.onNodeWithText("Fond A").assertExists()
        composeRule.onNodeWithText("90,00 kr · +18,0 %", substring = true).assertExists()
        composeRule.onNodeWithText("10.0 andelar sålda", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Belopp", substring = true).assertDoesNotExist()
    }

    @Test
    fun klick_expanderar_och_visar_detaljer_klick_igen_faller_ihop() {
        val state = SoldFundsUiState(loading = false, rows = listOf(SoldFundRad(sale = sale(), fundName = "Fond A")))

        composeRule.setContent {
            FonderTheme { SoldFundsContent(state = state) }
        }

        composeRule.onNodeWithText("Fond A").performClick()

        composeRule.onNodeWithText("10.0 andelar sålda", substring = true).assertExists()
        composeRule.onNodeWithText("Belopp", substring = true).assertExists()

        composeRule.onNodeWithText("Fond A").performClick()

        composeRule.onNodeWithText("10.0 andelar sålda", substring = true).assertDoesNotExist()
    }

    @Test
    fun osaker_varning_visas_bara_nar_expanderad() {
        val state = SoldFundsUiState(loading = false, rows = listOf(SoldFundRad(sale = sale(uncoveredShares = 2.0), fundName = "Fond A")))

        composeRule.setContent {
            FonderTheme { SoldFundsContent(state = state) }
        }

        composeRule.onNodeWithText("Osäkert resultat", substring = true).assertDoesNotExist()

        composeRule.onNodeWithText("Fond A").performClick()

        composeRule.onNodeWithText("Osäkert resultat", substring = true).assertExists()
    }
}
