package se.partee71.fonder.ui.beslut

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import kotlin.math.abs
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.OpenSwitchWatch
import se.partee71.fonder.domain.usecase.SuggestionDismissals
import se.partee71.fonder.domain.usecase.SwitchEffectCalc
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.domain.usecase.SwitchRow
import se.partee71.fonder.domain.usecase.SwitchRowState
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import se.partee71.fonder.ui.components.CenteredState
import se.partee71.fonder.ui.components.ExpandableSection
import se.partee71.fonder.ui.components.FundFactsCard
import se.partee71.fonder.ui.components.FundFactsRow
import se.partee71.fonder.ui.components.PullToRefreshContainer
import se.partee71.fonder.ui.components.RiskCard
import se.partee71.fonder.ui.riskprofil.labelRes
import se.partee71.fonder.ui.theme.Copper
import se.partee71.fonder.ui.theme.Gain
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.fonder.ui.theme.Neutral
import se.partee71.fonder.ui.theme.ReturnColors
import se.partee71.fonder.ui.theme.Tertiary
import se.partee71.fonder.ui.theme.flushCardStyle
import se.partee71.ui.components.AppBottomSheet
import se.partee71.ui.components.AppButton
import se.partee71.ui.components.AppCard
import se.partee71.ui.components.AppDivider
import se.partee71.ui.components.AppIconButton
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
        onRefresh = { viewModel.onEvent(BeslutEvent.Refresh) },
        onShowInfo = { viewModel.onEvent(BeslutEvent.ShowInfo(it)) },
        onHideInfo = { viewModel.onEvent(BeslutEvent.HideInfo) },
        onOpenFacit = onOpenFacit,
        onRetry = { viewModel.onEvent(BeslutEvent.Retry) },
        modifier = modifier
    )
}

