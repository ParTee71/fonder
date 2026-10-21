package se.partee71.fonder.ui.riskprofil

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.R
import se.partee71.fonder.data.common.AppErrors
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.data.common.suspendRunCatching
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.RiskProfileAnswers
import se.partee71.fonder.domain.model.TimeHorizon
import se.partee71.fonder.domain.usecase.RiskProfileCalc
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import se.partee71.ui.common.EditorEvents
import se.partee71.ui.common.EditorLoader
import se.partee71.ui.common.EditorState
import se.partee71.ui.common.EditorUiState
import se.partee71.ui.common.Validator

/** Andelen (0.0..1.0) uttryckt som ett rundat heltalsprocent för visning/redigering i inmatningsfälten. */
private fun percentTextOf(fraction: Double): String = (fraction * 100).roundToInt().toString()

/**
 * Riskprofilens formulärvärde — det [EditorState] håller: enkätsvaren och de egna andelarna.
 * Skalan ([RiskProfilUiState.availableLevels]) hör inte hit; den läses ur metadatan och är inget
 * användaren skriver.
 */
@Immutable
data class RiskProfileForm(
    val horizon: TimeHorizon? = null,
    val reaction: DownturnReaction? = null,
    val goal: PrimaryGoal? = null,
    /** Icke-null så fort användaren rör en enskild andel — vinner då alltid över enkätens förslag i sin helhet. */
    val manualAllocationText: ImmutableMap<Int, String>? = null
)

/** Enkätens förslag ur [RiskProfileCalc.suggest] — null om inte alla tre frågor är besvarade eller skalan är tom. */
private fun suggestionFor(form: RiskProfileForm, levels: List<Int>): Map<Int, Double>? {
    val horizon = form.horizon ?: return null
    val reaction = form.reaction ?: return null
    val goal = form.goal ?: return null
    return RiskProfileCalc.suggest(RiskProfileAnswers(horizon, reaction, goal), levels)
}

/** Text per tillgänglig nivås inmatningsfält — förslaget (rundat till heltalsprocent) tills ett eget värde skrivits in, annars de egna värdena (saknad nivå defaultar till "0"). */
private fun allocationTextFor(levels: List<Int>, suggested: Map<Int, Double>?, manual: Map<Int, String>?): Map<Int, String> = if (manual != null) {
    levels.associateWith { level -> manual[level] ?: "0" }
} else {
    levels.associateWith { level -> suggested?.get(level)?.let(::percentTextOf) ?: "0" }
}

/** Det som faktiskt går att spara — bara giltigt när summan är (i praktiken) exakt 100 %, annars tom (ingen tyst normalisering). */
private fun effectiveAllocationOf(allocationText: Map<Int, String>): Map<Int, Double> {
    val fractions = allocationText
        .mapValues { (_, text) -> (text.toIntOrNull() ?: 0) / 100.0 }
        .filterValues { it > 0.0 }
    return if (RiskProfileCalc.isCompleteAllocation(fractions)) fractions else emptyMap()
}

/**
 * Valideringen av [RiskProfileForm]: fördelningen måste summera till exakt 100 % på skalan [levels]
 * (läses vid varje validering – skalan kommer efter formuläret). En gång för ViewModel:n och testerna.
 */
internal fun riskProfileValidator(levels: () -> List<Int>) = Validator<RiskProfileForm> { form ->
    val scale = levels()
    val text = allocationTextFor(scale, suggestionFor(form, scale), form.manualAllocationText)
    if (effectiveAllocationOf(text).isEmpty()) mapOf(RISK_PROFILE_ALLOCATION to R.string.riskprofile_allocation_sum_error) else emptyMap()
}

/** Fältnamnen i [EditorState] – andelarna är ett fält, varje fråga ett. */
internal const val RISK_PROFILE_HORIZON = "horizon"
internal const val RISK_PROFILE_REACTION = "reaction"
internal const val RISK_PROFILE_GOAL = "goal"
internal const val RISK_PROFILE_ALLOCATION = "allocation"

/**
 * Det Riskprofilen visar: formulärets tillstånd ([editor], det `EntityEditScreen` läser – laddning,
 * läsfel, sparning, sparfel, ändrat och giltigt) och skalan [availableLevels]. Resten räknas ur dem.
 */
