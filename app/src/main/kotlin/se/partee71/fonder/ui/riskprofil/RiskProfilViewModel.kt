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

data class RiskProfilUiState(
    val availableLevels: List<Int> = emptyList(),
    val horizon: TimeHorizon? = null,
    val reaction: DownturnReaction? = null,
    val goal: PrimaryGoal? = null,
    /** Enkätens förslag ur [RiskProfileCalc.suggest] — null om inte alla tre frågor är besvarade eller skalan är tom. */
    val suggestedLevel: Int? = null,
    /** Satt så fort användaren tar ett eget val i nivåväljaren — vinner alltid över [suggestedLevel]. */
    val manualLevel: Int? = null,
    val saved: Boolean = false,
) {
    /** Det som faktiskt sparas vid `save()` — det egna valet vinner alltid över förslaget. */
    val selectedLevel: Int? get() = manualLevel ?: suggestedLevel
}

/**
 * Riskprofilens enkät + målrisknivå (SET-3, issue #68) — en engångsinställning under
 * Inställningar, inte en daglig destination (samma navigeringsmönster som import-skärmarna).
 * Enkäten *föreslår*, användaren äger och sparar den slutgiltiga nivån (se
 * [RiskProfileCalc]/[RiskProfile]).
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
        val manualLevel: Int? = null,
        val saved: Boolean = false,
    )

    private val form = MutableStateFlow(FormState())
    private val availableLevels = MutableStateFlow<List<Int>>(emptyList())

    init {
        viewModelScope.launch { availableLevels.value = fundMetadataRepository.knownRiskLevels() }
        viewModelScope.launch {
            // Förifyller enkäten med en redan sparad profils svar (om den enkätvägen sattes) så
            // att en återbesökt riskprofil visar vad man svarade förra gången, inte en tom
            // enkät — svaren och nivån hålls medvetet isär i persistensen (SET-3).
            preferences.riskProfile.first()?.let { saved ->
                form.update {
                    it.copy(
                        horizon = saved.answers?.horizon,
                        reaction = saved.answers?.reaction,
                        goal = saved.answers?.goal,
                        manualLevel = saved.targetRiskLevel,
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
                suggestedLevel = suggestionOrNull(f, levels),
                manualLevel = f.manualLevel,
                saved = f.saved,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RiskProfilUiState(),
        )

    private fun suggestionOrNull(f: FormState, levels: List<Int>): Int? {
        val horizon = f.horizon ?: return null
        val reaction = f.reaction ?: return null
        val goal = f.goal ?: return null
        return RiskProfileCalc.suggest(RiskProfileAnswers(horizon, reaction, goal), levels)
    }

    fun onHorizonSelected(horizon: TimeHorizon) = form.update { it.copy(horizon = horizon) }
    fun onReactionSelected(reaction: DownturnReaction) = form.update { it.copy(reaction = reaction) }
    fun onGoalSelected(goal: PrimaryGoal) = form.update { it.copy(goal = goal) }
    fun onLevelSelected(level: Int) = form.update { it.copy(manualLevel = level) }

    fun save() {
        val state = uiState.value
        val level = state.selectedLevel ?: return
        val answers = if (state.horizon != null && state.reaction != null && state.goal != null) {
            RiskProfileAnswers(state.horizon, state.reaction, state.goal)
        } else {
            null
        }
        viewModelScope.launch {
            preferences.setRiskProfile(RiskProfile(targetRiskLevel = level, answers = answers))
            form.update { it.copy(saved = true) }
        }
    }
}
