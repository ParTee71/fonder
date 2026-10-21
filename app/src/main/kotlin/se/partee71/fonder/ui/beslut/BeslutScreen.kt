package se.partee71.fonder.ui.beslut

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.OpenSwitchWatch
import se.partee71.fonder.domain.usecase.SuggestionDismissals
import se.partee71.fonder.domain.usecase.SwitchOrigin
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.domain.usecase.SwitchRow
import se.partee71.fonder.domain.usecase.SwitchRowState
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import se.partee71.fonder.ui.components.CenteredState
import se.partee71.fonder.ui.components.ExpandableSection
import se.partee71.fonder.ui.riskprofil.labelRes
import se.partee71.fonder.ui.settings.labelRes
import se.partee71.fonder.ui.theme.Copper
import se.partee71.fonder.ui.theme.Gain
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.fonder.ui.theme.Neutral
import se.partee71.fonder.ui.theme.ReturnColors
import se.partee71.fonder.ui.theme.Tertiary
import se.partee71.fonder.ui.theme.flushCardStyle
import se.partee71.ui.components.AppButton
import se.partee71.ui.components.AppCard
import se.partee71.ui.components.AppDivider
import se.partee71.ui.components.ButtonVariant
import se.partee71.ui.components.EmptyState
import se.partee71.ui.components.InfoPill
import se.partee71.ui.components.ItemRow
import se.partee71.ui.components.ItemRowText
import se.partee71.ui.components.LoadErrorState
import se.partee71.ui.components.SectionHeader
import se.partee71.ui.theme.AppTheme
import se.partee71.ui.theme.Spacing
import se.partee71.ui.theme.Tone

/** Gör Besluts lista adresserbar för `performScrollToIndex` i tester (UI-5, samma mönster som Hem och Facit). */
const val BESLUT_LIST_TEST_TAG = "beslut_list"

@Composable
fun BeslutScreen(
    onOpenSwitchWatch: (Long) -> Unit,
    onOpenRiskProfile: () -> Unit,
    onOpenFacit: () -> Unit,
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BeslutViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // En nystartad bevakning öppnas direkt: den är tom tills kandidaterna hämtats, och att
    // lämna användaren kvar på Beslut hade sett ut som att knappen inte gjorde något.
    val openSwitchWatch by rememberUpdatedState(onOpenSwitchWatch)
    LaunchedEffect(state.startedSwitchWatchId) {
        state.startedSwitchWatchId?.let { watchId ->
            openSwitchWatch(watchId)
            viewModel.onEvent(BeslutEvent.SwitchWatchOpened)
        }
    }

    BeslutContent(
        state = state,
        onOpenRiskProfile = onOpenRiskProfile,
        onOpenSwitchWatch = onOpenSwitchWatch,
        onFollow = onFollow,
        onStartSwitchWatch = { viewModel.onEvent(BeslutEvent.StartSwitchWatch(it)) },
        onDismissSuggestion = { viewModel.onEvent(BeslutEvent.DismissSuggestion(it)) },
        onUndoDismissal = { viewModel.onEvent(BeslutEvent.UndoDismissal(it)) },
        onRecomputeSwitchPlan = { viewModel.onEvent(BeslutEvent.RecomputeSwitchPlan) },
        onOpenFacit = onOpenFacit,
        onRetry = { viewModel.onEvent(BeslutEvent.Retry) },
        modifier = modifier
    )
}

/**
 * Tillståndsdriven, testbar del av [BeslutScreen] (NAV-9) — inget ViewModel/Hilt-beroende. En
 * `LazyColumn` hela vägen, även i tomt läge: rubrikraden, omräkningen, de avslutade och vägen till
 * Facit gäller också när inget väntar, och allt som kan växa ska gå att skrolla (UI-5).
 *
 * Listan "Byten" (BYT-4) visar varje byte på **en** rad med ett tillståndschip — sålda först, sedan
 * planens förslag och de billigare alternativen — och de avslutade och avstådda i en hopfälld sektion.
 */
