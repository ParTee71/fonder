package se.partee71.fonder.ui.bytesfonster

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import se.partee71.fonder.ui.components.CardTitleRow
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.PeriodRow
import se.partee71.fonder.ui.components.RiskBadge
import se.partee71.fonder.ui.components.WorkingIndicator
import se.partee71.fonder.ui.diagram.ChartSeries
import se.partee71.fonder.ui.diagram.FundLineChart
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** Gör listan adresserbar för `performScrollToIndex` i tester (UI-5, samma skäl som Facit). */
const val SWITCH_WATCH_LIST_TEST_TAG = "switch_watch_list"

/**
 * Skärmen **Pågående byte** (ANA-12, issue #114) — den yta som saknades mellan "sålt" och
 * "köpt". Leder med jämförelsen: ett diagram med säljfonden och samtliga bevakade alternativ,
 * följt av en rad per alternativ med det tal beslutet faktiskt hänger på — utvecklingen sedan
 * säljdagen, inte fondens historik i stort.
 *
 * Ingen åtgärdsknapp utför något byte (samma princip som HEM-8/ANA-10): "Köpte den här"
 * registrerar vad användaren gjort och stänger bevakningen.
 */
@Composable
fun SwitchWatchScreen(
    onAddCandidate: () -> Unit,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SwitchWatchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // En stängd bevakning har ingen egen skärm att fylla — kvitteringen är avslutet, och att
    // ligga kvar på en död vy hade sett ut som att något mer skulle hända.
    LaunchedEffect(state.closed) {
        if (state.closed) onClosed()
    }

    SwitchWatchContent(
        state = state,
        onBought = viewModel::onBought,
        onRemoveCandidate = viewModel::onRemoveCandidate,
        onAddCandidate = onAddCandidate,
        onCancel = viewModel::onCancel,
        onMessageShown = viewModel::onMessageShown,
        modifier = modifier,
    )
}

