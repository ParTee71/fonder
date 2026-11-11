package se.partee71.fonder.ui.beslut

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.SuggestionDismissalRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.usecase.CachedRiskBaseSource
import se.partee71.fonder.domain.usecase.FacitSummary
import se.partee71.fonder.domain.usecase.PendingDecisions
import se.partee71.fonder.domain.usecase.PendingDecisionsSource
import se.partee71.fonder.domain.usecase.PortfolioRiskSource
import se.partee71.fonder.domain.usecase.RecordTransaction
import se.partee71.fonder.domain.usecase.RiskSummary
import se.partee71.fonder.domain.usecase.SwitchEffectCalc
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.domain.usecase.SwitchRow
import se.partee71.fonder.domain.usecase.SwitchRows
import se.partee71.fonder.ui.common.RetrySignal
import se.partee71.fonder.ui.common.catchAsListError
import se.partee71.fonder.ui.common.errorOrNull
import se.partee71.fonder.ui.common.restartOn
import se.partee71.fonder.ui.components.FundFacts
import se.partee71.fonder.worker.BackgroundWork
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import se.partee71.ui.common.ListUiState
import se.partee71.ui.common.UiError
import se.partee71.ui.common.toListUiState

@Immutable
data class BeslutUiState(
    /** Listan "Byten" (BYT-4) — laddning, tomt, fel eller raderna i [SwitchRows.active] (NFR-6). */
    val list: ListUiState<SwitchRow> = ListUiState.Loading,
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
    /**
     * Riskkortet överst (HEM-7, HEM-17, issue #240) — samma [RiskSummary] som Översiktens, räknat ur
     * cachen ([CachedRiskBaseSource]) med den visade planen lagd på. Null utan profil; då visas
     * "Sätt ett mål" i stället.
     */
    val riskSummary: RiskSummary? = null,
    /** Infokortet som är öppet (BYT-5), null när inget är öppet. */
    val info: SuggestionInfo? = null,
    /** Kurserna hämtas — de pågående bytenas ledare räknas ur kurscachen (NAV-6). */
    val pricesWorking: Boolean = false,
    /** Bytesplanen skannas — förslagens väntesnurra (NAV-6). */
    val switchPlanWorking: Boolean = false,
    /** Sätts när en bevakning just startats, så skärmen kan navigera dit. Kvitteras med [BeslutViewModel.onSwitchWatchOpened]. */
    val startedSwitchWatchId: Long? = null
) {
    /** Listan "Byten" (BYT-4) — samma [SwitchRows] som "Att besluta" (HEM-13) och flikmärket räknar på. */
    val switches: SwitchRows get() = decisions.switches

    val loading: Boolean get() = list is ListUiState.Loading

    /** Inga byten väntar — listan är tom; rubrikraden, omräkningen, den hopfällda sektionen och Facit-raden står kvar. */
    val isEmpty: Boolean get() = list is ListUiState.Empty

    /** Läsfelet som visas i stället för listan (NFR-6); rubrikraden och Facit-raden står kvar. */
    val error: UiError? get() = list.errorOrNull()

    /**
     * Sant när en omräkning av bytesplanen (HEM-8, issue #88) över huvud taget kan ge något:
     * riskprofil satt (SET-3) **och** kontotyp ISK/KF (SET-4). Styr dra för att uppdatera (UI-11):
     * utan gaten finns ingen gest, bara en text om vad som saknas — en gest som lovar något SET-4
     * ändå vägrar infria är värre än ingen.
     */
    val canRecomputeSwitchPlan: Boolean get() = riskProfile != null && accountType == AccountType.ISK_KF

    /** Effekten per nivå av ett planbyte ([SwitchEffectCalc], kumulativt) — null för avgiftsbyten och steg som inte går att räkna. */
    fun effectFor(suggestion: SwitchPlanResolver.Suggestion): SwitchEffectCalc.StepEffect? = riskSummary?.stepByRecordId?.get(suggestion.recordId)

    /**
     * Listans väntesnurra (NAV-6): de sålda bytenas ledare vilar på kurserna, förslagen på
     * bytesplanens skanning — en lista, en rubrik, en snurra för båda.
     */
    val switchesWorking: Boolean get() = loading || pricesWorking || switchPlanWorking

    /** Tillståndet med [decisions] — listan och besluten ur samma rader, så de aldrig kan skilja sig. */
    fun withDecisions(decisions: PendingDecisions): BeslutUiState = copy(list = decisions.switches.active.toListUiState(), decisions = decisions)
}

