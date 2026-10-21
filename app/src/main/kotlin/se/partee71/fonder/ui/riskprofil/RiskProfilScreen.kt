package se.partee71.fonder.ui.riskprofil

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.TimeHorizon
import se.partee71.fonder.ui.components.ChoiceChipRow
import se.partee71.ui.common.EditorEvents
import se.partee71.ui.components.AppTextField
import se.partee71.ui.components.EntityEditScreen
import se.partee71.ui.theme.Spacing

@Composable
fun RiskProfilScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RiskProfilViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RiskProfilContent(
        state = state,
        events = viewModel.events,
        onClose = onClose,
        onHorizonSelected = viewModel::onHorizonSelected,
        onReactionSelected = viewModel::onReactionSelected,
        onGoalSelected = viewModel::onGoalSelected,
        onAllocationPercentChanged = viewModel::onAllocationPercentChanged,
        onSave = viewModel::save,
        onRetry = viewModel::retry,
        modifier = modifier
    )
}

/**
 * Tillståndsdriven, testbar del av [RiskProfilScreen] — inget ViewModel/Hilt-beroende. Ramen är
 * android-ui:s `EntityEditScreen` (UI-15): liten topprad med "Riskprofil" och Spara, "Släng
 * ändringar?" vid bakåt med osparade ändringar, sparfel som snackbar, läsfel med "Försök igen"
 * ([onRetry]) och tillbaka efter lyckad sparning ([onClose]). Ramens kolumn rullar (UI-5) och lägger
 * 16 dp mellan avsnitten.
 *
 * Målfördelningen har en rad per tillgänglig risknivå med ett procentfält. Summan måste bli exakt
 * 100 % för att Spara ska aktiveras — går den inte ihop sparas ingenting (ingen tyst normalisering,
 * [RiskProfilUiState.effectiveAllocation]).
 */
@Composable
fun RiskProfilContent(
    state: RiskProfilUiState,
    events: EditorEvents,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onHorizonSelected: (TimeHorizon) -> Unit = {},
    onReactionSelected: (DownturnReaction) -> Unit = {},
    onGoalSelected: (PrimaryGoal) -> Unit = {},
    onAllocationPercentChanged: (Int, String) -> Unit = { _, _ -> },
    onSave: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    EntityEditScreen(
        title = stringResource(R.string.riskprofil_title),
        state = state.editor,
        events = events,
        onSave = onSave,
        onClose = onClose,
        modifier = modifier,
        onRetry = onRetry,
        horizontalMargin = Spacing.l
    ) {
        Text(
            stringResource(R.string.riskprofile_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        QuestionSection(stringResource(R.string.riskprofile_q_horizon)) {
            ChoiceChipRow(
                options = TimeHorizon.entries,
                selected = state.horizon,
                optionLabel = { stringResource(it.labelRes()) },
                onSelect = onHorizonSelected
            )
        }
        QuestionSection(stringResource(R.string.riskprofile_q_reaction)) {
            ChoiceChipRow(
                options = DownturnReaction.entries,
                selected = state.reaction,
                optionLabel = { stringResource(it.labelRes()) },
                onSelect = onReactionSelected
            )
        }
        QuestionSection(stringResource(R.string.riskprofile_q_goal)) {
            ChoiceChipRow(
                options = PrimaryGoal.entries,
                selected = state.goal,
                optionLabel = { stringResource(it.labelRes()) },
                onSelect = onGoalSelected
            )
        }

        if (state.suggestedAllocation != null && !state.hasManualEdit) {
            Text(stringResource(R.string.riskprofile_suggestion_hint), style = MaterialTheme.typography.bodyMedium)
        }

        AllocationSection(state, onAllocationPercentChanged)
    }
}

@Composable
private fun QuestionSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

/** Målfördelningen: rubrik, förbehållet, en rad per nivå och summan med sitt fel. */
@Composable
private fun AllocationSection(state: RiskProfilUiState, onAllocationPercentChanged: (Int, String) -> Unit) {
    Column {
        Text(
            stringResource(R.string.riskprofile_allocation_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = Spacing.xs)
        )
        Text(
            stringResource(R.string.riskprofile_allocation_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Spacing.s)
        )
        if (state.availableLevels.isEmpty()) {
            Text(
                stringResource(R.string.riskprofile_no_levels_yet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.availableLevels.forEach { level ->
                AllocationRow(
                    level = level,
                    percentText = state.allocationText[level].orEmpty(),
                    onPercentChange = { onAllocationPercentChanged(level, it) }
                )
            }
            Text(
                stringResource(R.string.format_riskprofile_allocation_sum, state.allocationSumPercent),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.valid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacing.s)
            )
            if (!state.valid) {
                Text(
                    stringResource(R.string.riskprofile_allocation_sum_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }
    }
}

@Composable
private fun AllocationRow(level: Int, percentText: String, onPercentChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)
    ) {
        Text(
            stringResource(R.string.format_riskprofile_level_label, level),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        AppTextField(
            value = percentText,
            onValueChange = onPercentChange,
            label = stringResource(R.string.riskprofile_allocation_field_label),
            suffix = "%",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(100.dp).testTag(allocationFieldTestTag(level))
        )
    }
}

/** Testtagg per nivås procentfält (instrumenttest, issue #71) — text ändras (0 %, 25 % …) så `onNodeWithText` inte pålitligt kan hitta rätt fält, till skillnad från raden och etiketten. */
fun allocationFieldTestTag(level: Int): String = "riskprofile_allocation_$level"

private fun TimeHorizon.labelRes(): Int = when (this) {
    TimeHorizon.UNDER_3_AR -> R.string.risk_horizon_under_3
    TimeHorizon.TRE_TILL_7_AR -> R.string.risk_horizon_3_7
    TimeHorizon.SJU_TILL_15_AR -> R.string.risk_horizon_7_15
    TimeHorizon.OVER_15_AR -> R.string.risk_horizon_over_15
}

private fun DownturnReaction.labelRes(): Int = when (this) {
    DownturnReaction.SALJER_ALLT -> R.string.risk_reaction_saljer_allt
    DownturnReaction.SALJER_DEL -> R.string.risk_reaction_saljer_del
    DownturnReaction.GOR_INGET -> R.string.risk_reaction_gor_inget
    DownturnReaction.KOPER_MER -> R.string.risk_reaction_koper_mer
}

/** Målets namn – i enkäten och i Besluts rubrikrad (NAV-9), en gång (regel 4). */
@StringRes
fun PrimaryGoal.labelRes(): Int = when (this) {
    PrimaryGoal.BEVARA -> R.string.risk_goal_bevara
    PrimaryGoal.BALANSERAD -> R.string.risk_goal_balanserad
    PrimaryGoal.MAXIMAL_TILLVAXT -> R.string.risk_goal_maximal_tillvaxt
}
