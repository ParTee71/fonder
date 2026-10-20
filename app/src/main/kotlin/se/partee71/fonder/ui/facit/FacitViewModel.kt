package se.partee71.fonder.ui.facit

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
import se.partee71.fonder.ui.common.RetrySignal
import se.partee71.fonder.ui.common.catchAsListError
import se.partee71.fonder.ui.common.errorOrNull
import se.partee71.fonder.ui.common.itemsOrEmpty
import se.partee71.fonder.ui.common.restartOn
import se.partee71.fonder.worker.BackgroundWork
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import se.partee71.ui.common.ListUiState
import se.partee71.ui.common.UiError
import se.partee71.ui.common.toListUiState

@Immutable
data class FacitUiState(
    /** Facits rader — laddning, tomt, fel eller rader (NFR-6); samma rader som i [facit]. */
    val list: ListUiState<FacitRad> = ListUiState.Loading,
    /** Raderna och summeringarna, räknade av [FacitSummary] — se [FacitSnapshot]. */
    val facit: FacitSnapshot = FacitSnapshot(),
    /**
     * Bytesplanen skannas just nu ([BackgroundWork.SWITCH_PLAN_SCAN]) — det är den körningen som
     * spelar in nya rader och fyller på utfallens NAV, alltså precis den data kortet redovisar.
     */
    val switchPlanWorking: Boolean = false
) {
    val loading: Boolean get() = list is ListUiState.Loading
    val rows: List<FacitRad> get() = list.itemsOrEmpty()

    /** Läsfelet som visas i stället för facit (NFR-6). */
    val error: UiError? get() = list.errorOrNull()

    val planAllSummary: SwitchOutcomeCalc.Summary get() = facit.planAllSummary
    val planFollowedSummary: SwitchOutcomeCalc.Summary get() = facit.planFollowedSummary
    val feeAllSummary: SwitchOutcomeCalc.Summary get() = facit.feeAllSummary
    val feeFollowedSummary: SwitchOutcomeCalc.Summary get() = facit.feeFollowedSummary
    val byPlanIndex: List<SwitchOutcomeCalc.PlanIndexSummary> get() = facit.byPlanIndex

    val isEmpty: Boolean get() = list is ListUiState.Empty

    /** Summeringskortets väntesnurra (NAV-6) — härlett här, så kopplingen är enhetstestbar. */
    val summaryWorking: Boolean get() = loading || switchPlanWorking

    /** Tillståndet med [snapshot] — listan och summeringarna ur samma rader, så de aldrig kan skilja sig. */
    fun withFacit(snapshot: FacitSnapshot): FacitUiState = copy(list = snapshot.rows.toListUiState(), facit = snapshot)
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
    private val fundPriceRefreshScheduler: FundPriceRefreshScheduler
) : ViewModel() {

    /** Läsfelets "Försök igen" (NFR-6) – se [retry]. */
    private val retrySignal = RetrySignal()

    val uiState: StateFlow<FacitUiState> =
        facitSummary.observe().map { snapshot ->
            FacitUiState().withFacit(snapshot)
        }.catchAsListError { FacitUiState(list = it) }.restartOn(retrySignal).combine(fundPriceRefreshScheduler.observeRunningWork()) { state, running ->
            // Eget led, inte en gren i combinen ovan: körstatusen kommer från WorkManager och
            // ska inte kunna räkna om facit bara för att ett jobb startade (samma gräns som
            // HemViewModel drar).
            state.copy(switchPlanWorking = BackgroundWork.SWITCH_PLAN_SCAN in running)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FacitUiState()
        )

    /**
     * Dra ned för att uppdatera (UI-11) — startar bytesplansskanningen, den körning som spelar
     * in nya rader och fyller på utfallens NAV. Facit läser bara cachen; utan skanningen finns
     * det ingenting nytt att räkna om. Eget unikt arbetsnamn med `KEEP`, så upprepade
     * dragningar aldrig ger två parallella (dyra) skanningar.
     */
    fun refresh() {
        fundPriceRefreshScheduler.triggerSwitchPlanScan(force = true)
    }

    /** "Försök igen" efter ett läsfel (NFR-6): läser om facit ur cachen från början. */
    fun retry() {
        retrySignal.retry()
    }

    /** Markerar ett förslag som genomfört eller ej (HEM-8/SET-5) — flödet ovan speglar ändringen. */
    fun setFollowed(recordId: Long, followed: Boolean) {
        viewModelScope.launch { suggestionRecordRepository.setFollowed(recordId, followed) }
    }
}
