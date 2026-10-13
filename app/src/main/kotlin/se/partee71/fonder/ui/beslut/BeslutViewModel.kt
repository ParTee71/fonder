package se.partee71.fonder.ui.beslut

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.SuggestionDismissalRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.usecase.FacitSummary
import se.partee71.fonder.domain.usecase.PendingDecisions
import se.partee71.fonder.domain.usecase.PendingDecisionsSource
import se.partee71.fonder.domain.usecase.RecordTransaction
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.worker.BackgroundWork
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import javax.inject.Inject

data class BeslutUiState(
    val loading: Boolean = true,
    /** Riskprofilen planen räknas mot (SET-3) — rubrikraden, null utan sparad profil. */
    val riskProfile: RiskProfile? = null,
    /** Kontotypen (SET-4) — rubrikraden och gaten för bytesplanen. */
    val accountType: AccountType? = null,
    val decisions: PendingDecisions = PendingDecisions(),
    /** Förslag användaren markerat som genomförda, över hela facit-historiken (SET-5) — "Hur gick det?"-raden. */
    val followedCount: Int = 0,
    /**
     * Meravkastningen i kronor för de genomförda förslagen — [se.partee71.fonder.domain.usecase.FacitSnapshot.followedExcessKr], samma
     * tal som Facits två "Genomförda"-rader tillsammans. Null utan mätbart utfall; raden visar då
     * bara antalet.
     */
    val followedExcessKr: Double? = null,
    /** Sant medan en bakgrundskörning pågår — omräkningsknappen är då släckt, samma signal som chromets indikator (NAV-6). */
    val backgroundWorkRunning: Boolean = false,
    /** Kurserna hämtas — de pågående bytenas ledare räknas ur kurscachen (NAV-6). */
    val pricesWorking: Boolean = false,
    /** Bytesplanen skannas — förslagens väntesnurra (NAV-6). */
    val switchPlanWorking: Boolean = false,
    /** Sätts när en bevakning just startats, så skärmen kan navigera dit. Kvitteras med [BeslutViewModel.onSwitchWatchOpened]. */
    val startedSwitchWatchId: Long? = null,
) {
    val isEmpty: Boolean get() = !loading && decisions.isEmpty

    /**
     * Sant när en omräkning av bytesplanen (HEM-8, issue #88) över huvud taget kan ge något:
     * riskprofil satt (SET-3) **och** kontotyp ISK/KF (SET-4). Utan gaten hade knappen lovat
     * något SET-4 ändå vägrar infria.
     */
    val canRecomputeSwitchPlan: Boolean get() = riskProfile != null && accountType == AccountType.ISK_KF

    // Per avsnitt, inte ett gemensamt "något kör" (NAV-6): ledaren i ett pågående byte vilar på
    // kurserna, förslagen på bytesplanens skanning.
    val switchWatchWorking: Boolean get() = loading || pricesWorking
    val suggestionsWorking: Boolean get() = loading || switchPlanWorking
}

/**
 * Fliken **Beslut** (NAV-9) — det som väntar på användaren: pågående byten (ANA-12), bytesplanen
 * (HEM-8) och de billigare alternativen (ANA-9) med Följ och Avstå (HEM-14), mot riskprofilen och
 * kontotypen, plus vägen till Facit (SET-5) med de följda rådens resultat ur [FacitSummary] — samma
 * källa som Facit-skärmen, så facit räknas på ett ställe.
 *
 * Förslagen och bevakningarna kommer ur [PendingDecisionsSource], samma källa som Översiktens
 * "Att besluta"-rad och flikradens märke läser — antalet räknas därför på ett ställe. Läser bara
 * cachen; ingenting hämtas när fliken öppnas.
 */