/**
 * Infokortet för ett förslag (BYT-5, issue #240): den föreslagna fondens fakta och upp till
 * [MAX_ALTERNATIVES] andra fonder. För ett planbyte är alternativen köpkandidaterna som sparades med
 * förslaget på samma risknivå ([se.partee71.fonder.domain.model.SuggestionRecord.candidates]); för
 * ett billigare alternativ (ANA-9) de andra fonderna jämförelsen visade
 * ([se.partee71.fonder.domain.model.FundMetadata.shownAlternativeIsins]). Tom lista = inga hittades
 * (eller förslaget spelades in före 0.85.0) — det sägs ut i kortet.
 */
@Immutable
data class SuggestionInfo(
    val row: SwitchRow.Suggested,
    val proposed: FundFacts,
    val alternatives: List<FundFacts>
) {
    companion object {
        const val MAX_ALTERNATIVES = 3
    }
}

/** Användarens handlingar på Beslut, till [BeslutViewModel.onEvent]. */
sealed interface BeslutEvent {
    /** Avstår från ett förslag (HEM-14). */
    data class DismissSuggestion(val suggestion: SwitchPlanResolver.Suggestion) : BeslutEvent

    /** Ångrar ett avstående (HEM-14). */
    data class UndoDismissal(val suggestion: SwitchPlanResolver.Suggestion) : BeslutEvent

    /** Startar en bevakning ur ett kvitterat förslag (ANA-12). */
    data class StartSwitchWatch(val suggestion: SwitchPlanResolver.Suggestion) : BeslutEvent

    /** "Försök igen" efter ett läsfel (NFR-6). */
    data object Retry : BeslutEvent

    /** Kvitterar navigeringen till en nystartad bevakning. */
    data object SwitchWatchOpened : BeslutEvent

    /** Dra för att uppdatera (UI-11): räknar om bytesplanen (HEM-8) när gaten tillåter. */
    data object Refresh : BeslutEvent

    /** Öppnar infokortet för ett förslag (BYT-5). */
    data class ShowInfo(val row: SwitchRow.Suggested) : BeslutEvent

    /** Stänger infokortet. */
    data object HideInfo : BeslutEvent
}