/**
 * Tillståndsdriven, testbar del av [BeslutScreen] (NAV-9, issue #240, mockup C2) — inget
 * ViewModel/Hilt-beroende. Svaret på "varför?" först: riskkortet (HEM-7) med "Ändra mål", sedan
 * råden som dess konsekvens, ett kort per byte (BYT-4), och sist de avslutade och överhoppade och
 * vägen till Facit. En `LazyColumn` hela vägen, även i tomt läge, så allt som kan växa går att
 * skrolla (UI-5).
 *
 * Omräkningen är **dra för att uppdatera** (UI-11) och finns bara när planen alls kan ges
 * (profil + ISK/KF, [BeslutUiState.canRecomputeSwitchPlan]); annars säger en rad vad som saknas.
 * Infokortet (BYT-5) är ett bottenark som öppnas från utropstecknet på ett råd.
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
    onRefresh: () -> Unit = {},
    onShowInfo: (SwitchRow.Suggested) -> Unit = {},
    onHideInfo: () -> Unit = {},
    onOpenFacit: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    // Läsfel (NFR-6): bara felet med "Försök igen" – riskkortet och Facit-raden hade annars visat
    // standardvärden som om de vore data.
    state.error?.let { error ->
        CenteredState(modifier) { LoadErrorState(stringResource(R.string.error_load_title), error, onRetry) }
        return
    }
    // Listans läge lyfts över grenen nedan: dragningen kommer och går med gaten, och skrollen ska stå kvar.
    val listState = rememberLazyListState()
    val list: @Composable (Modifier) -> Unit = { listModifier ->
        BeslutList(
            state = state,
            listState = listState,
            modifier = listModifier,
            onOpenRiskProfile = onOpenRiskProfile,
            onOpenSwitchWatch = onOpenSwitchWatch,
            onFollow = onFollow,
            onStartSwitchWatch = onStartSwitchWatch,
            onDismissSuggestion = onDismissSuggestion,
            onUndoDismissal = onUndoDismissal,
            onShowInfo = onShowInfo,
            onOpenFacit = onOpenFacit
        )
    }
    // Ingen gest utan gaten: en dragning som inte kan ge något är värre än ingen (UI-11).
    if (state.canRecomputeSwitchPlan) {
        PullToRefreshContainer(refreshing = state.switchPlanWorking, onRefresh = onRefresh, modifier = modifier) { list(Modifier) }
    } else {
        list(modifier)
    }

    state.info?.let { info ->
        SuggestionInfoSheet(
            info = info,
            effect = state.effectFor(info.row.suggestion),
            onFollow = { suggestion ->
                onHideInfo()
                onFollow(suggestion)
            },
            onDismiss = onHideInfo
        )
    }
}

@Composable
private fun BeslutList(
    state: BeslutUiState,
    listState: LazyListState,
    onOpenRiskProfile: () -> Unit,
    onOpenSwitchWatch: (Long) -> Unit,
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit,
    onStartSwitchWatch: (SwitchPlanResolver.Suggestion) -> Unit,
    onDismissSuggestion: (SwitchPlanResolver.Suggestion) -> Unit,
    onUndoDismissal: (SwitchPlanResolver.Suggestion) -> Unit,
    onShowInfo: (SwitchRow.Suggested) -> Unit,
    onOpenFacit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val switches = state.switches
    // Första planbytet som ännu inte gjorts — senare steg är räknade som om det är gjort.
    val firstOpenStep = switches.active
        .filterIsInstance<SwitchRow.Suggested>()
        .filter { it.cheaper == null && !it.suggestion.followed }
        .minOfOrNull { it.suggestion.planIndex }
    LazyColumn(modifier = modifier.fillMaxSize().testTag(BESLUT_LIST_TEST_TAG), state = listState) {
        riskHeader(state, onOpenRiskProfile)

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
                    effect = (row as? SwitchRow.Suggested)?.let { state.effectFor(it.suggestion) },
                    firstOpenStep = firstOpenStep,
                    onOpenSwitchWatch = onOpenSwitchWatch,
                    onFollow = onFollow,
                    onStartSwitchWatch = onStartSwitchWatch,
                    onDismiss = onDismissSuggestion,
                    onShowInfo = onShowInfo
                )
            }
            // Förbehållet (HEM-8): appen byter aldrig, och planens träffsäkerhet så länge ett planbyte står på listan.
            item(contentType = "caption") {
                val accuracy = if (switches.active.any { it.state == SwitchRowState.PROPOSED }) " " + stringResource(R.string.switch_plan_disclaimer) else ""
                CaptionText(stringResource(R.string.beslut_never_switches) + accuracy)
            }
        }

        // Avslutade (14 dagar efter stängningen) och överhoppade (HEM-14) — hopfällt; ingen snackbar finns
        // i appen, så ångra-vägen är raden här.
        if (switches.closed.isNotEmpty()) {
            item(contentType = "closed") { ClosedSection(switches.closed, onUndo = onUndoDismissal) }
        }

        item(contentType = "section-heading") { SectionHeading(stringResource(R.string.beslut_outcome_title)) }
        item(contentType = "facit-row") { FacitRow(followedCount = state.followedCount, followedExcessKr = state.followedExcessKr, onOpen = onOpenFacit) }
    }
}

/**
 * Riskkortet (HEM-7, samma som Översiktens) med "Ändra mål", eller "Sätt ett mål" utan profil. Utan
 * gaten (profil + ISK/KF) säger en rad vad som krävs för att byten ska föreslås — i stället för
 * den gamla omräkningsknappen, som lovade något SET-4 ändå vägrade.
 */
