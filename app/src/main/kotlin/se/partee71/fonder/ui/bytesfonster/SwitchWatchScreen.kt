package se.partee71.fonder.ui.bytesfonster

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import se.partee71.fonder.R
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import se.partee71.fonder.ui.components.CardTitleRow
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.PeriodRow
import se.partee71.fonder.ui.components.PullToRefreshContainer
import se.partee71.fonder.ui.components.RiskBadge
import se.partee71.fonder.ui.components.RiskLevelPicker
import se.partee71.fonder.ui.components.WorkingIndicator
import se.partee71.fonder.ui.diagram.ChartSeries
import se.partee71.fonder.ui.diagram.FundLineChart
import se.partee71.ui.components.AppButton
import se.partee71.ui.components.AppCard
import se.partee71.ui.components.ButtonVariant
import se.partee71.ui.components.SwitchRow
import se.partee71.ui.theme.Spacing

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
    watchId: Long,
    onAddCandidate: () -> Unit,
    onBought: (String) -> Unit,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SwitchWatchViewModel = hiltViewModel<SwitchWatchViewModel, SwitchWatchViewModel.Factory> { it.create(watchId) }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // En stängd bevakning har ingen egen skärm att fylla — kvitteringen är avslutet, och att
    // ligga kvar på en död vy hade sett ut som att något mer skulle hända.
    val close by rememberUpdatedState(onClosed)
    val closed = state.content.closed
    LaunchedEffect(closed) {
        if (closed) close()
    }

    SwitchWatchContent(
        state = state,
        onRefresh = viewModel::refresh,
        onBought = onBought,
        onRemoveCandidate = viewModel::onRemoveCandidate,
        onAddCandidate = onAddCandidate,
        onCancel = viewModel::onCancel,
        onMessageShown = viewModel::onMessageShown,
        onRetryAutoFill = viewModel::onRetryAutoFill,
        onSelectTargetLevel = viewModel::onSelectTargetLevel,
        onIndexOnlyChanged = viewModel::onIndexOnlyChanged,
        modifier = modifier
    )
}