@Composable
fun BeslutContent(
    state: BeslutUiState,
    modifier: Modifier = Modifier,
    onOpenRiskProfile: () -> Unit = {},
    onOpenSwitchWatch: (Long) -> Unit = {},
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit = {},
    onStartSwitchWatch: (SwitchPlanResolver.Suggestion) -> Unit = {},
    onDismissSuggestion: (SwitchPlanResolver.Suggestion) -> Unit = {},
    onUndoDismissal: (SwitchPlanResolver.Suggestion) -> Unit = {},
    onRecomputeSwitchPlan: () -> Unit = {},
    onOpenFacit: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    // Läsfel (NFR-6): bara felet med "Försök igen" – profilraden och Facit-raden hade annars visat
    // standardvärden som om de vore data.
    state.error?.let { error ->
        CenteredState(modifier) { LoadErrorState(stringResource(R.string.error_load_title), error, onRetry) }
        return
    }
    val switches = state.switches
    LazyColumn(modifier = modifier.fillMaxSize().testTag(BESLUT_LIST_TEST_TAG)) {
        item(contentType = "profile-header") { ProfileHeader(state.riskProfile, state.accountType, onOpenRiskProfile) }

        if (state.isEmpty) {
            item(contentType = "empty") {
                EmptyState(
                    icon = R.drawable.ic_decisions,
                    title = stringResource(R.string.beslut_empty_title),
                    message = stringResource(R.string.beslut_empty_body)
                )
            }
        } else {
            item(contentType = "switches-heading") { SwitchesHeading(waiting = switches.waitingCount, working = state.switchesWorking) }
            items(switches.active, key = { it.key }, contentType = { "switch-row" }) { row ->
                SwitchRowCard(
                    row = row,
                    onOpenSwitchWatch = onOpenSwitchWatch,
                    onFollow = onFollow,
                    onStartSwitchWatch = onStartSwitchWatch,
                    onDismiss = onDismissSuggestion
                )
            }
        }

        // Planens förbehåll (HEM-8) så länge planen har ett förslag på listan.
        if (switches.active.any { it.state == SwitchRowState.PROPOSED }) {
            item(contentType = "caption") {
                CaptionText(stringResource(R.string.switch_plan_disclaimer) + " " + stringResource(R.string.beslut_never_switches))
            }
        }

        // Omräkning på begäran (HEM-8, issue #88) — bara när en plan alls kan ges (ISK/KF + satt
        // profil, SET-3/SET-4); även utan plan, för det är just då man vill fråga igen. Släckt
        // medan något jobb kör, samma signal som chromets indikator (NAV-6).
        if (state.canRecomputeSwitchPlan) {
            item(contentType = "recompute") {
                Column(modifier = Modifier.padding(horizontal = Spacing.l)) {
                    AppButton(
                        text = stringResource(
                            if (state.backgroundWorkRunning) R.string.switch_plan_recompute_running else R.string.switch_plan_recompute
                        ),
                        onClick = onRecomputeSwitchPlan,
                        enabled = !state.backgroundWorkRunning,
                        variant = ButtonVariant.Text
                    )
                    ItemRowText(title = null, subtitle = stringResource(R.string.switch_plan_recompute_explain))
                }
            }
        }

        // Avslutade (14 dagar efter stängningen) och avstådda (HEM-14) — hopfällt; ingen snackbar finns
        // i appen, så ångra-vägen är raden här.
        if (switches.closed.isNotEmpty()) {
            item(contentType = "closed") { ClosedSection(switches.closed, onUndo = onUndoDismissal) }
        }

        item(contentType = "section-heading") { SectionHeading(stringResource(R.string.beslut_outcome_title)) }
        item(contentType = "facit-row") { FacitRow(followedCount = state.followedCount, followedExcessKr = state.followedExcessKr, onOpen = onOpenFacit) }
    }
}