private fun LazyListScope.riskHeader(state: BeslutUiState, onOpenRiskProfile: () -> Unit) {
    val risk = state.riskSummary
    when {
        risk != null -> item(contentType = "risk") {
            RiskCard(
                risk = risk,
                // Kortets andelar räknas ur kurscachen — snurran när kurserna uppdateras (NAV-6).
                working = state.pricesWorking,
                goal = state.riskProfile?.answers?.goal?.let { stringResource(it.labelRes()) },
                footer = {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        AppButton(text = stringResource(R.string.beslut_change_goal), onClick = onOpenRiskProfile, variant = ButtonVariant.Text)
                    }
                }
            )
        }

        // Profil utan kort (underlaget läses ännu, eller målet saknar fördelning): Ändra mål ska ändå nås.
        state.riskProfile != null && !state.loading -> item(contentType = "goal") {
            AppCard(modifier = Modifier.fillMaxWidth().padding(Spacing.l), style = flushCardStyle) {
                ItemRow(
                    title = stringResource(R.string.hem_risk_title),
                    subtitle = state.riskProfile.answers?.goal?.let { stringResource(R.string.format_risk_card_goal, stringResource(it.labelRes())) },
                    trailing = { AppButton(text = stringResource(R.string.beslut_change_goal), onClick = onOpenRiskProfile, variant = ButtonVariant.Text) }
                )
            }
        }

        state.riskProfile == null && !state.loading -> item(contentType = "no-goal") {
            AppCard(modifier = Modifier.fillMaxWidth().padding(Spacing.l), style = flushCardStyle) {
                ItemRow(
                    title = stringResource(R.string.beslut_no_goal_title),
                    subtitle = stringResource(R.string.beslut_gate_body),
                    trailing = { AppButton(text = stringResource(R.string.beslut_change_goal), onClick = onOpenRiskProfile, variant = ButtonVariant.Text) }
                )
            }
        }
    }
    if (state.riskProfile != null && !state.loading && !state.canRecomputeSwitchPlan) {
        item(contentType = "gate") { CaptionText(stringResource(R.string.beslut_gate_body)) }
    }
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

/** "Så kommer du närmare målet · N väntar på dig" — listans rubrik med väntesnurran (NAV-6) och antalet som väntar i en pill. */
@Composable
private fun SwitchesHeading(waiting: Int, working: Boolean) {
    SectionHeading(
        title = stringResource(R.string.beslut_switches_title),
        working = working,
        count = if (waiting > 0) stringResource(R.string.format_beslut_switches_waiting, waiting) else null
    )
}

/**
 * Chipets text för en rad (BYT-4) — vad rådet gör, inte varifrån det kommer: "Sänker risken · steg 1",
 * "Sparar avgift · påverkar inte risken", "Sålt · dag 2 av 14 · välj köpfond". Tillståndet står
 * alltid i text (UI-3).
 */
@Composable
private fun switchChipText(row: SwitchRow): String = when (row) {
    is SwitchRow.Suggested -> if (row.cheaper != null) {
        stringResource(R.string.switch_chip_fee)
    } else {
        planStepChip(row.suggestion.fromLevel, row.suggestion.toLevel, row.suggestion.planIndex)
    }

    is SwitchRow.Sold -> stringResource(R.string.format_switch_state_sold, row.watch.daysWaiting, SwitchWatchCalc.WATCH_TTL_DAYS)

    is SwitchRow.Closed -> when (row.state) {
        SwitchRowState.BOUGHT ->
            row.closedAtEpochDay
                ?.let { stringResource(R.string.format_switch_state_bought, shortDate(LocalDate.ofEpochDay(it))) }
                ?: stringResource(R.string.switch_state_bought)

        SwitchRowState.CANCELLED -> stringResource(R.string.switch_state_cancelled)

        else -> stringResource(R.string.switch_state_expired)
    }

    is SwitchRow.Dismissed -> stringResource(R.string.switch_state_dismissed)
}

@Composable
private fun planStepChip(fromLevel: Int, toLevel: Int, planIndex: Int): String = stringResource(
    when {
        toLevel < fromLevel -> R.string.format_switch_chip_lower
        toLevel > fromLevel -> R.string.format_switch_chip_raise
        else -> R.string.format_switch_chip_step
    },
    planIndex + 1
)

/** Chipets ton (BYT-4): blå för råden, tertiär för avgiften, koppar för sålt, grön för köpt, neutral för resten. */
@Composable
private fun SwitchRowChip(row: SwitchRow) {
    val tone = when (row.state) {
        SwitchRowState.PROPOSED -> Tone.Primary
        SwitchRowState.FEE -> Tone.Tertiary
        SwitchRowState.SOLD -> Tone.Copper
        SwitchRowState.BOUGHT -> Tone.Gain
        SwitchRowState.CANCELLED, SwitchRowState.EXPIRED, SwitchRowState.DISMISSED -> Tone.Neutral
    }
    InfoPill(text = switchChipText(row), tone = tone, shape = AppTheme.shapes.smallTile)
}

