package se.partee71.fonder.ui.imports

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import java.time.LocalDate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.ImportedOrderTransaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.SwitchDecision
import se.partee71.fonder.domain.usecase.SwitchOption
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.fonder.ui.transaktioner.SwitchFieldUi
import se.partee71.fonder.ui.transaktioner.SwitchOptionLabel
import se.partee71.fonder.ui.transaktioner.SwitchOptionUi

/**
 * Granskningen av avräkningsnotorna med bytesväljaren per rad (IMP-6/BYT-2, issue #144): säljen
 * före köpen, ett köp som avslutar bytet en säljrad ovan startar, och skärmdump ljust + mörkt
 * enligt mockupen "Importera avräkningsnotor – granskning". Påhittade fonder.
 */
@RunWith(RobolectricTestRunner::class)
class ImportOrdersScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val none = SwitchOptionUi(SwitchOption.Decide(SwitchDecision.None), SwitchOptionLabel.NoSwitch)
    private val standalone = SwitchOptionUi(SwitchOption.Decide(SwitchDecision.Standalone), SwitchOptionLabel.Standalone)
    private val planRow = SwitchOptionUi(
        SwitchOption.Decide(SwitchDecision.ForSuggestion(42, 4)),
        SwitchOptionLabel.PlanRow(0, "Swedbank Robur Ny Teknik", "Länsförsäkringar Global Indexnära")
    )
    private val fromSaleRow = SwitchOptionUi(
        SwitchOption.Decide(SwitchDecision.ClosesWatchOfEntry(0)),
        SwitchOptionLabel.SaleRow("Swedbank Robur Ny Teknik")
    )

    private fun row(name: String, isin: String, type: TransactionType, day: Int, shares: Double, price: Double, field: SwitchFieldUi) = ImportOrderRowUiState(
        transaction = ImportedOrderTransaction(
            isin = isin, fundCompanyName = "Fondbolaget", fundName = name, type = type,
            epochDay = LocalDate.of(2026, 10, day).toEpochDay(), shares = shares, pricePerShare = price,
            amount = shares * price, sourceFileName = "nota-$day.pdf"
        ),
        matchedFund = Fund(fundId = isin, name = name, isin = isin),
        matchConfidence = 1.0,
        date = LocalDate.of(2026, 10, day),
        sharesText = shares.toString(),
        priceText = price.toString(),
        switchField = field
    )

    private val state = ImportOrdersUiState(
        filesSelected = true,
        switchReady = true,
        rows = persistentListOf(
            row(
                "Swedbank Robur Ny Teknik",
                "SE0000000101",
                TransactionType.SALJ,
                10,
                22.716,
                810.0,
                SwitchFieldUi(TransactionType.SALJ, persistentListOf(none, standalone, planRow), selected = planRow)
            ),
            row(
                "Spiltan Aktiefond Investmentbolag",
                "SE0000000102",
                TransactionType.SALJ,
                10,
                31.0,
                412.5,
                SwitchFieldUi(TransactionType.SALJ, persistentListOf(none, standalone), selected = none)
            ),
            row(
                "Länsförsäkringar Global Indexnära",
                "SE0000000103",
                TransactionType.KOP,
                13,
                41.9649,
                438.46,
                SwitchFieldUi(TransactionType.KOP, persistentListOf(none, fromSaleRow), selected = fromSaleRow)
            )
        )
    )

    @Composable
    private fun Review(state: ImportOrdersUiState) = ImportOrdersReview(
        state = state,
        onFundOverride = { _, _ -> },
        onIncludedChange = { _, _ -> },
        onDateChange = { _, _ -> },
        onSharesTextChange = { _, _ -> },
        onPriceTextChange = { _, _ -> },
        onSwitchSelect = { _, _ -> },
        onImport = {}
    )

    @Test
    fun `varje rad har sin bytesfraga och ett kop kan avsluta bytet saljraden ovan startar`() {
        composeRule.setContent { FonderTheme { Review(state) } }

        composeRule.onNodeWithText(
            "Säljen importeras före köpen, så ett köp kan höra till ett byte som ett sälj i samma import startar."
        ).assertIsDisplayed()
        composeRule.onNode(hasText("Byte") and hasText("Byte 1 · Sälj Swedbank Robur Ny Teknik → Köp Länsförsäkringar Global Indexnära"))
            .assertExists()
        composeRule.onNodeWithText("Importera 3 transaktioner").assertIsEnabled()
    }

    @Test
    fun `en obesvarad fraga slacker importen`() {
        val unanswered = state.copy(
            rows = state.rows.mapIndexed { index, row ->
                if (index == 2) row.copy(switchField = row.switchField!!.copy(selected = null)) else row
            }.toImmutableList()
        )
        composeRule.setContent { FonderTheme { Review(unanswered) } }

        composeRule.onNodeWithText("Importera 3 transaktioner").assertIsNotEnabled()
    }

    @Config(qualifiers = "w390dp-h2400dp-xxhdpi")
    @Test
    fun `skärmdump granskning med bytesväljare`() = composeRule.captureLightAndDark("ImportOrders_review") {
        Review(state)
    }
}