@HiltViewModel
class BeslutViewModel @Inject constructor(
    pendingDecisionsSource: PendingDecisionsSource,
    facitSummary: FacitSummary,
    preferencesRepository: PreferencesRepository,
    private val suggestionRecordRepository: SuggestionRecordRepository,
    private val recordTransaction: RecordTransaction,
    private val suggestionDismissalRepository: SuggestionDismissalRepository,
    private val fundPriceRefreshScheduler: FundPriceRefreshScheduler,
) : ViewModel() {

    /** Se [BeslutUiState.startedSwitchWatchId] — engångshändelse, inte ett bestående tillstånd. */
    private val startedSwitchWatchId = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<BeslutUiState> = combine(
        pendingDecisionsSource.observe(),
        preferencesRepository.riskProfile,
        preferencesRepository.accountType,
        facitSummary.observe(),
        startedSwitchWatchId,
    ) { decisions, riskProfile, accountType, facit, startedId ->
        BeslutUiState(
            loading = false,
            riskProfile = riskProfile,
            accountType = accountType,
            decisions = decisions,
            followedCount = facit.followedCount,
            followedExcessKr = facit.followedExcessKr,
            startedSwitchWatchId = startedId,
        )
    }.combine(fundPriceRefreshScheduler.observeRunningWork()) { state, running ->
        // Eget led: körstatusen kommer från WorkManager och ska inte kunna räkna om besluten.
        state.copy(
            backgroundWorkRunning = running.isNotEmpty(),
            pricesWorking = BackgroundWork.PRICE_REFRESH in running,
            switchPlanWorking = BackgroundWork.SWITCH_PLAN_SCAN in running,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BeslutUiState(),
    )

    /**
     * Markerar ett förslag som genomfört (HEM-8/SET-5). Skriver bara flaggan — inget byte utförs,
     * appen rör aldrig användarens innehav. Facit läser den för att mäta följda råd separat.
     */
    fun setSwitchFollowed(recordId: Long, followed: Boolean) {
        viewModelScope.launch { suggestionRecordRepository.setFollowed(recordId, followed) }
    }

    /**
     * Avstår från ett förslag (HEM-14) — ett byte i planen eller ett billigare alternativ (ANA-9),
     * efter förslagets egen sort: fondparet döljs på Beslut och i Fonddetalj och räknas inte i märket
     * eller i "Att besluta" så länge avståendet gäller. Förslaget självt rörs inte — det är
     * fortfarande ett givet råd i facit (SET-5).
     *
     * Ett avstått **planbyte** räknar om planen (HEM-8): paret tas ur kandidatmängden, så nästa
     * steg inte längre bygger på ett byte som inte ska göras och listan blir 1..n utan lucka. Ett
     * avgiftsbyte (ANA-9) ingår inte i planen och utlöser ingen omräkning.
     */
    fun dismissSuggestion(suggestion: SwitchPlanResolver.Suggestion) {
        viewModelScope.launch {
            suggestionDismissalRepository.dismiss(suggestion.sellIsin, suggestion.buyIsin, suggestion.kind)
            if (suggestion.kind == SuggestionKind.RISK_PLAN) fundPriceRefreshScheduler.triggerSwitchPlanScan()
        }
    }

    /** Ångrar ett avstående (HEM-14) — förslaget visas igen, och planen räknas om med paret tillbaka i kandidatmängden. */
    fun undoDismissal(suggestion: SwitchPlanResolver.Suggestion) {
        viewModelScope.launch {
            suggestionDismissalRepository.undoDismissal(suggestion.sellIsin, suggestion.buyIsin, suggestion.kind)
            if (suggestion.kind == SuggestionKind.RISK_PLAN) fundPriceRefreshScheduler.triggerSwitchPlanScan()
        }
    }

    /** Startar en bevakning ur ett kvitterat förslag (ANA-12) — se [RecordTransaction.startWatchFromSuggestion]. */
    fun startSwitchWatch(suggestion: SwitchPlanResolver.Suggestion) {
        viewModelScope.launch {
            recordTransaction.startWatchFromSuggestion(suggestion)?.let { startedSwitchWatchId.value = it }
        }
    }

    /** Kvitterar navigeringen till en nystartad bevakning, så den inte upprepas vid nästa emission. */
    fun onSwitchWatchOpened() {
        startedSwitchWatchId.value = null
    }

    /**
     * Ber om en omräkning av bytesplanen (HEM-8, issue #88). Kör inte själv: skanningen hör hemma
     * i WorkManager, med nätverksvillkor och koalescering, precis som backstopens egen körning.
     */
    fun recomputeSwitchPlan() {
        fundPriceRefreshScheduler.triggerSwitchPlanScan(force = true)
    }
}
