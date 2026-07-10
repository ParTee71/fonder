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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.components.AnalysisGuidanceCard
import se.partee71.fonder.ui.components.AnalysisStatusBanner
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.ExpandableInfoRow
import se.partee71.fonder.ui.components.PeriodRow
import se.partee71.fonder.ui.components.StatusDot
import se.partee71.fonder.ui.diagram.FundLineChart
import se.partee71.fonder.ui.theme.MonoAmountStyle
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * Fonddetalj — kurshistorik sedan första köpet i diagram och tabell (issue #7,
 * #7-uppföljning). Saknar fonden ISIN visas ett fält för att ange/bekräfta det (förifyllt
 * med ett namnbaserat förslag om ett hittades), se KRAVLISTA TP-14. Innehåller även en
 * Analys-sektion med nyckeltal och säljsignal-status (issue #16, ANA-1–ANA-4) för fonder
 * som är kvarvarande innehav, plus ett pedagogiskt lager med utfällbara förklaringar,
 * neutral kontext och en ordlista (issue #22, ANA-5–ANA-6).
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
                    val firstPurchaseEpochDay = state.firstPurchaseEpochDay
                    val netInvested = state.netInvested
                    if (firstPurchaseEpochDay != null && netInvested != null) {
                        Text(
                            stringResource(
                                R.string.format_holding_first_purchase,
                                LocalDate.ofEpochDay(firstPurchaseEpochDay).format(dateFormatter),
                                MoneyFormat.kr(netInvested),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
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
        AnalysisGuidanceCard(analysis = analysis, modifier = Modifier.padding(top = 8.dp))
        SignalExplanations(analysis = analysis, modifier = Modifier.padding(top = 8.dp))
        Column(modifier = Modifier.padding(top = 8.dp)) {
            analysis.keyFigures.periodReturns.forEach { periodReturn ->
                ExpandableInfoRow(explanation = stringResource(R.string.analys_period_explain)) {
                    PeriodRow(
                        label = periodLabel(periodReturn.period),
                        amount = periodReturn.amount,
                        fraction = periodReturn.fraction,
                    )
                }
            }
            ExpandableInfoRow(explanation = stringResource(R.string.analys_cagr_explain)) {
                PeriodRow(label = stringResource(R.string.analys_cagr_label), amount = null, fraction = analysis.keyFigures.cagr)
            }
            ExpandableInfoRow(explanation = stringResource(R.string.analys_gav_explain)) {
                PeriodRow(
                    label = stringResource(R.string.analys_gav_label),
                    amount = analysis.keyFigures.gavPerShare,
                    fraction = analysis.keyFigures.gavFraction,
                )
            }
            ExpandableInfoRow(explanation = stringResource(R.string.analys_portfolio_share_explain)) {
                PeriodRow(
                    label = stringResource(R.string.analys_portfolio_share_label),
                    amount = null,
                    fraction = analysis.keyFigures.portfolioShareFraction,
                )
            }
        }
        AnalysisGlossary(modifier = Modifier.padding(top = 16.dp))
    }
}

/**
 * Utfällbara förklaringar per beräknad säljsignal (ANA-5) — färgprick visar nivån, texten
 * förklarar vad måttet betyder och uttryckligen inte betyder (aldrig ett säljbud, ANA-3).
 * Bara signaler med tillräcklig data (icke-null) visas — otillräckliga utelämnas (ANA-4).
 */
@Composable
private fun SignalExplanations(analysis: FundAnalysisCalc.Analysis, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        analysis.distanceFromHigh?.let { signal ->
            SignalRow(
                level = signal.level,
                label = stringResource(R.string.analys_signal_distance_label),
                explanation = stringResource(R.string.analys_signal_distance_explain),
            )
        }
        analysis.trend?.let { signal ->
            SignalRow(
                level = signal.level,
                label = stringResource(R.string.analys_signal_trend_label),
                explanation = stringResource(R.string.analys_signal_trend_explain),
            )
        }
        analysis.momentum?.let { signal ->
            SignalRow(
                level = signal.level,
                label = stringResource(R.string.analys_signal_momentum_label),
                explanation = stringResource(R.string.analys_signal_momentum_explain),
            )
        }
    }
}

@Composable
private fun SignalRow(level: FundAnalysisCalc.SignalLevel, label: String, explanation: String) {
    ExpandableInfoRow(explanation = explanation) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(level, modifier = Modifier.padding(end = 8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Kort "Så funkar analysen"-ordlista (ANA-6) — de begrepp appen faktiskt visar, var och en utfällbar. */
@Composable
private fun AnalysisGlossary(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(stringResource(R.string.analys_glossary_title), style = MaterialTheme.typography.titleSmall)
        val terms = listOf(
            R.string.analys_glossary_nav_term to R.string.analys_glossary_nav_def,
            R.string.analys_glossary_gav_term to R.string.analys_glossary_gav_def,
            R.string.analys_glossary_cagr_term to R.string.analys_glossary_cagr_def,
            R.string.analys_glossary_sma_term to R.string.analys_glossary_sma_def,
            R.string.analys_glossary_topp_term to R.string.analys_glossary_topp_def,
            R.string.analys_glossary_horisont_term to R.string.analys_glossary_horisont_def,
            R.string.analys_glossary_ranta_term to R.string.analys_glossary_ranta_def,
        )
        terms.forEach { (termRes, defRes) ->
            ExpandableInfoRow(explanation = stringResource(defRes)) {
                Text(stringResource(termRes), style = MaterialTheme.typography.bodyMedium)
            }
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
