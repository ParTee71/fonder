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
import se.partee71.ui.common.EditorLoader
import se.partee71.ui.common.EditorState
import se.partee71.ui.common.UiError

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

@Immutable
data class RiskProfilUiState(
    val availableLevels: ImmutableList<Int> = persistentListOf(),
    val horizon: TimeHorizon? = null,
    val reaction: DownturnReaction? = null,
    val goal: PrimaryGoal? = null,
    /** Enkätens förslag ur [RiskProfileCalc.suggest] — null om inte alla tre frågor är besvarade eller skalan är tom. */
    val suggestedAllocation: ImmutableMap<Int, Double>? = null,
    /** Icke-null så fort användaren rör en enskild andel — vinner då alltid över [suggestedAllocation] i sin helhet. */
    val manualAllocationText: ImmutableMap<Int, String>? = null,
    /**
     * Profilen är sparad ([EditorState]s `done`) — en händelse till skärmen, som går tillbaka och
     * kvitterar med [RiskProfilViewModel.onSavedHandled], så att navigeringen sker exakt en gång (även vid rotation).
     */
    val saved: Boolean = false,
    /** Den sparade profilen läses ([EditorLoader]) — Spara är släckt så länge, så förifyllningen aldrig skrivs över. */
    val loading: Boolean = false,
    /** Profilen sparas just nu — Spara är släckt, så ett dubbeltryck inte sparar två gånger. */
    val saving: Boolean = false,
    /** Den sparade profilen gick inte att läsa — felet visas i stället för formuläret (NFR-6). */
    val loadError: UiError? = null,
    /** Sparningen misslyckades — sägs under Spara, och formuläret står kvar (NFR-6). */
    val failure: UiError? = null
) {
    /** Text att visa per tillgänglig nivås inmatningsfält — se [allocationTextFor]. */
    val allocationText: Map<Int, String> get() = allocationTextFor(availableLevels, suggestedAllocation, manualAllocationText)

    /** Sant efter det första egna ändrade fältet — styr om förslagstexten fortfarande visas. */
    val hasManualEdit: Boolean get() = manualAllocationText != null

    val allocationSumPercent: Int get() = allocationText.values.sumOf { it.toIntOrNull() ?: 0 }

    /** Det som faktiskt går att spara — se [effectiveAllocationOf]. */
    val effectiveAllocation: Map<Int, Double> get() = effectiveAllocationOf(allocationText)

    /** Fördelningen summerar till 100 % — summaraden och dess förklaring följer den, inte sparningens läge. */
    val valid: Boolean get() = effectiveAllocation.isNotEmpty()

    val canSave: Boolean get() = valid && !loading && !saving && loadError == null
}

/**
 * Riskprofilens enkät + målfördelning (SET-3, issue #68 → målfördelning i issue #71) — en
 * engångsinställning under Inställningar, inte en daglig destination (samma
 * navigeringsmönster som import-skärmarna). Enkäten *föreslår*, användaren äger och sparar den
 * slutgiltiga fördelningen (se [RiskProfileCalc]/[RiskProfile]).
 *
 * Formuläret är android-ui:s [EditorState] med [EditorLoader] (NFR-6): den sparade profilen läses
 * en gång, ett läsfel visas i stället för en tom enkät, och sparningen är spärrad mot dubbeltryck.
 * Profilen får sparas oförändrad (`saveUnchanged`) — Spara har aldrig krävt en ändring här.
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
        validator = { form ->
            val levels = availableLevels.value
            val text = allocationTextFor(levels, suggestionFor(form, levels), form.manualAllocationText)
            if (effectiveAllocationOf(text).isEmpty()) mapOf(ALLOCATION to R.string.riskprofile_allocation_sum_error) else emptyMap()
        },
        errors = AppErrors,
        loading = true,
        saveUnchanged = true
    )

    // Förifyller enkäten och fördelningen med en redan sparad profil så en återbesökt riskprofil
    // visar vad man svarade/valde förra gången, inte en tom enkät — svaren och fördelningen hålls
    // medvetet isär i persistensen (SET-3). Ingen sparad profil är en tom enkät, inget fel.
    @Suppress("unused")
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
            RiskProfilUiState(
                availableLevels = levels.toImmutableList(),
                horizon = form.value.horizon,
                reaction = form.value.reaction,
                goal = form.value.goal,
                suggestedAllocation = suggestionFor(form.value, levels)?.toImmutableMap(),
                manualAllocationText = form.value.manualAllocationText,
                saved = form.done,
                loading = form.loading,
                saving = form.saving,
                loadError = form.loadError,
                failure = form.failure
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RiskProfilUiState(loading = true)
        )

    fun onHorizonSelected(horizon: TimeHorizon) = editor.update(HORIZON) { it.copy(horizon = horizon) }
    fun onReactionSelected(reaction: DownturnReaction) = editor.update(REACTION) { it.copy(reaction = reaction) }
    fun onGoalSelected(goal: PrimaryGoal) = editor.update(GOAL) { it.copy(goal = goal) }

    fun onAllocationPercentChanged(level: Int, percentText: String) {
        val levels = availableLevels.value
        editor.update(ALLOCATION) { form ->
            val base = allocationTextFor(levels, suggestionFor(form, levels), form.manualAllocationText)
            form.copy(manualAllocationText = (base + (level to percentText)).toImmutableMap())
        }
    }

    fun save() {
        viewModelScope.launch {
            // Ett nytt försök — felet från det förra gäller inte längre.
            editor.onFailureShown()
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

    /** Kvitterar [RiskProfilUiState.saved] när skärmen gått tillbaka, så att det inte upprepas. */
    fun onSavedHandled() = editor.onDoneHandled()

    private companion object {
        const val HORIZON = "horizon"
        const val REACTION = "reaction"
        const val GOAL = "goal"
        const val ALLOCATION = "allocation"
    }
}

/** Den sparade profilen som formulärvärde — svaren och fördelningen (heltalsprocent per nivå). */
private fun RiskProfile.toForm() = RiskProfileForm(
    horizon = answers?.horizon,
    reaction = answers?.reaction,
    goal = answers?.goal,
    manualAllocationText = effectiveAllocation.mapValues { (_, fraction) -> percentTextOf(fraction) }.toImmutableMap()
)
