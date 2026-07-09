package se.partee71.fonder.ui.hem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.PeriodRow
import se.partee71.fonder.ui.components.StatusDot
import se.partee71.fonder.ui.components.statusTriggerMessages
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.fonder.ui.theme.ReturnColors

@Composable
fun HemScreen(
    onFundClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HemViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HemContent(state = state, onFundClick = onFundClick, modifier = modifier)
}

/** Tillståndsdriven, testbar del av [HemScreen] — inget ViewModel/Hilt-beroende (issue #14). */
@Composable
fun HemContent(state: HemUiState, onFundClick: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    when {
        state.isEmpty -> EmptyState(
            title = stringResource(R.string.hem_empty_title),
            body = stringResource(R.string.hem_empty_body),
            modifier = modifier,
        )

        else -> Column(modifier = modifier.fillMaxSize()) {
            TotalCard(state = state)
            PerformanceCard(performance = state.performance)
            AnalysisSummaryCard(summary = state.analysisSummary, onFundClick = onFundClick)
        }
    }
}

@Composable
private fun TotalCard(state: HemUiState) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.portfolj_total_value), style = MaterialTheme.typography.labelMedium)
            val fraction = state.totalGainLossFraction
            if (fraction != null) {
                Text(MoneyFormat.kr(state.totalValue), style = MonoAmountStyle.merge(MaterialTheme.typography.headlineMedium))
                Text(
                    "${MoneyFormat.percentSigned(fraction)} · ${MoneyFormat.kr(state.totalGainLoss)}",
                    style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                    color = ReturnColors.forAmount(state.totalGainLoss),
                )
            } else {
                Text(MoneyFormat.kr(state.totalInvested), style = MonoAmountStyle.merge(MaterialTheme.typography.headlineMedium))
                Text(
                    stringResource(R.string.portfolj_price_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PerformanceCard(performance: PortfolioPerformanceCalc.PortfolioPerformance) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            PeriodRow(
                label = stringResource(R.string.period_day),
                amount = performance.day?.amount,
                fraction = performance.day?.fraction,
                partial = performance.day?.partial ?: false,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            PeriodRow(
                label = stringResource(R.string.period_week),
                amount = performance.week?.amount,
                fraction = performance.week?.fraction,
                partial = performance.week?.partial ?: false,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            PeriodRow(
                label = stringResource(R.string.period_month),
                amount = performance.month?.amount,
                fraction = performance.month?.fraction,
                partial = performance.month?.partial ?: false,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

/** Summeringskort över gul-/rödflaggade fonder (issue #16, HEM-4). */
@Composable
private fun AnalysisSummaryCard(summary: AnalysisSummary, onFundClick: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.hem_analysis_title), style = MaterialTheme.typography.labelMedium)
            if (summary.flagged.isEmpty()) {
                Text(
                    stringResource(R.string.hem_analysis_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    summary.flagged.forEach { flagged ->
                        FlaggedFundRow(flagged = flagged, onClick = { onFundClick(flagged.fund.fundId) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlaggedFundRow(flagged: FlaggedHolding, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(level = flagged.analysis.status!!, modifier = Modifier.padding(end = 12.dp))
            Column {
                Text(flagged.fund.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    statusTriggerMessages(flagged.analysis).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