@Immutable
data class RiskProfilUiState(
    val availableLevels: ImmutableList<Int> = persistentListOf(),
    val editor: EditorUiState<RiskProfileForm> = EditorUiState(RiskProfileForm(), loading = true)
) {
    val horizon: TimeHorizon? get() = editor.value.horizon
    val reaction: DownturnReaction? get() = editor.value.reaction
    val goal: PrimaryGoal? get() = editor.value.goal

    /** Enkätens förslag ur [RiskProfileCalc.suggest] — null om inte alla tre frågor är besvarade eller skalan är tom. */
    val suggestedAllocation: Map<Int, Double>? get() = suggestionFor(editor.value, availableLevels)

    /** Text att visa per tillgänglig nivås inmatningsfält — se [allocationTextFor]. */
    val allocationText: Map<Int, String> get() = allocationTextFor(availableLevels, suggestedAllocation, editor.value.manualAllocationText)

    /** Sant efter det första egna ändrade fältet — styr om förslagstexten fortfarande visas. */
    val hasManualEdit: Boolean get() = editor.value.manualAllocationText != null

    val allocationSumPercent: Int get() = allocationText.values.sumOf { it.toIntOrNull() ?: 0 }

    /** Det som faktiskt går att spara — se [effectiveAllocationOf]. */
    val effectiveAllocation: Map<Int, Double> get() = effectiveAllocationOf(allocationText)

    /** Fördelningen summerar till 100 % — summaraden och dess förklaring följer den, inte sparningens läge. */
    val valid: Boolean get() = effectiveAllocation.isNotEmpty()
}

/** Användarens handlingar på Riskprofil, till [RiskProfilViewModel.onEvent]. */
sealed interface RiskProfilEvent {
    /** Enkätsvaret om placeringshorisont. */
    data class HorizonSelected(val horizon: TimeHorizon) : RiskProfilEvent

    /** Enkätsvaret om reaktion på nedgång. */
    data class ReactionSelected(val reaction: DownturnReaction) : RiskProfilEvent

    /** Enkätsvaret om sparmål. */
    data class GoalSelected(val goal: PrimaryGoal) : RiskProfilEvent

    /** Andelen i procent för en risknivå. */
    data class AllocationPercentChanged(val level: Int, val percentText: String) : RiskProfilEvent

    /** Sparar profilen. */
    data object Save : RiskProfilEvent

    /** "Försök igen" efter ett läsfel. */
    data object Retry : RiskProfilEvent
}

/**
 * Riskprofilens enkät + målfördelning (SET-3, issue #68 → målfördelning i issue #71) — en
 * engångsinställning under Inställningar, inte en daglig destination (samma
 * navigeringsmönster som import-skärmarna). Enkäten *föreslår*, användaren äger och sparar den
 * slutgiltiga fördelningen (se [RiskProfileCalc]/[RiskProfile]).
 *
 * Formuläret är android-ui:s [EditorState] med [EditorLoader], och skärmen dess `EntityEditScreen`
 * (NFR-6, UI-15): den sparade profilen läses en gång, ett läsfel visas med "Försök igen" ([retry]) i
 * stället för en tom enkät, Spara är aktiv först när något ändrats och fördelningen går ihop, och
 * sparningen är spärrad mot dubbeltryck. Ramen kvitterar sparat och sparfel via [events].
 */
