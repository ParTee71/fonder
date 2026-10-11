package se.partee71.fonder.ui.facit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.domain.usecase.FacitRad
import se.partee71.fonder.domain.usecase.FacitSnapshot
import se.partee71.fonder.domain.usecase.FacitSummary
import se.partee71.fonder.domain.usecase.SwitchOutcomeCalc
import se.partee71.fonder.worker.BackgroundWork
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import javax.inject.Inject

data class FacitUiState(
    val loading: Boolean = true,
    /** Raderna och summeringarna, räknade av [FacitSummary] — se [FacitSnapshot]. */
    val facit: FacitSnapshot = FacitSnapshot(),
    /**
     * Bytesplanen skannas just nu ([BackgroundWork.SWITCH_PLAN_SCAN]) — det är den körningen som
     * spelar in nya rader och fyller på utfallens NAV, alltså precis den data kortet redovisar.
     */
    val switchPlanWorking: Boolean = false,
) {
    val rows: List<FacitRad> get() = facit.rows
    val planAllSummary: SwitchOutcomeCalc.Summary get() = facit.planAllSummary
    val planFollowedSummary: SwitchOutcomeCalc.Summary get() = facit.planFollowedSummary
    val feeAllSummary: SwitchOutcomeCalc.Summary get() = facit.feeAllSummary
    val feeFollowedSummary: SwitchOutcomeCalc.Summary get() = facit.feeFollowedSummary
    val byPlanIndex: List<SwitchOutcomeCalc.PlanIndexSummary> get() = facit.byPlanIndex

    val isEmpty: Boolean get() = !loading && rows.isEmpty()

    /** Summeringskortets väntesnurra (NAV-6) — härlett här, så kopplingen är enhetstestbar. */
    val summaryWorking: Boolean get() = loading || switchPlanWorking
}

/**
 * Facit för bytesplanen (SET-5, issue #80) — redovisar utfallet av HEM-8:s inspelade förslag
 * mot att ha behållit innehavet, se [SwitchOutcomeCalc].
 *
 * **Läser bara cachen**, via [FacitSummary] — samma källa som Beslut-flikens rad "Hur gick det?"
 * (NAV-9), så facit räknas på ett ställe. Att öppna en redovisningsvy ska aldrig kosta en burst
 * av nätverksanrop — samma avgränsning som HEM-5 och POR-9 redan gör.
 */
@HiltViewModel
class FacitViewModel @Inject constructor(
    facitSummary: FacitSummary,
    private val suggestionRecordRepository: SuggestionRecordRepository,
    private val fundPriceRefreshScheduler: FundPriceRefreshScheduler,
) : ViewModel() {

    val uiState: StateFlow<FacitUiState> =
        facitSummary.observe().map { snapshot ->
            FacitUiState(loading = false, facit = snapshot)
        }.combine(fundPriceRefreshScheduler.observeRunningWork()) { state, running ->
            // Eget led, inte en gren i combinen ovan: körstatusen kommer från WorkManager och
            // ska inte kunna räkna om facit bara för att ett jobb startade (samma gräns som
            // HemViewModel drar).
            state.copy(switchPlanWorking = BackgroundWork.SWITCH_PLAN_SCAN in running)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FacitUiState(),
        )

    /**
     * Dra ned för att uppdatera (UI-11) — startar bytesplansskanningen, den körning som spelar
     * in nya rader och fyller på utfallens NAV. Facit läser bara cachen; utan skanningen finns
     * det ingenting nytt att räkna om. Eget unikt arbetsnamn med `KEEP`, så upprepade
     * dragningar aldrig ger två parallella (dyra) skanningar.
     */
    fun refresh() {
        fundPriceRefreshScheduler.triggerSwitchPlanScan()
    }

    /** Markerar ett förslag som genomfört eller ej (HEM-8/SET-5) — flödet ovan speglar ändringen. */
    fun setFollowed(recordId: Long, followed: Boolean) {
        viewModelScope.launch { suggestionRecordRepository.setFollowed(recordId, followed) }
    }
}