/** "7 okt" — dag och kort månad på svenska, utan punkt. */
@Composable
private fun shortDate(date: LocalDate): String = "${date.dayOfMonth} ${stringArrayResource(R.array.month_short)[date.monthValue - 1]}"

/**
 * Effekten av ett planbyte i en mening (HEM-17, [SwitchEffectCalc]), med en decimal som riskkortets
 * staplar: "Nivå 5 14,0 % → 7,0 %, nivå 2 8,0 % → 15,0 %. Dessutom 1,10 % lägre avgift per år." Går steget inte att räkna (okänt belopp, en lucka i
 * planen) sägs bara vart pengarna flyttas — aldrig en gissad andel.
 */
@Composable
private fun planEffectText(suggestion: SwitchPlanResolver.Suggestion, effect: SwitchEffectCalc.StepEffect?): String {
    val levels = effect?.let {
        stringResource(
            R.string.format_beslut_effect,
            it.from.level,
            MoneyFormat.percent(it.from.beforeFraction),
            MoneyFormat.percent(it.from.afterFraction),
            it.to.level,
            MoneyFormat.percent(it.to.beforeFraction),
            MoneyFormat.percent(it.to.afterFraction)
        )
    } ?: stringResource(R.string.format_beslut_effect_unknown, suggestion.fromLevel, suggestion.toLevel)
    // Avgiftsskillnaden i procentenheter; under en halv hundradel syns den inte i två decimaler.
    val delta = suggestion.feeDeltaPercent
    val fee = when {
        delta <= -FEE_DELTA_VISIBLE -> stringResource(R.string.format_beslut_effect_fee_lower, MoneyFormat.feePercent(abs(delta)))
        delta >= FEE_DELTA_VISIBLE -> stringResource(R.string.format_beslut_effect_fee_higher, MoneyFormat.feePercent(delta))
        else -> null
    }
    return listOfNotNull(levels, fee).joinToString(" ")
}

private const val FEE_DELTA_VISIBLE = 0.005

/** "Samma sorts fond, 1,40 % → 0,40 % i avgift. Ca 960 kr/år." — ett billigare alternativ (ANA-9) påverkar inte risken. */
@Composable
private fun feeEffectText(row: SwitchRow.Suggested): String {
    val cheaper = row.cheaper ?: return ""
    val fees = stringResource(R.string.format_beslut_fee_effect, MoneyFormat.feePercent(cheaper.sellFeePercent), MoneyFormat.feePercent(cheaper.buyFeePercent))
    val savings = cheaper.annualSavingsKr?.let { stringResource(R.string.format_beslut_fee_savings, MoneyFormat.krWhole(it)) }
    return listOfNotNull(fees, savings).joinToString(" ")
}

/** "Sverige Småbolag → Räntefond Kort, 12 500 kr" — beloppet bara när det är känt. */
@Composable
private fun suggestionTitle(suggestion: SwitchPlanResolver.Suggestion, withAmount: Boolean): String {
    val amount = suggestion.switchValueKr?.takeIf { withAmount }
    return if (amount != null) {
        stringResource(R.string.format_beslut_plan_title, suggestion.sellFundName, suggestion.buyFundName, MoneyFormat.krWhole(amount))
    } else {
        stringResource(R.string.format_beslut_switch_watch_title, suggestion.sellFundName, suggestion.buyFundName)
    }
}

