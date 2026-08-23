package se.partee71.fonder.ui.riskprofil

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.TimeHorizon
import se.partee71.fonder.ui.components.ChoiceChipRow

@Composable
fun RiskProfilScreen(
    onSaved: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: RiskProfilViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    RiskProfilContent(
        state = state,
        onHorizonSelected = viewModel::onHorizonSelected,
        onReactionSelected = viewModel::onReactionSelected,
        onGoalSelected = viewModel::onGoalSelected,
        onAllocationPercentChanged = viewModel::onAllocationPercentChanged,
        onSave = viewModel::save,
        modifier = modifier,
    )
}

/**
 * Tillståndsdriven, testbar del av [RiskProfilScreen] — inget ViewModel/Hilt-beroende (issue
 * #68, samma mönster som Hem/Portfölj sedan issue #14). Fast `Column` +
 * `verticalScroll` (inte en lazy `items()`-lista) — rätt UI-5-metod för en formulärvy där
 * innehållet inte växer med antalet innehav.
 *
 * Målnivån ersattes av en **målfördelning** i issue #71: en rad per tillgänglig risknivå med
 * ett procentfält, i stället för [ChoiceChipRow]s enda-val. Summan måste bli exakt 100 % för
 * att Spara-knappen ska aktiveras — går den inte ihop sparas ingenting (ingen tyst
 * normalisering, [RiskProfilUiState.effectiveAllocation]).
 */
@Composable
fun RiskProfilContent(
    state: RiskProfilUiState,
    onHorizonSelected: (TimeHorizon) -> Unit = {},
    onReactionSelected: (DownturnReaction) -> Unit = {},
    onGoalSelected: (PrimaryGoal) -> Unit = {},
    onAllocationPercentChanged: (Int, String) -> Unit = { _, _ -> },
    onSave: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.riskprofile_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.riskprofile_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        QuestionSection(stringResource(R.string.riskprofile_q_horizon)) {
            ChoiceChipRow(
                options = TimeHorizon.entries,
                selected = state.horizon,
                optionLabel = { stringResource(it.labelRes()) },
                onSelect = onHorizonSelected,
            )
        }
        QuestionSection(stringResource(R.string.riskprofile_q_reaction)) {
            ChoiceChipRow(
                options = DownturnReaction.entries,
                selected = state.reaction,
                optionLabel = { stringResource(it.labelRes()) },
                onSelect = onReactionSelected,
            )
        }
        QuestionSection(stringResource(R.string.riskprofile_q_goal)) {
            ChoiceChipRow(
                options = PrimaryGoal.entries,
                selected = state.goal,
                optionLabel = { stringResource(it.labelRes()) },
                onSelect = onGoalSelected,
            )
        }

        if (state.suggestedAllocation != null && !state.hasManualEdit) {
            Text(
                stringResource(R.string.riskprofile_suggestion_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        Text(
            stringResource(R.string.riskprofile_allocation_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        Text(
            stringResource(R.string.riskprofile_allocation_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (state.availableLevels.isEmpty()) {
            Text(
                stringResource(R.string.riskprofile_no_levels_yet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.availableLevels.forEach { level ->
                AllocationRow(
                    level = level,
                    percentText = state.allocationText[level].orEmpty(),
                    onPercentChange = { onAllocationPercentChanged(level, it) },
                )
            }
            Text(
                stringResource(R.string.format_riskprofile_allocation_sum, state.allocationSumPercent),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.canSave) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (!state.canSave) {
                Text(
                    stringResource(R.string.riskprofile_allocation_sum_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Button(
            onClick = onSave,
            enabled = state.canSave,
            modifier = Modifier.padding(top = 24.dp),
        ) { Text(stringResource(R.string.riskprofile_save_button)) }
    }
}

@Composable
private fun QuestionSection(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    content()
}

@Composable
private fun AllocationRow(level: Int, percentText: String, onPercentChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            stringResource(R.string.format_riskprofile_level_label, level),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = percentText,
            onValueChange = onPercentChange,
            suffix = { Text("%") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(100.dp).testTag(allocationFieldTestTag(level)),
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

private fun PrimaryGoal.labelRes(): Int = when (this) {
    PrimaryGoal.BEVARA -> R.string.risk_goal_bevara
    PrimaryGoal.BALANSERAD -> R.string.risk_goal_balanserad
    PrimaryGoal.MAXIMAL_TILLVAXT -> R.string.risk_goal_maximal_tillvaxt
}
