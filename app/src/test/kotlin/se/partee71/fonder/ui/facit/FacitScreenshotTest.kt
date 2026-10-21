package se.partee71.fonder.ui.facit

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.usecase.FacitRad
import se.partee71.fonder.domain.usecase.FacitSummary
import se.partee71.fonder.domain.usecase.SwitchOutcomeCalc
import se.partee71.fonder.testing.captureLightAndDark

/**
 * Skärmdump ljust + mörkt av Facit (SET-5): summeringskortet och raderna som utfällbara kort, en av dem
 * kvitterad som genomförd. Påhittade fonder och kurser; vaktar kortens kontur och radernas luft (UI-13).
 */
@RunWith(RobolectricTestRunner::class)
class FacitScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** En rad vars säljfond gått [sellNow] % och köpfond [buyNow] % sedan förslaget (kurs 100 då). */
    private fun outcomeRow(id: Long, names: Pair<String, String>, sellNow: Double, buyNow: Double, followed: Boolean, kind: SuggestionKind) = SuggestionRecord(
        id = id, suggestedAtEpochDay = 20_300, planIndex = id.toInt() - 1, sellIsin = "SE_S$id", buyIsin = "SE_K$id",
        sellNavAtSuggestion = 100.0, buyNavAtSuggestion = 100.0, switchValueKr = 25_000.0 * id, followed = followed, kind = kind
    ).let { record ->
        FacitRad(
            recordId = id, kind = kind, planIndex = record.planIndex, suggestedAtEpochDay = record.suggestedAtEpochDay,
            sellFundName = names.first, buyFundName = names.second, switchValueKr = record.switchValueKr, followed = followed,
            outcome = SwitchOutcomeCalc.evaluate(record, sellNavNow = 100.0 + sellNow, buyNavNow = 100.0 + buyNow)
        )
    }

    private val state = FacitUiState().withFacit(
        FacitSummary.summarize(
            listOf(
                outcomeRow(1, "Tillväxtmarknad Bred" to "Global Indexfond A", sellNow = -2.0, buyNow = 4.5, followed = true, kind = SuggestionKind.RISK_PLAN),
                outcomeRow(2, "Sverige Småbolag" to "Småbolag Index", sellNow = 3.0, buyNow = 3.4, followed = false, kind = SuggestionKind.FEE)
            )
        )
    )

    @Test
    fun `skärmdump av summeringen och raderna`() = composeRule.captureLightAndDark("FacitScreen_rader") {
        FacitContent(state = state)
    }
}
