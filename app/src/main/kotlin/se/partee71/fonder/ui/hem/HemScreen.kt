package se.partee71.fonder.ui.hem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import se.partee71.fonder.domain.usecase.PortfolioFeeCalc
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.PeriodRow
import se.partee71.fonder.ui.components.StatusDot
import se.partee71.fonder.ui.components.ValueAsOfRow
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

/**
 * Tillståndsdriven, testbar del av [HemScreen] — inget ViewModel/Hilt-beroende (issue #14).
 * `LazyColumn` (samma mönster som [se.partee71.fonder.ui.fond.FondDetaljScreen], regel 4) —
 * en vanlig `Column(fillMaxSize())` klippte tyst innehåll som växte förbi skärmhöjden
 * (analys-summeringskortets flaggade fonder, HEM-4, plus avgiftskortets rader, HEM-5) i
 * stället för att göra det nåbart (UI-5, issue #63).
 */
@Composable
fun HemContent(state: HemUiState, onFundClick: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    when {
        state.isEmpty -> EmptyState(
            title = stringResource(R.string.hem_empty_title),
            body = stringResource(R.string.hem_empty_body),
            modifier = modifier,
        )

        else -> LazyColumn(modifier = modifier.fillMaxSize()) {
            item { TotalCard(state = state) }
            item { PerformanceCard(performance = state.performance) }
            item { AnalysisSummaryCard(summary = state.analysisSummary, onFundClick = onFundClick) }
            item { FeeCard(summary = state.feeSummary, onFundClick = onFundClick) }
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
                ValueAsOfRow(navEpochDay = state.navEpochDay, modifier = Modifier.padding(top = 2.dp))
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
            val (dayAmount, dayFraction, dayPartial) = performance.day.toRowArgs()
            PeriodRow(
                label = stringResource(R.string.period_day),
                amount = dayAmount,
                fraction = dayFraction,
                partial = dayPartial,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            val (weekAmount, weekFraction, weekPartial) = performance.week.toRowArgs()
            PeriodRow(
                label = stringResource(R.string.period_week),
                amount = weekAmount,
                fraction = weekFraction,
                partial = weekPartial,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            val (monthAmount, monthFraction, monthPartial) = performance.month.toRowArgs()
            PeriodRow(
                label = stringResource(R.string.period_month),
                amount = monthAmount,
                fraction = monthFraction,
                partial = monthPartial,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

/** [amount]/[fraction]/[partial] — mappar [PortfolioPerformanceCalc.PortfolioPeriodResult] till [PeriodRow]s primitiver (regel 4). Saknas värde blir kr/% null och raden visar "Otillräcklig data". */
private data class PeriodRowArgs(val amount: Double?, val fraction: Double?, val partial: Boolean)

private fun PortfolioPerformanceCalc.PortfolioPeriodResult.toRowArgs(): PeriodRowArgs = when (this) {
    is PortfolioPerformanceCalc.PortfolioPeriodResult.Available ->
        PeriodRowArgs(amount, fraction, partial)
    PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory ->
        PeriodRowArgs(null, null, partial = false)
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

/**
 * Portföljens totala fondavgift per år (HEM-5, issue #60), med en rad per innehav (störst
 * avgift först, redan sorterat av [PortfolioFeeCalc.compute]) som öppnar fonden i Fonddetalj
 * — annars var totalen inte handlingsbar: användaren visste vad avgifterna kostade totalt,
 * men inte vilken fond som gjorde det (issue #63).
 */
@Composable
private fun FeeCard(summary: PortfolioFeeCalc.Result, onFundClick: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            PeriodRow(
                label = stringResource(R.string.hem_fee_title),
                amount = null,
                fraction = null,
                valueText = MoneyFormat.kr(summary.totalAnnualFeeKr),
            )
            Text(
                stringResource(R.string.hem_fee_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (summary.unknownFeeCount > 0) {
                Text(
                    stringResource(R.string.format_hem_fee_unknown, summary.unknownFeeCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (summary.byHolding.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    summary.byHolding.forEach { holdingFee ->
                        FeeHoldingRow(holdingFee = holdingFee, onClick = { onFundClick(holdingFee.fund.fundId) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeeHoldingRow(holdingFee: PortfolioFeeCalc.HoldingFee, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        PeriodRow(
            label = holdingFee.fund.name,
            amount = null,
            fraction = null,
            valueText = MoneyFormat.kr(holdingFee.annualFeeKr),
            modifier = Modifier.padding(12.dp),
        )
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