/**
 * "Mot riskprofil 4,00" med "Balanserad tillväxt · ISK/KF" under och "Ändra" till höger — vad förslagen
 * räknas mot. Målet och kontotypen utelämnas när de inte är satta i stället för att gissas; utan profil
 * sägs det ut.
 */
@Composable
private fun ProfileHeader(riskProfile: RiskProfile?, accountType: AccountType?, onChange: () -> Unit) {
    val level = riskProfile?.weightedTargetLevel
    val title = if (level != null) {
        stringResource(R.string.format_beslut_risk_profile, MoneyFormat.decimal(level))
    } else {
        stringResource(R.string.beslut_no_risk_profile)
    }
    val details = listOfNotNull(
        riskProfile?.answers?.goal?.let { stringResource(it.labelRes()) },
        accountType?.let { stringResource(it.labelRes()) }
    )
    ItemRow(
        title = title,
        subtitle = details.joinToString(" · ").ifEmpty { null },
        modifier = Modifier.padding(horizontal = Spacing.xs),
        trailing = {
            AppButton(
                text = stringResource(R.string.beslut_change_risk_profile),
                onClick = onChange,
                variant = ButtonVariant.Text
            )
        }
    )
}

/**
 * Avsnittsrubrik (20 sp) med plats för väntesnurran (NAV-6) och ett antal i en rund pill – android-ui:s
 * [SectionHeader], i linje med korten under.
 */
@Composable
private fun SectionHeading(title: String, working: Boolean = false, count: String? = null) {
    SectionHeader(
        title = title,
        modifier = Modifier.padding(start = Spacing.m, end = Spacing.m, top = Spacing.m),
        count = count,
        working = working,
        workingDescription = stringResource(R.string.format_card_working, title)
    )
}

@Composable
private fun CaptionText(text: String) {
    ItemRowText(title = null, subtitle = text, modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s))
}

/** "Byten · N väntar på dig" — listans rubrik med väntesnurran (NAV-6) och antalet som väntar i en pill. */
@Composable
private fun SwitchesHeading(waiting: Int, working: Boolean) {
    SectionHeading(
        title = stringResource(R.string.beslut_switches_title),
        working = working,
        count = if (waiting > 0) stringResource(R.string.format_beslut_switches_waiting, waiting) else null
    )
}

/** Chipets text och ton för en rad (BYT-4) — tillståndet står alltid i text (UI-3). */
@Composable
private fun SwitchRowChip(row: SwitchRow) {
    val text = when (row.state) {
        SwitchRowState.PROPOSED -> stringResource(R.string.switch_state_proposed)

        SwitchRowState.FEE -> stringResource(R.string.switch_state_fee)

        SwitchRowState.SOLD -> stringResource(
            R.string.format_switch_state_sold,
            (row as SwitchRow.Sold).watch.daysWaiting,
            SwitchWatchCalc.WATCH_TTL_DAYS
        )

        SwitchRowState.BOUGHT -> (row as? SwitchRow.Closed)?.closedAtEpochDay
            ?.let { stringResource(R.string.format_switch_state_bought, shortDate(LocalDate.ofEpochDay(it))) }
            ?: stringResource(R.string.switch_state_bought)

        SwitchRowState.CANCELLED -> stringResource(R.string.switch_state_cancelled)

        SwitchRowState.EXPIRED -> stringResource(R.string.switch_state_expired)

        SwitchRowState.DISMISSED -> stringResource(R.string.switch_state_dismissed)
    }
    val tone = when (row.state) {
        SwitchRowState.PROPOSED -> Tone.Primary
        SwitchRowState.FEE -> Tone.Tertiary
        SwitchRowState.SOLD -> Tone.Copper
        SwitchRowState.BOUGHT -> Tone.Gain
        SwitchRowState.CANCELLED, SwitchRowState.EXPIRED, SwitchRowState.DISMISSED -> Tone.Neutral
    }
    InfoPill(text = text, tone = tone, shape = AppTheme.shapes.smallTile)
}

