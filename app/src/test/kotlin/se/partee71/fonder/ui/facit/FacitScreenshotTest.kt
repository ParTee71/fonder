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
 * Skärmdump ljust + mörkt av Facit (SET-5): summeringskortet med träffandel, horisontband och plats i planen
 * (issue #197) och raderna som utfällbara kort med mätdagen, en av dem kvitterad som genomförd och en ej
 * utvärderad. Påhittade fonder och kurser; vaktar kortens kontur och radernas luft (UI-13).
 */
@RunWith(RobolectricTestRunner::class)
class FacitScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * En rad vars säljfond gått [sellNow] % och köpfond [buyNow] % (kurs 100 vid förslaget), mätt [days] dagar
     * efter förslaget på samma dag för båda — eller ej utvärderad när [days] är null (issue #197).
     */
    private fun outcomeRow(
        id: Long,
        names: Pair<String, String>,
        sellNow: Double,
        buyNow: Double,
        followed: Boolean,
        kind: SuggestionKind,
        days: Long? = 30,
        planIndex: Int = 0
    ) = SuggestionRecord(
        id = id, suggestedAtEpochDay = 20_300, planIndex = planIndex, sellIsin = "SE_S$id", buyIsin = "SE_K$id",
        sellNavAtSuggestion = 100.0, buyNavAtSuggestion = 100.0, switchValueKr = 25_000.0 * id, followed = followed, kind = kind,
        navEpochDay = 20_300
    ).let { record ->
        FacitRad(
            recordId = id, kind = kind, planIndex = record.planIndex, suggestedAtEpochDay = record.suggestedAtEpochDay,
            sellFundName = names.first, buyFundName = names.second, switchValueKr = record.switchValueKr, followed = followed,
            outcome = SwitchOutcomeCalc.evaluate(record, days?.let { SwitchOutcomeCalc.SameDayNav(20_300 + it, 100.0 + sellNow, 100.0 + buyNow) })
        )
    }

    private val state = FacitUiState().withFacit(
        FacitSummary.summarize(
            listOf(
                outcomeRow(1, "Tillväxtmarknad Bred" to "Global Indexfond A", sellNow = -2.0, buyNow = 4.5, followed = true, kind = SuggestionKind.RISK_PLAN, days = 240),
                outcomeRow(2, "Sverige Småbolag" to "Småbolag Index", sellNow = 3.0, buyNow = 3.4, followed = false, kind = SuggestionKind.FEE, days = 21),
                outcomeRow(3, "Europa Aktiv" to "Europa Index", sellNow = 1.2, buyNow = 0.4, followed = false, kind = SuggestionKind.RISK_PLAN, days = 4, planIndex = 1),
                outcomeRow(4, "Räntefond Kort" to "Global Hållbar", sellNow = 0.0, buyNow = 0.0, followed = false, kind = SuggestionKind.RISK_PLAN, days = null, planIndex = 2)
            )
        )
    )

    @Test
    fun `skärmdump av summeringen och raderna`() = composeRule.captureLightAndDark("FacitScreen_rader") {
        FacitContent(state = state)
    }
}