/** Tillståndsdriven, testbar del av [SwitchWatchScreen] — inget ViewModel/Hilt-beroende. */
@Composable
fun SwitchWatchContent(
    state: SwitchWatchUiState,
    onBought: (String) -> Unit = {},
    onRemoveCandidate: (Long) -> Unit = {},
    onAddCandidate: () -> Unit = {},
    onCancel: () -> Unit = {},
    onMessageShown: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (state.missing) {
        EmptyState(
            title = stringResource(R.string.switch_watch_missing_title),
            body = stringResource(R.string.switch_watch_missing_body),
            modifier = modifier,
        )
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize().testTag(SWITCH_WATCH_LIST_TEST_TAG)) {
        item { HeaderCard(state) }
        item { ComparisonChart(state) }

        if (state.rows.isEmpty()) {
            item {
                // Ingen `EmptyState` här: den fyller skärmen och hade tryckt bort kortet med
                // säljfond, belopp och dag — den informationen gäller även utan alternativ.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val text = stringResource(
                        if (state.fillingCandidates) R.string.switch_watch_filling else R.string.switch_watch_empty_body,
                    )
                    // Snurran bara medan förslagen faktiskt hämtas (ANA-13) — "inga alternativ
                    // ännu" är ett färdigt svar, inte ett som är på väg.
                    WorkingIndicator(working = state.fillingCandidates, contentDescription = text)
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(state.rows, key = { it.candidateId }) { row ->
            CandidateCard(
                row = row,
                closed = state.closed,
                onBought = onBought,
                onRemoveCandidate = onRemoveCandidate,
            )
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (state.canAddCandidate) {
                    TextButton(onClick = onAddCandidate, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.switch_watch_add_candidate))
                    }
                }
                state.message?.let { message ->
                    Text(
                        text = when (message) {
                            SwitchWatchMessage.IsinUnavailable -> stringResource(R.string.switch_watch_isin_unavailable)
                            SwitchWatchMessage.CandidateLimitReached ->
                                stringResource(R.string.format_switch_watch_limit, SwitchWatchCalc.MAX_CANDIDATES)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    // Meddelandet är en kvittens på en åtgärd, inte ett tillstånd — det kvitteras
                    // när det visats så nästa tillägg inte möts av föregående försöks felmeddelande.
                    LaunchedEffect(message) { onMessageShown() }
                }
                if (!state.closed) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.switch_watch_cancel))
                    }
                }
                Text(
                    stringResource(R.string.switch_watch_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Vad som såldes, för hur mycket och hur länge sedan — sammanhanget varje rad under läses i. */
@Composable
private fun HeaderCard(state: SwitchWatchUiState) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardTitleRow(
                title = stringResource(
                    R.string.format_switch_watch_sold,
                    state.sellFundName,
                    LocalDate.ofEpochDay(state.soldAtEpochDay).format(dateFormatter),
                ),
                working = state.headerWorking,
                style = MaterialTheme.typography.titleSmall,
            )
            state.proceedsKr?.let { amount ->
                Text(
                    stringResource(R.string.format_switch_watch_amount, MoneyFormat.kr(amount)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val status = when {
                state.closed && state.boughtFundName != null ->
                    stringResource(R.string.format_switch_watch_bought, state.boughtFundName)
                state.closed -> stringResource(R.string.switch_watch_closed)
                state.expired -> stringResource(R.string.format_switch_watch_expired, state.ttlDays)
                else -> stringResource(R.string.format_switch_watch_day, state.daysWaiting, state.ttlDays)
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.expired && !state.closed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Säljfonden och alla bevakade alternativ i **ett** diagram (regel 4 — samma delade
 * [FundLineChart] som Fonddetaljs jämförelse, som redan kan flera serier). Serierna indexeras
 * till 100 vid periodens första gemensamma dag; rå NAV går inte att jämföra mellan fonder.
 */
@Composable
private fun ComparisonChart(state: SwitchWatchUiState) {
    if (!state.hasChart) {
        Text(
            stringResource(R.string.switch_watch_chart_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        return
    }
    FundLineChart(
        points = state.sellSeries,
        primaryLabel = state.sellFundName,
        comparisonSeries = state.candidateSeries.map { (label, points) -> ChartSeries(label = label, points = points) },
        working = state.chartWorking,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/**
 * Ett bevakat alternativ: namn, risknivå (UI-10), avgift och — talet beslutet hänger på —
 * utvecklingen sedan säljdagen via den delade [PeriodRow] (regel 4), som färgar efter procenten
 * och markerar en ej utvärderad rad som otillräcklig data i stället för som 0.
 */
@Composable
private fun CandidateCard(
    row: CandidateRow,
    closed: Boolean,
    onBought: (String) -> Unit,
    onRemoveCandidate: (Long) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                RiskBadge(level = row.riskLevel)
                // Radens egen väntesnurra (NAV-6): utvecklingen sedan säljdagen är talet
                // beslutet hänger på, och den kan inte visas förrän kandidatens historik landat.
                WorkingIndicator(
                    working = row.historyLoading,
                    contentDescription = stringResource(R.string.format_card_working, row.name),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            PeriodRow(
                label = stringResource(R.string.switch_watch_change_label),
                amount = row.changeKr,
                fraction = row.changeFraction,
                partial = row.partial,
                stackValue = true,
                modifier = Modifier.padding(top = 8.dp),
            )
            row.feePercent?.let { fee ->
                Text(
                    stringResource(R.string.format_switch_watch_fee, MoneyFormat.feePercent(fee)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.historyUnavailable) {
                Text(
                    stringResource(R.string.switch_watch_history_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!closed) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onBought(row.isin) }) {
                        Text(stringResource(R.string.switch_watch_bought_action))
                    }
                    // Bara handplockade alternativ går att ta bort: de automatiska är appens
                    // förslag för nivån, och att plocka bort ett av dem hade sett ut som ett val
                    // men bara dolt vad planen faktiskt föreslår.
                    if (row.manual) {
                        TextButton(onClick = { onRemoveCandidate(row.candidateId) }) {
                            Text(stringResource(R.string.switch_watch_remove_candidate))
                        }
                    }
                }
            }
        }
    }
}