/**
 * Fliken **Beslut** (NAV-9) — listan "Byten" (BYT-4): sålda byten (ANA-12), bytesplanen (HEM-8) och
 * de billigare alternativen (ANA-9), ett byte per rad, med Registrera säljet och Avstå (HEM-14) — Registrera säljet navigerar till
 * säljformuläret ([se.partee71.fonder.ui.navigation.TransactionFormKey.follow]) och skriver
 * ingenting här — mot riskprofilen och
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
    private val recordTransaction: RecordTransaction,
    private val suggestionDismissalRepository: SuggestionDismissalRepository,
    private val fundPriceRefreshScheduler: FundPriceRefreshScheduler,
    cachedRiskBaseSource: CachedRiskBaseSource,
    private val suggestionRecordRepository: SuggestionRecordRepository,
    private val fundMetadataRepository: FundMetadataRepository
) : ViewModel() {

    /** Läsfelets "Försök igen" (NFR-6) – se [retry]. */
    private val retrySignal = RetrySignal()

    /** Se [BeslutUiState.startedSwitchWatchId] — engångshändelse, inte ett bestående tillstånd. */
    private val startedSwitchWatchId = MutableStateFlow<Long?>(null)

    /** Det öppna infokortet (BYT-5) — läst ur cachen när det öppnas, se [showInfo]. */
    private val info = MutableStateFlow<SuggestionInfo?>(null)
    private var infoJob: Job? = null

    val uiState: StateFlow<BeslutUiState> = combine(
        pendingDecisionsSource.observe(),
        preferencesRepository.riskProfile,
        preferencesRepository.accountType,
        facitSummary.observe(),
        startedSwitchWatchId
    ) { decisions, riskProfile, accountType, facit, startedId ->
        BeslutUiState(
            riskProfile = riskProfile,
            accountType = accountType,
            followedCount = facit.followedCount,
            followedExcessKr = facit.followedExcessKr,
            startedSwitchWatchId = startedId
        ).withDecisions(decisions)
    }.combine(cachedRiskBaseSource.observe().onStart { emit(null) }.catch { emit(null) }) { state, base ->
        // Riskkortet (HEM-17) med den visade planen lagd på — samma räkning som Översiktens kort.
        // `onStart`: listan ska inte vänta på portföljen. `catch`: ett läsfel i kortets underlag får
        // inte ta listan med sig — då visas bara inget kort.
        state.copy(riskSummary = base?.let { PortfolioRiskSource.summary(it, state.decisions.switchPlan) })
    }.combine(info) { state, openInfo ->
        // Kortet följer raden: räknas planen om medan det är öppet visas den aktuella raden, och
        // finns förslaget inte längre stängs kortet — "Jag har sålt" ska aldrig gälla ett gammalt förslag.
        val current = openInfo?.let { open -> state.switches.active.firstOrNull { it.key == open.row.key } as? SwitchRow.Suggested }
        state.copy(info = current?.let { openInfo.copy(row = it) })
    }.catchAsListError { BeslutUiState(list = it) }.restartOn(retrySignal).combine(fundPriceRefreshScheduler.observeRunningWork()) { state, running ->
        // Eget led: körstatusen kommer från WorkManager och ska inte kunna räkna om besluten.
        state.copy(
            pricesWorking = BackgroundWork.PRICE_REFRESH in running,
            switchPlanWorking = BackgroundWork.SWITCH_PLAN_SCAN in running
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BeslutUiState()
    )

    fun onEvent(event: BeslutEvent) {
        when (event) {
            is BeslutEvent.DismissSuggestion -> dismissSuggestion(event.suggestion)
            is BeslutEvent.UndoDismissal -> undoDismissal(event.suggestion)
            is BeslutEvent.StartSwitchWatch -> startSwitchWatch(event.suggestion)
            BeslutEvent.Retry -> retry()
            BeslutEvent.SwitchWatchOpened -> onSwitchWatchOpened()
            BeslutEvent.Refresh -> refresh()
            is BeslutEvent.ShowInfo -> showInfo(event.row)
            BeslutEvent.HideInfo -> hideInfo()
        }
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
    private fun dismissSuggestion(suggestion: SwitchPlanResolver.Suggestion) {
        viewModelScope.launch {
            suggestionDismissalRepository.dismiss(suggestion.sellIsin, suggestion.buyIsin, suggestion.kind)
            if (suggestion.kind == SuggestionKind.RISK_PLAN) fundPriceRefreshScheduler.triggerSwitchPlanScan()
        }
    }

    /** Ångrar ett avstående (HEM-14) — förslaget visas igen, och planen räknas om med paret tillbaka i kandidatmängden. */
    private fun undoDismissal(suggestion: SwitchPlanResolver.Suggestion) {
        viewModelScope.launch {
            suggestionDismissalRepository.undoDismissal(suggestion.sellIsin, suggestion.buyIsin, suggestion.kind)
            if (suggestion.kind == SuggestionKind.RISK_PLAN) fundPriceRefreshScheduler.triggerSwitchPlanScan()
        }
    }

    /** Startar en bevakning ur ett kvitterat förslag (ANA-12) — se [RecordTransaction.startWatchFromSuggestion]. */
    private fun startSwitchWatch(suggestion: SwitchPlanResolver.Suggestion) {
        viewModelScope.launch {
            recordTransaction.startWatchFromSuggestion(suggestion)?.let { startedSwitchWatchId.value = it }
        }
    }

    /** "Försök igen" efter ett läsfel (NFR-6): läser om förslagen, profilen och facit från början. */
    private fun retry() {
        retrySignal.retry()
    }

    /** Kvitterar navigeringen till en nystartad bevakning, så den inte upprepas vid nästa emission. */
    private fun onSwitchWatchOpened() {
        startedSwitchWatchId.value = null
    }

    /**
     * Dra för att uppdatera (UI-11): ber om en omräkning av bytesplanen (HEM-8, issue #88), samma
     * väg som Facit. Bara med gaten ([BeslutUiState.canRecomputeSwitchPlan]) — skärmen erbjuder
     * ingen gest utan den, men vakten står här också så att en händelse aldrig kan starta en
     * skanning SET-4 ändå vägrar. Kör inte själv: skanningen hör hemma i WorkManager, med
     * nätverksvillkor och koalescering, precis som backstopens egen körning.
     */
    private fun refresh() {
        if (uiState.value.canRecomputeSwitchPlan) fundPriceRefreshScheduler.triggerSwitchPlanScan(force = true)
    }

    /**
     * Öppnar infokortet (BYT-5). Läser **bara cachen**, som resten av Beslut: förslagets sparade
     * kandidater och fondernas cachade metadata. Saknas den föreslagna fondens metadata visas det
     * förslaget självt vet (namn och risknivå) och resten som okänt (ANA-4-principen).
     */
    private fun showInfo(row: SwitchRow.Suggested) {
        infoJob?.cancel()
        infoJob = viewModelScope.launch {
            info.value = try {
                loadInfo(row)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ett läsfel i cachen ger kortet med det förslaget självt vet, aldrig en krasch.
                SuggestionInfo(row, row.fallbackFacts(), emptyList())
            }
        }
    }

    private fun hideInfo() {
        infoJob?.cancel()
        info.value = null
    }

    private suspend fun loadInfo(row: SwitchRow.Suggested): SuggestionInfo {
        val suggestion = row.suggestion
        val metadata = fundMetadataRepository.cachedMetadataFor(listOf(suggestion.sellIsin, suggestion.buyIsin))
        val proposed = metadata[suggestion.buyIsin]?.let(FundFacts::of) ?: row.fallbackFacts()
        val alternatives = if (row.cheaper == null) {
            suggestionRecordRepository.observeByIds(setOf(suggestion.recordId)).first()
                .firstOrNull { it.id == suggestion.recordId }
                ?.candidates.orEmpty()
                .filter { it.isin != suggestion.buyIsin }
                .sortedBy { it.position }
                .take(SuggestionInfo.MAX_ALTERNATIVES)
                .map(FundFacts::of)
        } else {
            val shown = metadata[suggestion.sellIsin]?.shownAlternativeIsins.orEmpty()
                .filter { it != suggestion.buyIsin && it != suggestion.sellIsin }
                .distinct()
                .take(SuggestionInfo.MAX_ALTERNATIVES)
            val shownMetadata = fundMetadataRepository.cachedMetadataFor(shown)
            shown.mapNotNull { shownMetadata[it] }.map(FundFacts::of)
        }
        return SuggestionInfo(row, proposed, alternatives)
    }
}

/** Det förslaget självt vet om köpfonden: namnet, och risknivån för ett planbyte. */
private fun SwitchRow.Suggested.fallbackFacts(): FundFacts = FundFacts(name = suggestion.buyFundName, risk = suggestion.toLevel.takeIf { cheaper == null })
