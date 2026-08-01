package se.partee71.fonder.ui.riskprofil

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
        onLevelSelected = viewModel::onLevelSelected,
        onSave = viewModel::save,
        modifier = modifier,
    )
}

/**
 * Tillståndsdriven, testbar del av [RiskProfilScreen] — inget ViewModel/Hilt-beroende (issue
 * #68, samma mönster som Hem/Portfölj sedan issue #14). Fast `Column` +
 * `verticalScroll` (inte en lazy `items()`-lista) — rätt UI-5-metod för en formulärvy där
 * innehållet inte växer med antalet innehav.
 */
@Composable
fun RiskProfilContent(
    state: RiskProfilUiState,
    onHorizonSelected: (TimeHorizon) -> Unit = {},
    onReactionSelected: (DownturnReaction) -> Unit = {},
    onGoalSelected: (PrimaryGoal) -> Unit = {},
    onLevelSelected: (Int) -> Unit = {},
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

        if (state.suggestedLevel != null && state.manualLevel == null) {
            Text(
                stringResource(R.string.format_riskprofile_suggested, state.suggestedLevel),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        Text(
            stringResource(R.string.riskprofile_level_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        if (state.availableLevels.isEmpty()) {
            Text(
                stringResource(R.string.riskprofile_no_levels_yet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ChoiceChipRow(
                options = state.availableLevels,
                selected = state.selectedLevel,
                optionLabel = { it.toString() },
                onSelect = onLevelSelected,
            )
        }

        Button(
            onClick = onSave,
            enabled = state.selectedLevel != null,
            modifier = Modifier.padding(top = 24.dp),
        ) { Text(stringResource(R.string.riskprofile_save_button)) }
    }
}

@Composable
private fun QuestionSection(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    content()
}

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