/** Tillståndsdriven, testbar del av [SwitchWatchScreen] — inget ViewModel/Hilt-beroende. */
@Composable
fun SwitchWatchContent(
    state: SwitchWatchUiState,
    onRefresh: () -> Unit = {},
    onBought: (String) -> Unit = {},
    onRemoveCandidate: (Long) -> Unit = {},
    onAddCandidate: () -> Unit = {},
    onCancel: () -> Unit = {},
    onMessageShown: () -> Unit = {},
    onRetryAutoFill: () -> Unit = {},
    onSelectTargetLevel: (Int) -> Unit = {},
    onIndexOnlyChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    state.error?.let { error ->
        // Bevakningen finns inte (raderad, eller en gammal länk) — skärmen säger det i stället för
        // att visa tomt; ett läsfel visas som fel (NFR-6).
        if (error.cause == DataError.NotFound) {
            EmptyState(
                title = stringResource(R.string.switch_watch_missing_title),
                body = stringResource(R.string.switch_watch_missing_body),
                modifier = modifier
            )
        } else {
            EmptyState(error = error, modifier = modifier)
        }
        return
    }
    val content = state.content

    // Dra ned hämtar om kandidaternas kurvor och säljfondens historik (UI-11). Kandidaterna
    // ligger inte i kurscachen (ANA-11), så det är den enda vägen till färskare tal än de som
    // hämtades när skärmen öppnades.
    PullToRefreshContainer(
        refreshing = state.chartWorking,
        onRefresh = onRefresh,
        modifier = modifier
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize().testTag(SWITCH_WATCH_LIST_TEST_TAG)) {
            item(contentType = "header") { HeaderCard(state) }
            // Nivå och filter går bara att ändra medan bytet pågår (ANA-13) — en stängd
            // bevakning bär vad som hände och visar ingen väljare.
            if (state.canChangeSelection) {
                item(contentType = "target-level") {
                    TargetLevelCard(
                        state = state,
                        onSelectTargetLevel = onSelectTargetLevel,
                        onIndexOnlyChanged = onIndexOnlyChanged
                    )
                }
            }
            item(contentType = "chart") { ComparisonChart(state) }

            if (content.rows.isEmpty()) {
                item(contentType = "empty") {
                    // Ingen `EmptyState` här: den fyller skärmen och hade tryckt bort kortet med
                    // säljfond, belopp och dag — den informationen gäller även utan alternativ.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)
                    ) {
                        val text = when {
                            state.fillingCandidates -> stringResource(R.string.switch_watch_filling)

                            // Med filtret är "inga köpbara indexfonder" ett annat besked än inga
                            // köpbara fonder alls — och det säger vad som går att ändra (issue #150).
                            content.emptyBecauseOfIndexFilter && content.targetLevel != null ->
                                stringResource(R.string.format_switch_watch_empty_index_body, content.targetLevel)

                            // Tre orsaker som förr delade samma text (issue #149) — var och en
                            // säger vad användaren kan göra åt den.
                            else -> when (content.emptyReason) {
                                SwitchWatchEmptyReason.NO_TARGET_LEVEL ->
                                    stringResource(R.string.format_switch_watch_empty_no_level, content.sellFundName)

                                SwitchWatchEmptyReason.NONE_AVAILABLE ->
                                    content.targetLevel
                                        ?.let { stringResource(R.string.format_switch_watch_empty_none_available, it) }
                                        ?: stringResource(R.string.switch_watch_empty_body)

                                SwitchWatchEmptyReason.LOOKUP_FAILED -> stringResource(R.string.switch_watch_empty_lookup_failed)

                                null -> stringResource(R.string.switch_watch_empty_body)
                            }
                        }
                        // Snurran bara medan förslagen faktiskt hämtas (ANA-13) — "inga alternativ
                        // ännu" är ett färdigt svar, inte ett som är på väg.
                        WorkingIndicator(working = state.fillingCandidates, contentDescription = text)
                        Column {
                            if (!state.fillingCandidates && content.emptyBecauseOfIndexFilter && content.targetLevel != null) {
                                Text(
                                    stringResource(R.string.format_switch_watch_empty_index_title, content.targetLevel),
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            Text(
                                text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                // Bara när kontrollen misslyckades: "inga köpbara" och "okänd nivå" blir inte
                // annorlunda av ett nytt försök, ett misslyckat uppslag kan bli det.
                if (content.emptyReason == SwitchWatchEmptyReason.LOOKUP_FAILED) {
                    item(contentType = "retry") {
                        AppButton(
                            text = stringResource(R.string.switch_watch_retry),
                            onClick = onRetryAutoFill,
                            modifier = Modifier.padding(horizontal = Spacing.l),
                            variant = ButtonVariant.Text
                        )
                    }
                }
            }

            // Rubriken över alternativen: vad talen mäter och vilken nivå (och filter) förslagen gäller.
            if (content.rows.isNotEmpty() && content.targetLevel != null) {
                item(contentType = "heading") {
                    Text(
                        stringResource(
                            if (content.indexOnly) {
                                R.string.format_switch_watch_alternatives_heading_index
                            } else {
                                R.string.format_switch_watch_alternatives_heading
                            },
                            content.targetLevel
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = Spacing.xs)
                    )
                }
            }

            items(content.rows, key = { it.candidateId }, contentType = { "candidate" }) { row ->
                CandidateCard(
                    row = row,
                    closed = content.closed,
                    onBought = onBought,
                    onRemoveCandidate = onRemoveCandidate
                )
            }

            item(contentType = "add-candidate") {
                Column(modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
                    if (state.canAddCandidate) {
                        AppButton(
                            text = stringResource(R.string.switch_watch_add_candidate),
                            onClick = onAddCandidate,
                            modifier = Modifier.padding(top = Spacing.xs),
                            variant = ButtonVariant.Text
                        )
                    }
                    state.message?.let { message ->
                        Text(
                            text = when (message) {
                                SwitchWatchMessage.IsinUnavailable -> stringResource(R.string.switch_watch_isin_unavailable)

                                SwitchWatchMessage.CandidateLimitReached ->
                                    stringResource(R.string.format_switch_watch_limit, SwitchWatchCalc.MAX_CANDIDATES)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        // Meddelandet är en kvittens på en åtgärd, inte ett tillstånd — det kvitteras
                        // när det visats så nästa tillägg inte möts av föregående försöks felmeddelande.
                        val messageShown by rememberUpdatedState(onMessageShown)
                        LaunchedEffect(message) { messageShown() }
                    }
                    if (!content.closed) {
                        AppButton(
                            text = stringResource(R.string.switch_watch_cancel),
                            onClick = onCancel,
                            variant = ButtonVariant.Text
                        )
                    }
                    if (state.canChangeSelection) {
                        Text(
                            stringResource(R.string.switch_watch_level_footer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.s)
                        )
                    }
                    Text(
                        stringResource(R.string.switch_watch_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.s)
                    )
                }
            }
        }
    }
}

/** Vad som såldes, för hur mycket och hur länge sedan — sammanhanget varje rad under läses i. */
@Composable
private fun HeaderCard(state: SwitchWatchUiState) {
    val content = state.content
    AppCard(modifier = Modifier.fillMaxWidth().padding(Spacing.l)) {
        CardTitleRow(
            title = stringResource(
                R.string.format_switch_watch_sold,
                content.sellFundName,
                LocalDate.ofEpochDay(content.soldAtEpochDay).format(dateFormatter)
            ),
            working = state.headerWorking,
            style = MaterialTheme.typography.titleSmall
        )
        content.proceedsKr?.let { amount ->
            Text(
                stringResource(R.string.format_switch_watch_amount, MoneyFormat.kr(amount)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val status = when {
            content.closed && content.boughtFundName != null ->
                stringResource(R.string.format_switch_watch_bought, content.boughtFundName)

            content.closed -> stringResource(R.string.switch_watch_closed)

            content.expired -> stringResource(R.string.format_switch_watch_expired, content.ttlDays)

            else -> stringResource(R.string.format_switch_watch_day, content.daysWaiting, content.ttlDays)
        }
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (content.expired && !content.closed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = Spacing.xs)
        )
    }
}

/**
 * Risknivån appen föreslår fonder på och filtret "Bara indexfonder" (ANA-13, issue #150). Nivån
 * väljs i den delade [RiskLevelPicker] med säljfondens egen nivå markerad, så användaren ser vad
 * "något likvärdigt" hade varit; filtret är en delad [SwitchRow]. Båda byter appens förslag —
 * ViewModeln tar bort de gamla och hämtar nya, de handplockade ligger kvar.
 */
@Composable
private fun TargetLevelCard(
    state: SwitchWatchUiState,
    onSelectTargetLevel: (Int) -> Unit,
    onIndexOnlyChanged: (Boolean) -> Unit
) {
    val content = state.content
    AppCard(modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.l)) {
        Text(stringResource(R.string.switch_watch_level_title), style = MaterialTheme.typography.titleSmall)
        RiskLevelPicker(
            selected = content.targetLevel,
            onSelect = onSelectTargetLevel,
            marked = content.sellLevel,
            markedDescription = stringResource(R.string.switch_watch_level_marked),
            modifier = Modifier.padding(top = Spacing.m)
        )
        Text(
            levelHint(state),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.s)
        )
        SwitchRow(
            title = stringResource(R.string.switch_watch_index_only),
            checked = content.indexOnly,
            onCheckedChange = onIndexOnlyChanged,
            modifier = Modifier.padding(top = Spacing.xs)
        )
    }
}

/**
 * Förklaringsraden under nivåväljaren (issue #150). Med riskprofil säger den vad profilen saknar
 * på den valda nivån ("9 procentenheter för lite där") eller, när en annan nivå är vald, var
 * profilen saknar mest; utan profil vilken nivå appen föreslår på och var säljfondens egen nivå
 * ligger. Texten säger aldrig att nivån *förvaldes*: bevakningen bär inte vem som valde den (en
 * planrad, profilen eller användaren), och ett sådant fält finns inte i backupkontraktet.
 */
@Composable
private fun levelHint(state: SwitchWatchUiState): String {
    val content = state.content
    val target = content.targetLevel ?: return stringResource(R.string.switch_watch_level_hint_none)
    val sell = content.sellLevel
    val profileLevel = content.profileSuggestedLevel
    val gap = content.profileGapPp
    return when {
        profileLevel != null && gap != null && profileLevel == target -> {
            val reason = stringResource(R.string.format_switch_watch_level_hint_profile, target, gap)
            when (sell) {
                null -> reason
                target -> "$reason ${stringResource(R.string.switch_watch_level_hint_frame_same)}"
                else -> "$reason ${stringResource(R.string.format_switch_watch_level_hint_frame, sell)}"
            }
        }

        profileLevel != null -> stringResource(R.string.format_switch_watch_level_hint_profile_other, target, profileLevel)

        sell == null -> stringResource(R.string.format_switch_watch_level_hint_unknown_sell, target)

        sell == target -> stringResource(R.string.format_switch_watch_level_hint_same, target)

        else -> stringResource(R.string.format_switch_watch_level_hint, target, sell)
    }
}

/**
 * Säljfonden och alla bevakade alternativ i **ett** diagram (regel 4 — samma delade
 * [FundLineChart] som Fonddetaljs jämförelse, som redan kan flera serier). Serierna indexeras
 * till 100 vid periodens första gemensamma dag; rå NAV går inte att jämföra mellan fonder.
 */
@Composable
private fun ComparisonChart(state: SwitchWatchUiState) {
    val content = state.content
    if (!content.hasChart) {
        Text(
            stringResource(R.string.switch_watch_chart_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.l)
        )
        return
    }
    FundLineChart(
        points = content.sellSeries,
        primaryLabel = content.sellFundName,
        comparisonSeries = content.candidateSeries.map { (label, points) -> ChartSeries(label = label, points = points) },
        working = state.chartWorking,
        modifier = Modifier.padding(horizontal = Spacing.l)
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
    onRemoveCandidate: (Long) -> Unit
) {
    AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = Spacing.s)
            )
            RiskBadge(level = row.riskLevel)
            // Radens egen väntesnurra (NAV-6): utvecklingen sedan säljdagen är talet
            // beslutet hänger på, och den kan inte visas förrän kandidatens historik landat.
            WorkingIndicator(
                working = row.historyLoading,
                contentDescription = stringResource(R.string.format_card_working, row.name),
                modifier = Modifier.padding(start = Spacing.s)
            )
        }
        PeriodRow(
            label = stringResource(R.string.switch_watch_change_label),
            amount = row.changeKr,
            fraction = row.changeFraction,
            partial = row.partial,
            stackValue = true,
            modifier = Modifier.padding(top = Spacing.s)
        )
        row.feePercent?.let { fee ->
            Text(
                stringResource(R.string.format_switch_watch_fee, MoneyFormat.feePercent(fee)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (row.historyUnavailable) {
            Text(
                stringResource(R.string.switch_watch_history_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!closed) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                AppButton(
                    text = stringResource(R.string.switch_watch_bought_action),
                    onClick = { onBought(row.isin) },
                    variant = ButtonVariant.Text
                )
                // Bara handplockade alternativ går att ta bort: de automatiska är appens
                // förslag för nivån, och att plocka bort ett av dem hade sett ut som ett val
                // men bara dolt vad planen faktiskt föreslår.
                if (row.manual) {
                    AppButton(
                        text = stringResource(R.string.switch_watch_remove_candidate),
                        onClick = { onRemoveCandidate(row.candidateId) },
                        variant = ButtonVariant.Text
                    )
                }
            }
        }
    }
}