/**
 * Ett råd i listan (BYT-4): chip, rubrik, effekt och radens beslut.
 *
 * - **Planbyte** (HEM-8) och **billigare alternativ** (ANA-9): utropstecknet öppnar infokortet
 *   (BYT-5), **Jag har sålt** öppnar säljformuläret förifyllt (BYT-2) — det sparade säljet gör raden
 *   Sålt — och **Hoppa över** (HEM-14) döljer paret. Stegnumret kommer ur
 *   [SwitchPlanResolver.Suggestion.planIndex], aldrig ur listpositionen (issue #75). Ett förslag
 *   kvitterat i Facit (SET-5) erbjuder i stället "Bevaka alternativ", och bara när säljfonden inte
 *   redan bevakas.
 * - **Sålt** (ANA-12): beloppet som väntar, det som gått bäst sedan säljdagen — ur **cachade**
 *   kurser — och **Välj köpfond**, som öppnar bevakningen där köpet registreras.
 */
@Composable
private fun SwitchRowCard(
    row: SwitchRow,
    effect: SwitchEffectCalc.StepEffect?,
    firstOpenStep: Int?,
    onOpenSwitchWatch: (Long) -> Unit,
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit,
    onStartSwitchWatch: (SwitchPlanResolver.Suggestion) -> Unit,
    onDismiss: (SwitchPlanResolver.Suggestion) -> Unit,
    onShowInfo: (SwitchRow.Suggested) -> Unit
) {
    AppCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
        style = flushCardStyle
    ) {
        when (row) {
            is SwitchRow.Suggested -> SuggestedRow(row, effect, firstOpenStep, onFollow, onStartSwitchWatch, onDismiss, onShowInfo)

            is SwitchRow.Sold -> SoldRow(row, onOpenSwitchWatch)

            // Avslutade och överhoppade står i den hopfällda sektionen, aldrig bland de väntande.
            is SwitchRow.Closed, is SwitchRow.Dismissed -> Unit
        }
    }
}

/**
 * Radens innehåll i en nod (chip, rubrik, effekt) — skärmläsaren läser rådet i ett svep. Infoknappen
 * (48 dp) är radens `trailing` och en egen klickbar nod.
 */
@Composable
private fun SwitchRowText(row: SwitchRow, title: String, details: String?, info: (@Composable () -> Unit)? = null, lead: (@Composable () -> Unit)? = null) {
    ItemRow(
        title = title,
        modifier = Modifier.semantics(mergeDescendants = true) {},
        subtitle = details,
        overline = { SwitchRowChip(row) },
        trailing = info,
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
    effect: SwitchEffectCalc.StepEffect?,
    firstOpenStep: Int?,
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit,
    onStartSwitchWatch: (SwitchPlanResolver.Suggestion) -> Unit,
    onDismiss: (SwitchPlanResolver.Suggestion) -> Unit,
    onShowInfo: (SwitchRow.Suggested) -> Unit
) {
    val suggestion = row.suggestion
    val isPlan = row.cheaper == null
    val details = if (isPlan) {
        val after = firstOpenStep?.takeIf { suggestion.planIndex > it && !suggestion.followed }
            ?.let { stringResource(R.string.format_beslut_after_step, it + 1) }
        listOfNotNull(planEffectText(suggestion, effect), after).joinToString(" ")
    } else {
        feeEffectText(row)
    }
    SwitchRowText(
        row = row,
        title = suggestionTitle(suggestion, withAmount = isPlan),
        details = details,
        info = {
            AppIconButton(
                icon = R.drawable.ic_info,
                contentDescription = stringResource(R.string.format_beslut_info_a11y, suggestion.sellFundName, suggestion.buyFundName),
                onClick = { onShowInfo(row) }
            )
        }
    )
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
        title = stringResource(R.string.format_beslut_sold_title, watch.sellFundName),
        details = watch.proceedsKr?.let { stringResource(R.string.format_beslut_sold_proceeds, MoneyFormat.krWhole(it)) },
        lead = { SoldLeader(watch) }
    )
    SwitchRowActions {
        AppButton(
            text = stringResource(R.string.beslut_open_watch),
            onClick = { onOpen(watch.watchId) },
            variant = ButtonVariant.Primary,
            contentDescription = stringResource(R.string.format_beslut_open_watch_a11y, watch.sellFundName)
        )
    }
}

