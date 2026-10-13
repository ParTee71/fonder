package se.partee71.fonder.ui.beslut

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.usecase.CheaperAlternative
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.SuggestionDismissals
import se.partee71.fonder.domain.usecase.OpenSwitchWatch
import se.partee71.fonder.domain.usecase.PendingDecisions
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import se.partee71.fonder.ui.components.AppButton
import se.partee71.fonder.ui.components.AppButtonVariant
import se.partee71.fonder.ui.components.AppCard
import se.partee71.fonder.ui.components.CardTitleRow
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.ExpandableSection
import se.partee71.fonder.ui.components.ItemRow
import se.partee71.fonder.ui.riskprofil.labelRes
import se.partee71.fonder.ui.settings.labelRes
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.fonder.ui.theme.ReturnColors

/** Gör Besluts lista adresserbar för `performScrollToIndex` i tester (UI-5, samma mönster som Hem och Facit). */
const val BESLUT_LIST_TEST_TAG = "beslut_list"

@Composable
fun BeslutScreen(
    onOpenSwitchWatch: (Long) -> Unit,
    onOpenRiskProfile: () -> Unit,
    onOpenFacit: () -> Unit,
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BeslutViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // En nystartad bevakning öppnas direkt: den är tom tills kandidaterna hämtats, och att
    // lämna användaren kvar på Beslut hade sett ut som att knappen inte gjorde något.
    LaunchedEffect(state.startedSwitchWatchId) {
        state.startedSwitchWatchId?.let { watchId ->
            onOpenSwitchWatch(watchId)
            viewModel.onSwitchWatchOpened()
        }
    }

    BeslutContent(
        state = state,
        onOpenRiskProfile = onOpenRiskProfile,
        onOpenSwitchWatch = onOpenSwitchWatch,
        onFollow = onFollow,
        onStartSwitchWatch = viewModel::startSwitchWatch,
        onDismissSuggestion = viewModel::dismissSuggestion,
        onUndoDismissal = viewModel::undoDismissal,
        onRecomputeSwitchPlan = viewModel::recomputeSwitchPlan,
        onOpenFacit = onOpenFacit,
        modifier = modifier,
    )
}

/**
 * Tillståndsdriven, testbar del av [BeslutScreen] (NAV-9) — inget ViewModel/Hilt-beroende. En
 * `LazyColumn` hela vägen, även i tomt läge: rubrikraden, omräkningen och vägen till Facit gäller
 * också när inget väntar, och allt som kan växa ska gå att skrolla (UI-5).
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
) {
    val decisions = state.decisions
    LazyColumn(modifier = modifier.fillMaxSize().testTag(BESLUT_LIST_TEST_TAG)) {
        item { ProfileHeader(state.riskProfile, state.accountType, onOpenRiskProfile) }

        if (state.isEmpty) {
            item {
                EmptyState(
                    title = stringResource(R.string.beslut_empty_title),
                    body = stringResource(R.string.beslut_empty_body),
                    icon = Icons.Outlined.TaskAlt,
                )
            }
        }

        if (decisions.openWatches.isNotEmpty()) {
            item { SectionHeading(stringResource(R.string.beslut_switch_watch_title)) }
            items(decisions.openWatches, key = { "watch-${it.watchId}" }) { watch ->
                SwitchWatchCard(watch = watch, working = state.switchWatchWorking, onOpen = onOpenSwitchWatch)
            }
        }

        if (decisions.switchPlan.isNotEmpty()) {
            item { SectionHeading(stringResource(R.string.beslut_suggestions_title), working = state.suggestionsWorking) }
            items(decisions.switchPlan, key = { "plan-${it.recordId}" }) { switch ->
                SuggestionCard(
                    suggestion = switch,
                    title = switchPlanHeadline(switch),
                    details = listOfNotNull(
                        switch.switchValueKr?.let { stringResource(R.string.format_switch_plan_amount, MoneyFormat.kr(it)) },
                        stringResource(R.string.format_switch_plan_fee_delta, MoneyFormat.percentSigned(switch.feeDeltaPercent / 100.0)),
                    ),
                    watched = switch.sellIsin in decisions.watchedSellIsins,
                    onFollow = onFollow,
                    onStartSwitchWatch = onStartSwitchWatch,
                    onDismiss = onDismissSuggestion,
                )
            }
            item { CaptionText(stringResource(R.string.switch_plan_disclaimer)) }
        }

        // Omräkning på begäran (HEM-8, issue #88) — bara när en plan alls kan ges (ISK/KF + satt
        // profil, SET-3/SET-4); även utan plan, för det är just då man vill fråga igen. Släckt
        // medan något jobb kör, samma signal som chromets indikator (NAV-6).
        if (state.canRecomputeSwitchPlan) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    AppButton(
                        text = stringResource(
                            if (state.backgroundWorkRunning) R.string.switch_plan_recompute_running else R.string.switch_plan_recompute,
                        ),
                        onClick = onRecomputeSwitchPlan,
                        enabled = !state.backgroundWorkRunning,
                        variant = AppButtonVariant.Text,
                    )
                    Text(
                        stringResource(R.string.switch_plan_recompute_explain),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Billigare alternativ (ANA-9) under planen och dess omräkning, som egna förslag med samma
        // två beslut. Egen rubrik: de svarar på en annan fråga än planen — samma exponering till
        // lägre avgift.
        if (decisions.cheaperAlternatives.isNotEmpty()) {
            item { SectionHeading(stringResource(R.string.fee_comparison_title)) }
            items(decisions.cheaperAlternatives, key = { "fee-${it.suggestion.recordId}" }) { alternative ->
                SuggestionCard(
                    suggestion = alternative.suggestion,
                    title = cheaperAlternativeHeadline(alternative),
                    details = listOfNotNull(
                        alternative.annualSavingsKr?.let { stringResource(R.string.format_beslut_cheaper_savings, MoneyFormat.kr(it)) },
                    ),
                    watched = alternative.suggestion.sellIsin in decisions.watchedSellIsins,
                    onFollow = onFollow,
                    onStartSwitchWatch = onStartSwitchWatch,
                    onDismiss = onDismissSuggestion,
                )
            }
            item { CaptionText(stringResource(R.string.fee_comparison_explain)) }
        }

        item { SectionHeading(stringResource(R.string.beslut_outcome_title)) }
        item { FacitRow(followedCount = state.followedCount, followedExcessKr = state.followedExcessKr, onOpen = onOpenFacit) }

        // Avstådda förslag (HEM-14) längst ned: ingen snackbar finns i appen, så ångra-vägen är en
        // hopfälld rad som bara visas när något avståtts och fortfarande ingår i planen.
        if (decisions.dismissedCount > 0) {
            item { DismissedSection(decisions, onUndo = onUndoDismissal) }
        }
    }
}

/**
 * "Mot riskprofil 4,00 · Balanserad tillväxt · ISK/KF · Ändra" — vad förslagen räknas mot. Målet
 * och kontotypen utelämnas när de inte är satta i stället för att gissas; utan profil sägs det ut.
 */
