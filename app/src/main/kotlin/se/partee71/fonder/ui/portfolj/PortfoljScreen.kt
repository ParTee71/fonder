package se.partee71.fonder.ui.portfolj

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.PeriodRow
import se.partee71.fonder.ui.components.UnavailableReason
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.fonder.ui.theme.ReturnColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

@Composable
fun PortfoljScreen(
    onFundClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PortfoljViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PortfoljContent(state = state, onFundClick = onFundClick, modifier = modifier)
}

/** Tillståndsdriven, testbar del av [PortfoljScreen] — inget ViewModel/Hilt-beroende (issue #14). */
@Composable
fun PortfoljContent(
    state: PortfoljUiState,
    onFundClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isEmpty -> EmptyState(
            title = stringResource(R.string.portfolj_empty_title),
            body = stringResource(R.string.portfolj_empty_body),
            modifier = modifier,
        )

        else -> Column(modifier = modifier.fillMaxSize()) {
            TotalCard(state = state)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.holdings, key = { it.fund.fundId }) { holding ->
                    HoldingRow(
                        holding = holding,
                        performance = state.performance[holding.fund.fundId],
                        onClick = { onFundClick(holding.fund.fundId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TotalCard(state: PortfoljUiState) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HoldingRow(
    holding: Holding,
    performance: PortfolioPerformanceCalc.HoldingPerformance?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(holding.fund.name, style = MaterialTheme.typography.titleMedium)
            FirstPurchaseRow(holding = holding, modifier = Modifier.padding(top = 2.dp))
            val value = holding.currentValue
            val fraction = holding.gainLossFraction
            if (value != null) {
                Text(
                    MoneyFormat.kr(value),
                    style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                )
                if (fraction != null) {
                    Text(
                        "${MoneyFormat.percentSigned(fraction)} · ${MoneyFormat.kr(holding.gainLoss ?: 0.0)}",
                        style = MonoAmountStyle.merge(MaterialTheme.typography.bodySmall),
                        color = ReturnColors.forAmount(holding.gainLoss ?: 0.0),
                    )
                }
                val (dayAmount, dayFraction, dayReason) = performance?.day.toRowArgs()
                PeriodRow(
                    label = stringResource(R.string.period_day),
                    amount = dayAmount,
                    fraction = dayFraction,
                    unavailableReason = dayReason,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val (weekAmount, weekFraction, weekReason) = performance?.week.toRowArgs()
                PeriodRow(
                    label = stringResource(R.string.period_week),
                    amount = weekAmount,
                    fraction = weekFraction,
                    unavailableReason = weekReason,
                )
                val (monthAmount, monthFraction, monthReason) = performance?.month.toRowArgs()
                PeriodRow(
                    label = stringResource(R.string.period_month),
                    amount = monthAmount,
                    fraction = monthFraction,
                    unavailableReason = monthReason,
                )
            } else {
                Text(
                    MoneyFormat.kr(holding.netInvested),
                    style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.portfolj_price_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Första köp-datum + kvarvarande inköpsvärde (FIFO) för innehavet (POR-6, issue #18). */
@Composable
private fun FirstPurchaseRow(holding: Holding, modifier: Modifier = Modifier) {
    val firstPurchaseEpochDay = holding.firstPurchaseEpochDay ?: return
    Text(
        stringResource(
            R.string.format_holding_first_purchase,
            LocalDate.ofEpochDay(firstPurchaseEpochDay).format(dateFormatter),
            MoneyFormat.kr(holding.netInvested),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Mappar [PortfolioPerformanceCalc.PeriodResult] till [PeriodRow]s primitiva parametrar (issue #18) — komponenten själv känner inte till domänmodeller (regel 4). */
private fun PortfolioPerformanceCalc.PeriodResult?.toRowArgs(): Triple<Double?, Double?, UnavailableReason> = when (this) {
    is PortfolioPerformanceCalc.PeriodResult.Available -> Triple(amount, fraction, UnavailableReason.INSUFFICIENT_DATA)
    PortfolioPerformanceCalc.PeriodResult.StalePrice -> Triple(null, null, UnavailableReason.STALE_PRICE)
    PortfolioPerformanceCalc.PeriodResult.InsufficientHistory, null -> Triple(null, null, UnavailableReason.INSUFFICIENT_DATA)
}