/**
 * Infokortet (BYT-5, issue #240, mockup "C · Infokort"): vad rådet gör, den föreslagna fondens fakta
 * ([FundFactsCard]) och upp till tre andra fonder — på samma risknivå för ett planbyte, de andra
 * billigare alternativen för ett avgiftsbyte (ANA-9). Saknas alternativ sägs det. "Jag har sålt X"
 * gör samma sak som radens knapp (BYT-2); ett kvitterat förslag har ingen.
 */
@Composable
private fun SuggestionInfoSheet(
    info: SuggestionInfo,
    effect: SwitchEffectCalc.StepEffect?,
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit,
    onDismiss: () -> Unit
) {
    val row = info.row
    val suggestion = row.suggestion
    val isPlan = row.cheaper == null
    AppBottomSheet(
        title = stringResource(R.string.format_beslut_switch_watch_title, suggestion.sellFundName, suggestion.buyFundName),
        onDismiss = onDismiss,
        footer = {
            AppButton(text = stringResource(R.string.beslut_info_close), onClick = onDismiss, variant = ButtonVariant.Text)
            if (!suggestion.followed) {
                AppButton(
                    text = stringResource(R.string.format_beslut_info_register_sale, suggestion.sellFundName),
                    onClick = { onFollow(suggestion) },
                    variant = ButtonVariant.Primary,
                    contentDescription = stringResource(R.string.format_beslut_register_sale_a11y, suggestion.sellFundName, suggestion.buyFundName)
                )
            }
        }
    ) {
        SwitchRowChip(row)
        val sale = suggestion.switchValueKr?.takeIf { isPlan }?.let { stringResource(R.string.format_beslut_info_sale, MoneyFormat.krWhole(it)) }
        Text(
            listOfNotNull(sale, if (isPlan) planEffectText(suggestion, effect) else feeEffectText(row)).joinToString(" "),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.m)
        )
        FundFactsCard(
            facts = info.proposed,
            badge = stringResource(R.string.beslut_info_badge),
            reason = if (isPlan) stringResource(R.string.format_beslut_info_reason_plan, suggestion.toLevel) else stringResource(R.string.beslut_info_reason_fee)
        )
        ItemRowText(
            title = if (isPlan) stringResource(R.string.format_beslut_info_alternatives_plan, suggestion.toLevel) else stringResource(R.string.beslut_info_alternatives_fee),
            subtitle = stringResource(if (isPlan) R.string.beslut_info_alternatives_plan_body else R.string.beslut_info_alternatives_fee_body),
            modifier = Modifier.padding(top = Spacing.l)
        )
        if (info.alternatives.isEmpty()) {
            ItemRowText(
                title = null,
                subtitle = if (isPlan) stringResource(R.string.format_beslut_info_no_alternatives_plan, suggestion.toLevel) else stringResource(R.string.beslut_info_no_alternatives_fee),
                modifier = Modifier.padding(top = Spacing.s)
            )
        } else {
            AppCard(modifier = Modifier.fillMaxWidth().padding(top = Spacing.s), style = flushCardStyle) {
                info.alternatives.forEachIndexed { index, facts ->
                    if (index > 0) AppDivider()
                    FundFactsRow(facts)
                }
            }
            ItemRowText(title = null, subtitle = stringResource(R.string.beslut_info_columns), modifier = Modifier.padding(top = Spacing.xs))
        }
    }
}

/**
 * "Bäst hittills: Global Indexfond A +1,2 %" — ledaren ur cachade kurser, utvecklingen med
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
 * "Avslutade och överhoppade (N)" (BYT-4, HEM-14) — hopfälld som standard: byten stängda som köpta,
 * avbrutna eller utgångna i [SwitchWatchCalc.WATCH_TTL_DAYS] dagar efter stängningen (sedan bara i
 * Facit), och de överhoppade förslagen med **Ångra**, som visar förslaget igen.
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

        is SwitchRow.Dismissed -> stringResource(R.string.format_beslut_switch_watch_title, row.suggestion.sellFundName, row.suggestion.buyFundName)

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