/** "7 okt" — dag och kort månad på svenska, utan punkt. */
@Composable
private fun shortDate(date: LocalDate): String = "${date.dayOfMonth} ${stringArrayResource(R.array.month_short)[date.monthValue - 1]}"

/**
 * En rad i listan "Byten" (BYT-4): chip och ursprung, rubrik, underlag och radens beslut.
 *
 * - **Föreslaget** (planen, HEM-8) och **Avgift** (billigare alternativ, ANA-9): **Registrera säljet**
 *   öppnar säljformuläret förifyllt (BYT-2) — det sparade säljet gör raden Sålt — och **Avstå**
 *   (HEM-14) döljer paret. För planen kommer rangordningen ur [SwitchPlanResolver.Suggestion.planIndex],
 *   aldrig ur listpositionen (issue #75). Ett förslag kvitterat i Facit (SET-5) erbjuder i stället
 *   "Bevaka alternativ", och bara när säljfonden inte redan bevakas.
 * - **Sålt** (ANA-12): likviden, de bevakade alternativen och det som gått bäst sedan säljdagen —
 *   ur **cachade** kurser — och **Öppna bevakningen**, där köpet registreras.
 */
@Composable
private fun SwitchRowCard(
    row: SwitchRow,
    onOpenSwitchWatch: (Long) -> Unit,
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit,
    onStartSwitchWatch: (SwitchPlanResolver.Suggestion) -> Unit,
    onDismiss: (SwitchPlanResolver.Suggestion) -> Unit
) {
    AppCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
        style = flushCardStyle
    ) {
        when (row) {
            is SwitchRow.Suggested -> SuggestedRow(row, onFollow, onStartSwitchWatch, onDismiss)

            is SwitchRow.Sold -> SoldRow(row, onOpenSwitchWatch)

            // Avslutade och avstådda står i den hopfällda sektionen, aldrig bland de väntande.
            is SwitchRow.Closed, is SwitchRow.Dismissed -> Unit
        }
    }
}