@HiltViewModel
class RiskProfilViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val fundPriceRefreshScheduler: FundPriceRefreshScheduler
) : ViewModel() {

    private val availableLevels = MutableStateFlow<List<Int>>(emptyList())

    private val editor = EditorState(
        initial = RiskProfileForm(),
        validator = riskProfileValidator { availableLevels.value },
        errors = AppErrors,
        loading = true
    )

    /** Ramens kvittering av sparat och sparfel ([EditorUiState.done], [EditorUiState.failure]). */
    val events: EditorEvents get() = editor

    // Förifyller enkäten och fördelningen med en redan sparad profil så en återbesökt riskprofil
    // visar vad man svarade/valde förra gången, inte en tom enkät — svaren och fördelningen hålls
    // medvetet isär i persistensen (SET-3). Ingen sparad profil är en tom enkät, inget fel.
    private val loader = EditorLoader(editor, viewModelScope, read = { suspendRunCatching { preferences.riskProfile.first()?.toForm() ?: RiskProfileForm() } })

    init {
        viewModelScope.launch {
            availableLevels.value = fundMetadataRepository.knownRiskLevels()
            // Giltigheten vilar på skalan — en fördelning kan bli giltig först när nivåerna är kända.
            editor.revalidate()
        }
    }

    val uiState: StateFlow<RiskProfilUiState> =
        combine(editor.state, availableLevels) { form, levels ->
            RiskProfilUiState(availableLevels = levels.toImmutableList(), editor = form)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RiskProfilUiState()
        )

    fun onEvent(event: RiskProfilEvent) {
        when (event) {
            is RiskProfilEvent.HorizonSelected -> onHorizonSelected(event.horizon)
            is RiskProfilEvent.ReactionSelected -> onReactionSelected(event.reaction)
            is RiskProfilEvent.GoalSelected -> onGoalSelected(event.goal)
            is RiskProfilEvent.AllocationPercentChanged -> onAllocationPercentChanged(event.level, event.percentText)
            RiskProfilEvent.Save -> save()
            RiskProfilEvent.Retry -> retry()
        }
    }

    private fun onHorizonSelected(horizon: TimeHorizon) = editor.update(RISK_PROFILE_HORIZON) { it.copy(horizon = horizon) }
    private fun onReactionSelected(reaction: DownturnReaction) = editor.update(RISK_PROFILE_REACTION) { it.copy(reaction = reaction) }
    private fun onGoalSelected(goal: PrimaryGoal) = editor.update(RISK_PROFILE_GOAL) { it.copy(goal = goal) }

    private fun onAllocationPercentChanged(level: Int, percentText: String) {
        val levels = availableLevels.value
        editor.update(RISK_PROFILE_ALLOCATION) { form ->
            val base = allocationTextFor(levels, suggestionFor(form, levels), form.manualAllocationText)
            form.copy(manualAllocationText = (base + (level to percentText)).toImmutableMap())
        }
    }

    private fun save() {
        viewModelScope.launch {
            // Inställningarna är lokala: ett skrivfel är aldrig "kunde inte nå källan".
            editor.save { form -> suspendRunCatching({ DataError.Unknown }) { write(form) } }
        }
    }

    private suspend fun write(form: RiskProfileForm) {
        val levels = availableLevels.value
        val allocation = effectiveAllocationOf(allocationTextFor(levels, suggestionFor(form, levels), form.manualAllocationText))
        val answers = if (form.horizon != null && form.reaction != null && form.goal != null) {
            RiskProfileAnswers(form.horizon, form.reaction, form.goal)
        } else {
            null
        }
        val previousAllocation = preferences.riskProfile.first()?.effectiveAllocation
        preferences.setRiskProfile(RiskProfile(targetAllocation = allocation, answers = answers))

        // Bytesplanen (HEM-8) mäts mot **målfördelningen** — ändras den är den inspelade
        // planen inaktuell i samma sekund, och utan den här triggern fick man vänta upp till
        // 12 timmar på backstopen (issue #88). Jämförelsen görs mot den sparade fördelningen,
        // inte mot "sparade knappen trycktes": en oförändrad profil ska inte kosta en
        // skanning (källfråga + budgeterad köpbarhetsverifiering per underviktad nivå).
        // Enkätsvaren påverkar inte planen — bara fördelningen gör det.
        if (allocation != previousAllocation) fundPriceRefreshScheduler.triggerSwitchPlanScan()
    }

    /** "Försök igen" efter ett läsfel: läser den sparade profilen på nytt. */
    private fun retry() = loader.retry()
}

/** Den sparade profilen som formulärvärde — svaren och fördelningen (heltalsprocent per nivå). */
private fun RiskProfile.toForm() = RiskProfileForm(
    horizon = answers?.horizon,
    reaction = answers?.reaction,
    goal = answers?.goal,
    manualAllocationText = effectiveAllocation.mapValues { (_, fraction) -> percentTextOf(fraction) }.toImmutableMap()
)