@Composable
private fun ProfileHeader(riskProfile: RiskProfile?, accountType: AccountType?, onChange: () -> Unit) {
    val parts = buildList {
        val level = riskProfile?.weightedTargetLevel
        add(
            if (level != null) {
                stringResource(R.string.format_beslut_risk_profile, MoneyFormat.decimal(level))
            } else {
                stringResource(R.string.beslut_no_risk_profile)
            },
        )
        riskProfile?.answers?.goal?.let { add(stringResource(it.labelRes())) }
        accountType?.let { add(stringResource(it.labelRes())) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        AppButton(
            text = stringResource(R.string.beslut_change_risk_profile),
            onClick = onChange,
            variant = AppButtonVariant.Text,
        )
    }
}

/** Avsnittsrubrik med plats för en väntesnurra (NAV-6) — samma rubrikrad som korten använder. */
@Composable
private fun SectionHeading(title: String, working: Boolean = false) {
    CardTitleRow(
        title = title,
        working = working,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun CaptionText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Ett pågående byte (ANA-12): säljfonden och det alternativ som gått bäst sedan säljdagen, dag N
 * av väntan och vägen tillbaka till bevakningen, där köpet registreras. Ledaren ur **cachade**
 * kurser — kortet ska rendera direkt, den dyra hämtningen hör hemma på bevakningens egen skärm.
 */
@Composable
private fun SwitchWatchCard(watch: OpenSwitchWatch, working: Boolean, onOpen: (Long) -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        CardTitleRow(
            title = watch.leaderName?.let { stringResource(R.string.format_beslut_switch_watch_title, watch.sellFundName, it) }
                ?: watch.sellFundName,
            working = working,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.format_switch_watch_day, watch.daysWaiting, SwitchWatchCalc.WATCH_TTL_DAYS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            stringResource(R.string.format_beslut_switch_watch_candidates, watch.candidateCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (watch.leaderName != null && watch.leaderChangeFraction != null) {
                stringResource(
                    R.string.format_beslut_switch_watch_leader,
                    watch.leaderName,
                    MoneyFormat.percentSigned(watch.leaderChangeFraction),
                )
            } else {
                stringResource(R.string.beslut_switch_watch_no_leader)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppButton(
            text = stringResource(R.string.beslut_switch_watch_open),
            onClick = { onOpen(watch.watchId) },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** "1. Sälj X (nivå 6) → Köp Y (nivå 4)" — ett byte i bytesplanen, på kortet och bland de avstådda. */
@Composable
private fun switchPlanHeadline(switch: SwitchPlanResolver.Suggestion): String =
    stringResource(
        R.string.format_switch_plan_row,
        switch.planIndex + 1,
        switch.sellFundName,
        switch.fromLevel,
        switch.buyFundName,
        switch.toLevel,
    )

/** "Sverige Småbolag 1,40 % → Småbolag Index 0,40 %" — ett billigare alternativ (ANA-9), på kortet och bland de avstådda. */
@Composable
private fun cheaperAlternativeHeadline(alternative: CheaperAlternative): String =
    stringResource(
        R.string.format_beslut_cheaper_row,
        alternative.suggestion.sellFundName,
        MoneyFormat.feePercent(alternative.sellFeePercent),
        alternative.suggestion.buyFundName,
        MoneyFormat.feePercent(alternative.buyFeePercent),
    )

/**
 * Ett förslag på Beslut — ett byte i bytesplanen (HEM-8) eller ett billigare alternativ (ANA-9):
 * rubrik, underlag och förslagets två beslut. För planen kommer rangordningen ur
 * [SwitchPlanResolver.Suggestion.planIndex], aldrig ur listpositionen: planen är girig och
 * sekventiell, så numret är en del av rådet (issue #75).
 *
 * **Följ** och **Avstå** (HEM-14) är förslagets två beslut. "Följ" utför inget byte, den öppnar
 * säljformuläret förifyllt för förslaget (BYT-2) — det sparade säljet startar bytet, och ingen
 * kvittering sker utan transaktion; ett pågående byte (BYT-1) erbjuds inte Följ igen. "Avstå" döljer
 * fondparet för förslagets sort (se [SuggestionDismissals]). Ett förslag kvitterat i Facit (SET-5)
 * erbjuder "Bevaka alternativ" — bevakningen handlar om perioden **efter** säljet (ANA-12) — och
 * bara när säljfonden inte redan bevakas, annars hade varje tryck startat ytterligare en bevakning
 * av samma byte.
 */
@Composable
private fun SuggestionCard(
    suggestion: SwitchPlanResolver.Suggestion,
    title: String,
    details: List<String>,
    watched: Boolean,
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit,
    onStartSwitchWatch: (SwitchPlanResolver.Suggestion) -> Unit,
    onDismiss: (SwitchPlanResolver.Suggestion) -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        details.forEachIndexed { index, detail ->
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (index == 0) Modifier.padding(top = 4.dp) else Modifier,
            )
        }
        if (suggestion.followed) {
            // Kvitterat i Facit (SET-5) utan ett registrerat sälj — bevakningen kan startas utan
            // transaktion (HEM-8/ANA-12); kvitteringen ändras bara i Facit.
            if (!watched) {
                AppButton(
                    text = stringResource(R.string.switch_watch_start),
                    onClick = { onStartSwitchWatch(suggestion) },
                    variant = AppButtonVariant.Text,
                )
            }
        } else {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Ett pågående byte (BYT-1) är redan sålt — Följ hade registrerat samma sälj igen.
                if (!suggestion.inProgress) {
                    AppButton(
                        text = stringResource(R.string.beslut_follow),
                        onClick = { onFollow(suggestion) },
                        variant = AppButtonVariant.Outlined,
                        contentDescription = stringResource(R.string.format_beslut_follow_a11y, suggestion.sellFundName, suggestion.buyFundName),
                    )
                }
                AppButton(
                    text = stringResource(R.string.beslut_dismiss),
                    onClick = { onDismiss(suggestion) },
                    variant = AppButtonVariant.Text,
                    contentDescription = stringResource(R.string.format_beslut_dismiss_a11y, suggestion.sellFundName, suggestion.buyFundName),
                )
            }
        }
    }
}

/**
 * "Avstådda (N)" (HEM-14) — hopfälld som standard, med en rad per avstått förslag och **Ångra**, som
 * visar förslaget igen: först planens byten, sedan de billigare alternativen. Planens rangordning står
 * kvar i raden, så det syns vilket steg i planen som hoppats över.
 */
@Composable
private fun DismissedSection(decisions: PendingDecisions, onUndo: (SwitchPlanResolver.Suggestion) -> Unit) {
    ExpandableSection(
        title = stringResource(R.string.format_beslut_dismissed_title, decisions.dismissedCount),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        Column {
            Text(
                stringResource(R.string.format_beslut_dismissed_explain, SuggestionDismissals.TTL_DAYS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            decisions.dismissedSuggestions.forEach { switch ->
                DismissedRow(headline = switchPlanHeadline(switch), suggestion = switch, onUndo = onUndo)
            }
            decisions.dismissedCheaperAlternatives.forEach { alternative ->
                DismissedRow(headline = cheaperAlternativeHeadline(alternative), suggestion = alternative.suggestion, onUndo = onUndo)
            }
        }
    }
}

@Composable
private fun DismissedRow(headline: String, suggestion: SwitchPlanResolver.Suggestion, onUndo: (SwitchPlanResolver.Suggestion) -> Unit) {
    ItemRow(
        headline = headline,
        headlineStyle = MaterialTheme.typography.bodyMedium,
        trailing = {
            AppButton(
                text = stringResource(R.string.beslut_undo_dismissal),
                onClick = { onUndo(suggestion) },
                variant = AppButtonVariant.Text,
                contentDescription = stringResource(
                    R.string.format_beslut_undo_dismissal_a11y,
                    suggestion.sellFundName,
                    suggestion.buyFundName,
                ),
            )
        },
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
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.format_beslut_facit_row, followedCount, followedCount),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (followedCount > 0 && followedExcessKr != null) {
                Text(
                    MoneyFormat.krSigned(followedExcessKr),
                    style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                    color = ReturnColors.forAmount(followedExcessKr),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