/** Radens innehåll i en nod (chip, ursprung, rubrik, underlag) — skärmläsaren läser bytet i ett svep. */
@Composable
private fun SwitchRowText(row: SwitchRow, origin: String, title: String, details: List<String>, lead: (@Composable () -> Unit)? = null) {
    ItemRow(
        title = title,
        modifier = Modifier.semantics(mergeDescendants = true) {},
        subtitle = details.joinToString(" · ").ifEmpty { null },
        overline = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                SwitchRowChip(row)
                Text(origin, style = AppTheme.typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        below = lead
    )
}

/** Radens knappar, högerställda som i mockupen: sekundär textknapp före den konturerade huvudknappen. */
@Composable
private fun SwitchRowActions(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.s, end = Spacing.s, bottom = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
private fun ColumnScope.SuggestedRow(
    row: SwitchRow.Suggested,
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit,
    onStartSwitchWatch: (SwitchPlanResolver.Suggestion) -> Unit,
    onDismiss: (SwitchPlanResolver.Suggestion) -> Unit
) {
    val suggestion = row.suggestion
    val cheaper = row.cheaper
    if (cheaper == null) {
        SwitchRowText(
            row = row,
            origin = stringResource(R.string.format_switch_origin_plan_step, suggestion.planIndex + 1),
            title = stringResource(
                R.string.format_switch_plan_row,
                suggestion.planIndex + 1,
                suggestion.sellFundName,
                suggestion.fromLevel,
                suggestion.buyFundName,
                suggestion.toLevel
            ),
            details = listOfNotNull(
                suggestion.switchValueKr?.let { stringResource(R.string.format_switch_plan_amount, MoneyFormat.kr(it)) },
                stringResource(R.string.format_switch_plan_fee_delta, MoneyFormat.percentSigned(suggestion.feeDeltaPercent / 100.0))
            )
        )
    } else {
        SwitchRowText(
            row = row,
            origin = stringResource(R.string.fee_comparison_title),
            title = stringResource(R.string.format_beslut_switch_pair, suggestion.sellFundName, suggestion.buyFundName),
            details = listOfNotNull(
                cheaper.annualSavingsKr?.let { stringResource(R.string.format_beslut_cheaper_savings, MoneyFormat.kr(it)) },
                stringResource(R.string.format_beslut_cheaper_fees, MoneyFormat.feePercent(cheaper.sellFeePercent), MoneyFormat.feePercent(cheaper.buyFeePercent))
            )
        )
    }
    SwitchRowActions {
        if (suggestion.followed) {
            // Kvitterat i Facit (SET-5) utan ett registrerat sälj — bevakningen kan startas utan
            // transaktion (HEM-8/ANA-12); kvitteringen ändras bara i Facit.
            if (!row.watched) {
                AppButton(
                    text = stringResource(R.string.switch_watch_start),
                    onClick = { onStartSwitchWatch(suggestion) },
                    variant = ButtonVariant.Text
                )
            }
        } else {
            AppButton(
                text = stringResource(R.string.beslut_dismiss),
                onClick = { onDismiss(suggestion) },
                variant = ButtonVariant.Text,
                contentDescription = stringResource(R.string.format_beslut_dismiss_a11y, suggestion.sellFundName, suggestion.buyFundName)
            )
            AppButton(
                text = stringResource(R.string.beslut_register_sale),
                onClick = { onFollow(suggestion) },
                variant = ButtonVariant.Outlined,
                contentDescription = stringResource(R.string.format_beslut_register_sale_a11y, suggestion.sellFundName, suggestion.buyFundName)
            )
        }
    }
}

@Composable
private fun ColumnScope.SoldRow(row: SwitchRow.Sold, onOpen: (Long) -> Unit) {
    val watch = row.watch
    SwitchRowText(
        row = row,
        origin = when (row.origin) {
            SwitchOrigin.PLAN -> row.planIndex?.let { stringResource(R.string.format_switch_origin_plan_step, it + 1) }
                ?: stringResource(R.string.switch_origin_plan)

            SwitchOrigin.FEE -> stringResource(R.string.fee_comparison_title)

            SwitchOrigin.STANDALONE -> stringResource(R.string.switch_origin_standalone)
        },
        title = stringResource(R.string.format_beslut_sold_title, watch.sellFundName),
        details = listOfNotNull(
            watch.proceedsKr?.let { stringResource(R.string.format_beslut_sold_proceeds, MoneyFormat.kr(it)) },
            pluralStringResource(R.plurals.format_beslut_sold_candidates, watch.candidateCount, watch.candidateCount)
        ),
        lead = { SoldLeader(watch) }
    )
    SwitchRowActions {
        AppButton(
            text = stringResource(R.string.beslut_open_watch),
            onClick = { onOpen(watch.watchId) },
            variant = ButtonVariant.Outlined,
            contentDescription = stringResource(R.string.format_beslut_open_watch_a11y, watch.sellFundName)
        )
    }
}

/**
 * "Bäst sedan säljdagen: Global Indexfond A +1,2 %" — ledaren ur cachade kurser, utvecklingen med
 * tecken och i vinst- eller förlustfärg (UI-3). Inget utvärderat än sägs ut (ANA-4-principen).
 */
@Composable
private fun SoldLeader(watch: OpenSwitchWatch) {
    val name = watch.leaderName
    val change = watch.leaderChangeFraction
    if (name == null || change == null) {
        Text(
            stringResource(R.string.beslut_switch_watch_no_leader),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val label = stringResource(R.string.beslut_sold_leader_label)
    val color = ReturnColors.forAmount(change)
    Text(
        buildAnnotatedString {
            append(label)
            append(" ")
            withStyle(SpanStyle(color = color)) {
                append(name)
                append(" ")
                withStyle(MonoAmountStyle.toSpanStyle()) { append(MoneyFormat.percentSigned(change)) }
            }
        },
        style = MaterialTheme.typography.bodySmall
    )
}

/**
 * "Avslutade och avstådda (N)" (BYT-4, HEM-14) — hopfälld som standard: byten stängda som köpta,
 * avbrutna eller utgångna i [SwitchWatchCalc.WATCH_TTL_DAYS] dagar efter stängningen (sedan bara i
 * Facit), och de avstådda förslagen med **Ångra**, som visar förslaget igen.
 */
@Composable
private fun ClosedSection(rows: List<SwitchRow>, onUndo: (SwitchPlanResolver.Suggestion) -> Unit) {
    ExpandableSection(
        title = stringResource(R.string.format_beslut_closed_title, rows.size),
        modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = Spacing.s)
    ) {
        Column {
            ItemRowText(
                title = null,
                subtitle = stringResource(R.string.format_beslut_closed_explain, SwitchWatchCalc.WATCH_TTL_DAYS, SuggestionDismissals.TTL_DAYS)
            )
            rows.forEach { row ->
                AppDivider(Modifier.padding(top = Spacing.s))
                ClosedRow(row, onUndo)
            }
        }
    }
}

@Composable
private fun ClosedRow(row: SwitchRow, onUndo: (SwitchPlanResolver.Suggestion) -> Unit) {
    val title = when (row) {
        is SwitchRow.Closed -> when {
            row.state != SwitchRowState.BOUGHT -> stringResource(R.string.format_beslut_closed_no_buy, row.sellFundName)
            row.boughtFundName != null -> stringResource(R.string.format_beslut_switch_watch_title, row.sellFundName, row.boughtFundName)
            else -> stringResource(R.string.format_beslut_closed_bought_unknown, row.sellFundName)
        }

        is SwitchRow.Dismissed -> stringResource(R.string.format_beslut_switch_pair, row.suggestion.sellFundName, row.suggestion.buyFundName)

        is SwitchRow.Suggested, is SwitchRow.Sold -> return
    }
    ItemRow(
        title = title,
        leading = { SwitchRowChip(row) },
        trailing = if (row is SwitchRow.Dismissed) {
            {
                AppButton(
                    text = stringResource(R.string.beslut_undo_dismissal),
                    onClick = { onUndo(row.suggestion) },
                    variant = ButtonVariant.Text,
                    contentDescription = stringResource(
                        R.string.format_beslut_undo_dismissal_a11y,
                        row.suggestion.sellFundName,
                        row.suggestion.buyFundName
                    )
                )
            }
        } else {
            null
        }
    )
}

/**
 * Vägen till Facit (SET-5) — en skärm djupare i stacken. Visar antalet genomförda förslag och, när
 * det finns ett mätbart utfall, deras meravkastning i kronor till höger ([BeslutUiState.followedExcessKr]):
 * summan av Facits två "Genomförda"-rader, aldrig ett procentsnitt över sorterna. Utan mätbart utfall
 * visas bara antalet — aldrig 0 kr (ANA-4-principen).
 */
@Composable
private fun FacitRow(followedCount: Int, followedExcessKr: Double?, onOpen: () -> Unit) {
    AppCard(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, top = Spacing.xs, bottom = Spacing.l),
        style = flushCardStyle
    ) {
        ItemRow(
            title = pluralStringResource(R.plurals.format_beslut_facit_row, followedCount, followedCount),
            trailing = if (followedCount > 0 && followedExcessKr != null) {
                {
                    Text(
                        MoneyFormat.krSigned(followedExcessKr),
                        style = MonoAmountStyle.merge(MaterialTheme.typography.bodyLarge),
                        color = ReturnColors.forAmount(followedExcessKr)
                    )
                }
            } else {
                null
            },
            navigates = true,
            onClick = onOpen
        )
    }
}
