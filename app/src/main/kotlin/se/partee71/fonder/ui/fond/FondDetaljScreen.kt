package se.partee71.fonder.ui.fond

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.components.AnalysisStatusBanner
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.PeriodRow
import se.partee71.fonder.ui.diagram.FundLineChart
import se.partee71.fonder.ui.theme.MonoAmountStyle
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * Fonddetalj — kurshistorik sedan första köpet i diagram och tabell (issue #7,
 * #7-uppföljning). Saknar fonden ISIN visas ett fält för att ange/bekräfta det (förifyllt
 * med ett namnbaserat förslag om ett hittades), se KRAVLISTA TP-14. Innehåller även en
 * Analys-sektion med nyckeltal och säljsignal-status (issue #16, ANA-1–ANA-3) för fonder
 * som är kvarvarande innehav.
 */
@Composable
fun FondDetaljScreen(
    modifier: Modifier = Modifier,
    viewModel: FondDetaljViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FondDetaljContent(state = state, onIsinConfirmed = viewModel::onIsinConfirmed, modifier = modifier)
}

/** Tillståndsdriven, testbar del av [FondDetaljScreen] — inget ViewModel/Hilt-beroende (issue #16). */
@Composable
fun FondDetaljContent(
    state: FondDetaljUiState,
    onIsinConfirmed: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when {
        state.isEmpty -> EmptyState(
            title = state.fundName ?: stringResource(R.string.fond_title),
            body = stringResource(R.string.fond_history_empty_body),
            modifier = modifier,
        )

        else -> LazyColumn(modifier = modifier.fillMaxSize()) {
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(state.fundName ?: "", style = MaterialTheme.typography.titleLarge)
                    if (state.analysis != null) {
                        AnalysisSection(analysis = state.analysis!!, modifier = Modifier.padding(top = 16.dp))
                    }
                    FundLineChart(
                        points = state.prices.sortedBy { it.epochDay }.map { it.epochDay to it.nav },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    if (state.isin == null) {
                        IsinInput(
                            suggestedIsin = state.suggestedIsin,
                            onConfirm = onIsinConfirmed,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
                HorizontalDivider()
            }
            items(state.prices, key = { it.epochDay }) { price ->
                PriceRow(price)
            }
        }
    }
}

@Composable
private fun AnalysisSection(analysis: FundAnalysisCalc.Analysis, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(stringResource(R.string.analys_section_title), style = MaterialTheme.typography.titleMedium)
        AnalysisStatusBanner(analysis = analysis, modifier = Modifier.padding(top = 8.dp))
        Column(modifier = Modifier.padding(top = 8.dp)) {
            analysis.keyFigures.periodReturns.forEach { periodReturn ->
                PeriodRow(
                    label = periodLabel(periodReturn.period),
                    amount = periodReturn.amount,
                    fraction = periodReturn.fraction,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            PeriodRow(
                label = stringResource(R.string.analys_cagr_label),
                amount = null,
                fraction = analysis.keyFigures.cagr,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            PeriodRow(
                label = stringResource(R.string.analys_gav_label),
                amount = analysis.keyFigures.gavPerShare,
                fraction = analysis.keyFigures.gavFraction,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            PeriodRow(
                label = stringResource(R.string.analys_portfolio_share_label),
                amount = null,
                fraction = analysis.keyFigures.portfolioShareFraction,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun periodLabel(period: FundAnalysisCalc.Period): String = when (period) {
    FundAnalysisCalc.Period.YTD -> stringResource(R.string.analys_period_ytd)
    FundAnalysisCalc.Period.TRE_MANADER -> stringResource(R.string.analys_period_3man)
    FundAnalysisCalc.Period.ETT_AR -> stringResource(R.string.analys_period_1ar)
    FundAnalysisCalc.Period.TRE_AR -> stringResource(R.string.analys_period_3ar)
    FundAnalysisCalc.Period.SEDAN_KOP -> stringResource(R.string.analys_period_sedan_kop)
}

@Composable
private fun IsinInput(
    suggestedIsin: String?,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(suggestedIsin.orEmpty()) }
    LaunchedEffect(suggestedIsin) {
        if (text.isEmpty()) text = suggestedIsin.orEmpty()
    }

    Column(modifier = modifier) {
        Text(
            text = stringResource(
                if (suggestedIsin != null) R.string.fond_isin_suggested_body else R.string.fond_isin_missing_body,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.fond_isin_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Button(
            onClick = { onConfirm(text) },
            enabled = text.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.save))
        }
    }
}

@Composable
private fun PriceRow(price: FundPrice) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            LocalDate.ofEpochDay(price.epochDay).format(dateFormatter),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            MoneyFormat.kr(price.nav),
            style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
        )
    }
}
