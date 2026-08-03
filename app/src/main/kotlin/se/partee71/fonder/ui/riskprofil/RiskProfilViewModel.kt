package se.partee71.fonder.ui.riskprofil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.RiskProfileAnswers
import se.partee71.fonder.domain.model.TimeHorizon
import se.partee71.fonder.domain.usecase.RiskProfileCalc
import javax.inject.Inject
import kotlin.math.roundToInt

/** Andelen (0.0..1.0) uttryckt som ett rundat heltalsprocent för visning/redigering i inmatningsfälten. */
private fun percentTextOf(fraction: Double): String = (fraction * 100).roundToInt().toString()

data class RiskProfilUiState(
    val availableLevels: List<Int> = emptyList(),
    val horizon: TimeHorizon? = null,
    val reaction: DownturnReaction? = null,
    val goal: PrimaryGoal? = null,
    /** Enkätens förslag ur [RiskProfileCalc.suggest] — null om inte alla tre frågor är besvarade eller skalan är tom. */
    val suggestedAllocation: Map<Int, Double>? = null,
    /** Icke-null så fort användaren rör en enskild andel — vinner då alltid över [suggestedAllocation] i sin helhet. */
    val manualAllocationText: Map<Int, String>? = null,
    val saved: Boolean = false,
) {
    /** Text att visa per tillgänglig nivås inmatningsfält — förslaget (rundat till heltalsprocent) tills ett eget värde skrivits in, annars de egna värdena (saknad nivå defaultar till "0"). */
    val allocationText: Map<Int, String>
        get() {
            val manual = manualAllocationText
            return if (manual != null) {
                availableLevels.associateWith { level -> manual[level] ?: "0" }
            } else {
                availableLevels.associateWith { level -> suggestedAllocation?.get(level)?.let(::percentTextOf) ?: "0" }
            }
        }

    /** Sant efter det första egna ändrade fältet — styr om förslagstexten fortfarande visas. */
    val hasManualEdit: Boolean get() = manualAllocationText != null

    val allocationSumPercent: Int get() = allocationText.values.sumOf { it.toIntOrNull() ?: 0 }

    /** Det som faktiskt går att spara — bara giltigt när summan är (i praktiken) exakt 100 %, annars tom (ingen tyst normalisering). */
    val effectiveAllocation: Map<Int, Double>
        get() {
            val fractions = allocationText
                .mapValues { (_, text) -> (text.toIntOrNull() ?: 0) / 100.0 }
                .filterValues { it > 0.0 }
            return if (RiskProfileCalc.isCompleteAllocation(fractions)) fractions else emptyMap()
        }

    val canSave: Boolean get() = effectiveAllocation.isNotEmpty()
}

/**
 * Riskprofilens enkät + målfördelning (SET-3, issue #68 → målfördelning i issue #71) — en
 * engångsinställning under Inställningar, inte en daglig destination (samma
 * navigeringsmönster som import-skärmarna). Enkäten *föreslår*, användaren äger och sparar den
 * slutgiltiga fördelningen (se [RiskProfileCalc]/[RiskProfile]).
 */
@HiltViewModel
class RiskProfilViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val fundMetadataRepository: FundMetadataRepository,
) : ViewModel() {

    private data class FormState(
        val horizon: TimeHorizon? = null,
        val reaction: DownturnReaction? = null,
        val goal: PrimaryGoal? = null,
        val manualAllocationText: Map<Int, String>? = null,
        val saved: Boolean = false,
    )

    private val form = MutableStateFlow(FormState())
    private val availableLevels = MutableStateFlow<List<Int>>(emptyList())

    init {
        viewModelScope.launch { availableLevels.value = fundMetadataRepository.knownRiskLevels() }
        viewModelScope.launch {
            // Förifyller enkäten och fördelningen med en redan sparad profil så en återbesökt
            // riskprofil visar vad man svarade/valde förra gången, inte en tom enkät — svaren
            // och fördelningen hålls medvetet isär i persistensen (SET-3).
            preferences.riskProfile.first()?.let { saved ->
                form.update {
                    it.copy(
                        horizon = saved.answers?.horizon,
                        reaction = saved.answers?.reaction,
                        goal = saved.answers?.goal,
                        manualAllocationText = saved.effectiveAllocation.mapValues { (_, fraction) -> percentTextOf(fraction) },
                    )
                }
            }
        }
    }

    val uiState: StateFlow<RiskProfilUiState> =
        combine(form, availableLevels) { f, levels ->
            RiskProfilUiState(
                availableLevels = levels,
                horizon = f.horizon,
                reaction = f.reaction,
                goal = f.goal,
                suggestedAllocation = suggestionOrNull(f, levels),
                manualAllocationText = f.manualAllocationText,
                saved = f.saved,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RiskProfilUiState(),
        )

    private fun suggestionOrNull(f: FormState, levels: List<Int>): Map<Int, Double>? {
        val horizon = f.horizon ?: return null
        val reaction = f.reaction ?: return null
        val goal = f.goal ?: return null
        return RiskProfileCalc.suggest(RiskProfileAnswers(horizon, reaction, goal), levels)
    }

    fun onHorizonSelected(horizon: TimeHorizon) = form.update { it.copy(horizon = horizon) }
    fun onReactionSelected(reaction: DownturnReaction) = form.update { it.copy(reaction = reaction) }
    fun onGoalSelected(goal: PrimaryGoal) = form.update { it.copy(goal = goal) }

    fun onAllocationPercentChanged(level: Int, percentText: String) {
        val base = uiState.value.allocationText
        form.update { it.copy(manualAllocationText = base + (level to percentText)) }
    }

    fun save() {
        val state = uiState.value
        val allocation = state.effectiveAllocation
        if (allocation.isEmpty()) return
        val answers = if (state.horizon != null && state.reaction != null && state.goal != null) {
            RiskProfileAnswers(state.horizon, state.reaction, state.goal)
        } else {
            null
        }
        viewModelScope.launch {
            preferences.setRiskProfile(RiskProfile(targetAllocation = allocation, answers = answers))
            form.update { it.copy(saved = true) }
        }
    }
}
