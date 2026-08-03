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
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.PortfolioFeeCalc
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import se.partee71.fonder.domain.usecase.PortfolioRiskCalc
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.ExposureBar
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
            state.riskProfile?.let { riskProfile ->
                item {
                    RiskCard(
                        riskProfile = riskProfile,
                        portfolioRisk = state.portfolioRisk,
                        levelDeviations = state.riskLevelDeviations,
                    )
                }
            }
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
 * Portföljens totala fondavgift per år (HEM-5, issue #60), samlade besparingspotential
 * (HEM-6, issue #61) och en rad per innehav (störst avgift först, redan sorterat av
 * [PortfolioFeeCalc.compute]) som öppnar fonden i Fonddetalj — annars var totalen inte
 * handlingsbar: användaren visste vad avgifterna kostade totalt, men inte vilken fond som
 * gjorde det (issue #63). Besparingen visas bara för innehav med ett känt, färskt
 * jämförelseresultat ([PortfolioFeeCalc.HoldingFee.annualSavingsKr] null annars) — ett
 * innehav som aldrig genomsökts ser ut som just det (via "N av M genomsökta"), aldrig som
 * "inget billigare hittades".
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
            if (summary.comparableCount > 0) {
                Text(
                    stringResource(
                        R.string.format_hem_fee_savings,
                        MoneyFormat.kr(summary.totalAnnualSavingsKr),
                        summary.comparedCount,
                        summary.comparableCount,
                    ),
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
        Column(modifier = Modifier.padding(12.dp)) {
            PeriodRow(
                label = holdingFee.fund.name,
                amount = null,
                fraction = null,
                valueText = MoneyFormat.kr(holdingFee.annualFeeKr),
            )
            // Aldrig jämfört (ingen text) och jämfört-utan-träff ("Redan bland de billigaste",
            // samma text som ANA-9:s eget kort i Fonddetalj — regel 4) är skilda tillstånd,
            // både i data (PortfolioFeeCalc.HoldingFee.wasCompared) och här i UI:t. Annars ser
            // ett outforskat innehav ut som redan optimalt.
            val savings = holdingFee.annualSavingsKr
            when {
                savings != null && savings > 0 -> Text(
                    stringResource(R.string.format_hem_fee_holding_savings, MoneyFormat.kr(savings)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                holdingFee.wasCompared -> Text(
                    stringResource(R.string.fee_comparison_none_cheaper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
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

/**
 * Målfördelning (SET-3) mot innehavens faktiska fördelning per risknivå (HEM-7, uppgraderad
 * från en enskild målnivå till en fördelning i issue #71) — ren läsvy, ingen åtgärdsknapp.
 * Det värdeviktade snittet ([RiskProfile.weightedTargetLevel] mot
 * [PortfolioRiskCalc.Result.weightedAverageRisk]) visas kvar som en sammanfattning överst
 * (samma [PeriodRow]-mönster som tidigare, regel 4), men huvudinnehållet är nu per-nivå-raderna
 * längre ned — en enda skalär kan inte skilja en 50/50-mix av nivå 1 och 6 från 100 % nivå
 * 3,5, se [PortfolioRiskCalc]s precisionsanmärkning. Per-nivå-raderna återanvänder
 * [se.partee71.fonder.ui.components.ExposureBar] (regel 4) — här är den rätt komponenten,
 * till skillnad från #68 där en enskild nivå var en position på en skala, inte en andel.
 */
@Composable
private fun RiskCard(
    riskProfile: RiskProfile,
    portfolioRisk: PortfolioRiskCalc.Result,
    levelDeviations: List<PortfolioRiskCalc.LevelDeviation>,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.hem_risk_title), style = MaterialTheme.typography.labelMedium)
            PeriodRow(
                label = stringResource(R.string.hem_risk_target_label),
                amount = null,
                fraction = null,
                valueText = riskProfile.weightedTargetLevel?.let { MoneyFormat.decimal(it) },
                modifier = Modifier.padding(top = 8.dp),
            )
            PeriodRow(
                label = stringResource(R.string.hem_risk_actual_label),
                amount = null,
                fraction = null,
                valueText = portfolioRisk.weightedAverageRisk?.let { MoneyFormat.decimal(it) },
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                stringResource(R.string.hem_risk_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (portfolioRisk.excludedCount > 0) {
                Text(
                    stringResource(R.string.format_hem_risk_excluded, portfolioRisk.excludedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (levelDeviations.isNotEmpty()) {
                Text(
                    stringResource(R.string.hem_risk_distribution_title),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                levelDeviations.sortedBy { it.level }.forEach { deviation ->
                    Text(
                        stringResource(R.string.format_hem_risk_level, deviation.level),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    ExposureBar(label = stringResource(R.string.hem_risk_target_bar_label), fraction = deviation.targetFraction)
                    ExposureBar(
                        label = stringResource(R.string.hem_risk_actual_bar_label),
                        fraction = deviation.actualFraction,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}
