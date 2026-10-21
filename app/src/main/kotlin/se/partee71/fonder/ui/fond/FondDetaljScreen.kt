package se.partee71.fonder.ui.fond

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.ui.components.AnalysisGuidanceCard
import se.partee71.fonder.ui.components.AnalysisStatusBanner
import se.partee71.fonder.ui.components.ExpandableInfoRow
import se.partee71.fonder.ui.components.ExpandableSection
import se.partee71.fonder.ui.components.PeriodRow
import se.partee71.fonder.ui.components.RiskBadge
import se.partee71.fonder.ui.components.StatusDot
import se.partee71.fonder.ui.components.WorkingIndicator
import se.partee71.fonder.ui.diagram.ChartSeries
import se.partee71.fonder.ui.diagram.FundLineChart
import se.partee71.fonder.ui.navigation.TransactionFormKey
import se.partee71.ui.components.AppButton
import se.partee71.ui.components.AppDivider
import se.partee71.ui.components.AppTextField
import se.partee71.ui.components.ButtonVariant
import se.partee71.ui.components.EmptyState
import se.partee71.ui.components.EntityDetailScreen
import se.partee71.ui.components.GroupLabel
import se.partee71.ui.components.ItemRowText
import se.partee71.ui.components.SectionHeader
import se.partee71.ui.theme.Spacing

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * Fonddetalj — appens beslutsstöd för en enskild fond (ANA-10, issue #85). Kortet leder med
 * **bytesbeslutet**: verdiktrad (säljsignal-status ANA-3, risknivå UI-10 och neutral kontext
 * ANA-6) följd av de bytesförslag som rör fonden — riskprofilens bytesplan (HEM-8) och de
 * billigare, likvärdiga alternativen (ANA-9) — där varje förslag kan fällas ut till ett
 * jämförelsediagram (ANA-11). Under det ligger fondens egen kurshistorik i diagram (issue #7,
 * #7-uppföljning); den radvisa kurstabellen är borttagen (NAV-2).
 *
 * Allt förklarande material — analysens nyckeltal (ANA-1/ANA-7), signalförklaringarna (ANA-5)
 * och ordlistan (ANA-6) — ligger i hopfällda sektioner: det är underlag man slår upp, inte det
 * man öppnar kortet för. Saknar fonden ISIN visas fältet för att ange/bekräfta det (förifyllt
 * med ett namnbaserat förslag om ett hittades), se KRAVLISTA TP-14.
 *
 * Skärmen står på android-ui:s detaljram (`EntityDetailScreen`, nyckeln är en `FrameKey`): liten
 * topprad med fondens namn och bakåtpil; laddning, läsfel med "Försök igen" och "finns inte längre"
 * är ramens lägen (NFR-6), och dra ned hämtar om kursen (UI-11).
 */
@Composable
fun FondDetaljScreen(
    fundId: String,
    onBack: () -> Unit,
    onOpenSwitchWatch: (Long) -> Unit,
    onOpenTransactionForm: (TransactionFormKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FondDetaljViewModel = hiltViewModel<FondDetaljViewModel, FondDetaljViewModel.Factory> { it.create(fundId) }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Samma mönster som Hem (HEM-11): en nystartad bevakning öppnas direkt, annars ser knappen
    // ut som om den inte gjorde något — bevakningen är tom tills kandidaterna hämtats.
    val openSwitchWatch by rememberUpdatedState(onOpenSwitchWatch)
    LaunchedEffect(state.startedSwitchWatchId) {
        state.startedSwitchWatchId?.let { watchId ->
            openSwitchWatch(watchId)
            viewModel.onEvent(FondDetaljEvent.SwitchWatchOpened)
        }
    }

    FondDetaljContent(
        state = state,
        onBack = onBack,
        onRefresh = { viewModel.onEvent(FondDetaljEvent.Refresh) },
        onIsinConfirm = { viewModel.onEvent(FondDetaljEvent.IsinConfirmed(it)) },
        onSuggestionExpand = { viewModel.onEvent(FondDetaljEvent.SuggestionExpanded(it)) },
        onFollow = { suggestion -> onOpenTransactionForm(TransactionFormKey.follow(suggestion)) },
        onFollowFee = { recordId -> onOpenTransactionForm(TransactionFormKey.follow(fundId, recordId)) },
        onStartSwitchWatch = { viewModel.onEvent(FondDetaljEvent.StartSwitchWatch(it)) },
        onRetry = { viewModel.onEvent(FondDetaljEvent.Retry) },
        modifier = modifier
    )
}

/**
 * Tillståndsdriven, testbar del av [FondDetaljScreen] — inget ViewModel/Hilt-beroende (issue #16).
 * Detaljramen ritar toppraden (fondens namn, "Fond" tills det lästs) och lägena laddning, läsfel och
 * "finns inte längre" ur [FondDetaljUiState.fund] (NFR-6); innehållet är sektioner 12 dp isär.
 */
@Composable
fun FondDetaljContent(
    state: FondDetaljUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onIsinConfirm: (String) -> Unit = {},
    onSuggestionExpand: (String) -> Unit = {},
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit = {},
    onFollowFee: (Long) -> Unit = {},
    onStartSwitchWatch: (SwitchPlanResolver.Suggestion) -> Unit = {},
    onRetry: () -> Unit = {}
) {
    EntityDetailScreen(
        state = state.fund,
        // Inget centrerat huvud: namnet står i den lilla toppraden, och märkningarna (status,
        // risknivå, väntesnurra) vänsterställda överst i innehållet.
        header = null,
        onBack = onBack,
        title = state.content.fundName ?: stringResource(R.string.fond_title),
        onRetry = onRetry,
        sectionGap = Spacing.m,
        // Dra ned hämtar om fondens kurs och historik (UI-11) — samma kodväg som skärmöppningen,
        // se FondDetaljViewModel.refresh.
        refreshing = state.priceRefreshing,
        onRefresh = onRefresh,
        // Skalet lägger ingen imePadding runt en ramskärm, och detaljramen (till skillnad från
        // redigeringsramen) har ingen egen – utan den hamnade tangentbordet över ISIN-fältet (issue #78).
        modifier = modifier.imePadding()
    ) { fund ->
        if (fund.prices.isEmpty()) {
            EmptyState(
                icon = R.drawable.ic_chart,
                // Egen rubrik – fondens namn står redan i toppraden.
                title = stringResource(R.string.fond_history_empty_title),
                message = stringResource(R.string.fond_history_empty_body)
            )
        } else {
            FondDetaljSections(
                state = state,
                fund = fund,
                onIsinConfirm = onIsinConfirm,
                onSuggestionExpand = onSuggestionExpand,
                onFollow = onFollow,
                onFollowFee = onFollowFee,
                onStartSwitchWatch = onStartSwitchWatch
            )
        }
    }
}

/** Fonddetaljens sektioner i ramens kolumn, i den ordning ANA-10 anger. */
@Composable
private fun ColumnScope.FondDetaljSections(
    state: FondDetaljUiState,
    fund: FundDetail,
    onIsinConfirm: (String) -> Unit,
    onSuggestionExpand: (String) -> Unit,
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit,
    onFollowFee: (Long) -> Unit,
    onStartSwitchWatch: (SwitchPlanResolver.Suggestion) -> Unit
) {
    // remember: listan är redan sorterad (fallande) av ViewModel:en, så det här är en ren
    // omvändning + mappning — men utan remember kördes den vid varje recomposition, och
    // eftersom listans identitet då ändrades triggades hela Vico-kedjan (periodfilter,
    // LaunchedEffect, ny transaktion) på nytt vid varje kurstick. En backfillad fond har
    // flera tusen punkter (issue #78).
    val chartPoints = remember(fund.prices) {
        fund.prices.sortedBy { it.epochDay }.map { it.epochDay to it.nav }
    }
    FundHeader(state = state)
    if (fund.analysis != null) {
        AnalysisStatusBanner(analysis = fund.analysis)
        AnalysisGuidanceCard(analysis = fund.analysis)
    }
    SwitchDecisionSection(
        state = state,
        chartPoints = chartPoints,
        onSuggestionExpand = onSuggestionExpand,
        onFollow = onFollow,
        onFollowFee = onFollowFee,
        onStartSwitchWatch = onStartSwitchWatch
    )
    AppDivider()
    FundLineChart(
        points = chartPoints,
        purchaseEpochDays = fund.purchaseEpochDays,
        movingAverage = fund.movingAverage,
        working = state.chartWorking
    )
    // Allt förklarande material ligger hopfällt (ANA-10): analysen och ordlistan är referens man
    // slår upp, inte det man öppnar kortet för.
    if (fund.analysis != null) {
        ExpandableSection(title = stringResource(R.string.analys_section_title)) {
            AnalysisSection(analysis = fund.analysis)
        }
    }
    ExpandableSection(title = stringResource(R.string.analys_glossary_title)) {
        AnalysisGlossary()
    }
    if (fund.isin == null) {
        IsinInput(
            suggestedIsin = fund.suggestedIsin,
            onConfirm = onIsinConfirm,
            modifier = Modifier.padding(bottom = Spacing.s)
        )
    }
}

/**
 * Märkningarna överst i innehållet: säljsignal-status (ANA-3), risknivå (UI-10), väntesnurran
 * (NAV-6) och innehavets utgångsläge (POR-6). Fondens namn står i ramens topprad.
 */
@Composable
private fun FundHeader(state: FondDetaljUiState, modifier: Modifier = Modifier) {
    val content = state.content
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            content.analysis?.status?.let { status -> StatusDot(status) }
            RiskBadge(level = state.riskLevel)
            // Väntesnurran (NAV-6): fondens kurs och historik hämtas när skärmen öppnas, och
            // nyckeltalen under räknas på det som hunnit landa.
            WorkingIndicator(
                working = state.chartWorking,
                contentDescription = stringResource(R.string.format_card_working, content.fundName ?: "")
            )
        }
        val firstPurchaseEpochDay = content.firstPurchaseEpochDay
        val netInvested = content.netInvested
        if (firstPurchaseEpochDay != null && netInvested != null) {
            ItemRowText(
                title = null,
                subtitle = stringResource(
                    R.string.format_holding_first_purchase,
                    LocalDate.ofEpochDay(firstPurchaseEpochDay).format(dateFormatter),
                    MoneyFormat.kr(netInvested)
                )
            )
        }
    }
}

/**
 * Kortets bytesavsnitt (ANA-10, issue #85) — det man öppnar fondkortet för: *ska jag byta, och
 * i så fall till vad?* Ligger därför överst, före analysen, och samlar båda källorna appen har
 * till ett bytesförslag för den här fonden:
 *
 * - riskprofilens inspelade bytesplan ([SwitchPlanResolver], HEM-8) — det enda förslaget som
 *   nämner fonden vid namn i **båda** riktningarna (sälj härifrån / köp hit), och
 * - de billigare, likvärdiga alternativen (ANA-9), som byter avgift utan att byta exponering.
 *
 * Varje rad är hopfälld till namn, risknivå och det tal som avgör (belopp respektive
 * årsbesparing); utfälld visar den motiveringen och jämförelsediagrammet (ANA-11). Fortfarande
 * ingen åtgärdsknapp — appen rör aldrig innehavet, den visar bara underlaget (samma princip som
 * HEM-8:s rena läsvy).
 */
@Composable
private fun SwitchDecisionSection(
    state: FondDetaljUiState,
    chartPoints: List<Pair<Long, Double>>,
    onSuggestionExpand: (String) -> Unit,
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit,
    onFollowFee: (Long) -> Unit,
    onStartSwitchWatch: (SwitchPlanResolver.Suggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    val content = state.content
    Column(modifier = modifier) {
        SectionHeader(title = stringResource(R.string.switch_section_title))
        ItemRowText(
            title = null,
            subtitle = stringResource(R.string.switch_section_explain),
            modifier = Modifier.padding(bottom = Spacing.s)
        )

        if (state.switchPlan.isNotEmpty()) {
            SwitchGroupLabel(stringResource(R.string.switch_plan_title))
            state.switchPlan.forEach { suggestion ->
                SwitchPlanRow(
                    suggestion = suggestion,
                    fundIsin = content.isin,
                    chartPoints = chartPoints,
                    comparison = state.comparisons[suggestion.counterpartIsin(content.isin)],
                    onSuggestionExpand = onSuggestionExpand,
                    onFollow = onFollow,
                    watched = suggestion.sellIsin in state.watchedSellIsins,
                    onStartSwitchWatch = onStartSwitchWatch
                )
            }
        }

        // Avgiftsjämförelsen (ANA-9) behåller sin egen rubrik inne i avsnittet: den svarar på en
        // annan fråga än bytesplanen — byt avgift utan att byta exponering, i stället för att
        // flytta portföljen mot målfördelningen — och de två råden ska inte se ut som ett.
        if (state.feeComparison != null) {
            SwitchGroupLabel(stringResource(R.string.fee_comparison_title))
            ItemRowText(
                title = null,
                subtitle = stringResource(R.string.fee_comparison_explain),
                modifier = Modifier.padding(bottom = Spacing.xs)
            )
        }
        when (val feeComparison = state.feeComparison) {
            null -> Unit

            FeeComparisonUiState.Loading -> SwitchWorkingMessage(stringResource(R.string.fee_comparison_loading))

            FeeComparisonUiState.Unavailable -> SwitchMessage(stringResource(R.string.fee_comparison_unavailable))

            FeeComparisonUiState.NoCheaperAlternative -> SwitchMessage(stringResource(R.string.fee_comparison_none_cheaper))

            is FeeComparisonUiState.Found -> feeComparison.alternatives.forEach { alternative ->
                FeeAlternativeRow(
                    alternative = alternative,
                    chartPoints = chartPoints,
                    comparison = state.comparisons[alternative.candidate.isin],
                    recorded = state.recordedFeeSwitches[alternative.candidate.isin],
                    onSuggestionExpand = onSuggestionExpand,
                    onFollowFee = onFollowFee
                )
            }
        }

        // Tomt-tillstånd med **orsak**: "du äger inte fonden", "planen föreslår inget" och
        // "kunde inte jämföras" (avgiftssidans egna texter ovan) är olika svar, och en tom yta
        // hade lästs som att appen inte gjort något (ANA-4-principen). Innan jämförelsen ens
        // hunnit starta sägs ingenting — då är frågan obesvarad, inte besvarad med "nej".
        when {
            content.analysis == null -> SwitchMessage(stringResource(R.string.switch_none_not_a_holding))

            state.switchPlan.isEmpty() && state.feeComparison == null -> Unit

            // Ett avstått byte (HEM-14) är inte "planen föreslår inget" — det sägs ut nedan.
            state.switchPlan.isEmpty() && state.dismissedPlanCount == 0 -> SwitchMessage(stringResource(R.string.switch_none_plan_body))

            else -> Unit
        }
        // Avstådda förslag döljs här som på Beslut; antalet och vägen till Ångra sägs ut, så ett
        // dolt förslag inte läses som att appen inte har något att säga om fonden.
        if (state.dismissedCount > 0) {
            SwitchMessage(pluralStringResource(R.plurals.format_switch_dismissed, state.dismissedCount, state.dismissedCount))
        }
    }
}

/** Den *andra* fonden i ett byte sett från fonden [fundIsin] — den som ska jämföras mot. */
private fun SwitchPlanResolver.Suggestion.counterpartIsin(fundIsin: String?): String = if (sellIsin == fundIsin) buyIsin else sellIsin

/** Liten grupprubrik inne i bytesavsnittet — skiljer bytesplanen (HEM-8) från avgiftsjämförelsen (ANA-9). */
@Composable
private fun SwitchGroupLabel(text: String) {
    GroupLabel(text, modifier = Modifier.padding(top = Spacing.s, bottom = Spacing.xs))
}

/**
 * Som [SwitchMessage], men för ett svar som är **på väg** i stället för ett som är givet:
 * texten sa redan "letar …", medan det som saknades var kvittot att något faktiskt kör (NAV-6).
 * En hämtning mot en odokumenterad källa (TP-14) kan ta flera sekunder, och en stillastående
 * text går inte att skilja från en som fastnat.
 */
@Composable
private fun SwitchWorkingMessage(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Samma toppmarginal som texten, så snurran ligger i linje med raden i stället för att
        // dras ned av `SwitchMessage`s egen padding.
        WorkingIndicator(
            working = true,
            contentDescription = text,
            modifier = Modifier.padding(top = Spacing.s, end = Spacing.s)
        )
        SwitchMessage(text)
    }
}

@Composable
private fun SwitchMessage(text: String) {
    ItemRowText(title = null, subtitle = text, modifier = Modifier.padding(top = Spacing.s))
}

/**
 * En rad ur riskprofilens bytesplan (HEM-8) sedd från den här fonden: "Byt till X" när fonden
 * är säljkandidat, "Byt hit från Y" när den är köpkandidat. Rangordningen kommer ur
 * [SwitchPlanResolver.Suggestion.planIndex], aldrig ur listpositionen — planen är girig och
 * sekventiell, så numret är en del av rådet (issue #75).
 */
@Composable
private fun SwitchPlanRow(
    suggestion: SwitchPlanResolver.Suggestion,
    fundIsin: String?,
    chartPoints: List<Pair<Long, Double>>,
    comparison: ComparisonUiState?,
    onSuggestionExpand: (String) -> Unit,
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit,
    watched: Boolean,
    onStartSwitchWatch: (SwitchPlanResolver.Suggestion) -> Unit
) {
    val sellingThisFund = suggestion.sellIsin == fundIsin
    val counterpartName = if (sellingThisFund) suggestion.buyFundName else suggestion.sellFundName
    val counterpartIsin = suggestion.counterpartIsin(fundIsin)
    val explanation = listOfNotNull(
        suggestion.switchValueKr?.let { stringResource(R.string.format_switch_plan_amount, MoneyFormat.kr(it)) },
        stringResource(R.string.format_switch_plan_fee_delta, MoneyFormat.percentSigned(suggestion.feeDeltaPercent / 100.0)),
        stringResource(R.string.switch_plan_disclaimer)
    ).joinToString("\n")

    Column {
        ExpandableInfoRow(
            explanation = explanation,
            onExpand = { onSuggestionExpand(counterpartIsin) },
            extraContent = {
                ComparisonChart(
                    holdingPoints = chartPoints,
                    candidateName = counterpartName,
                    comparison = comparison
                )
            }
        ) {
            Column {
                ItemRowText(
                    title = stringResource(
                        if (sellingThisFund) R.string.format_switch_plan_to else R.string.format_switch_plan_from,
                        suggestion.planIndex + 1,
                        counterpartName
                    ),
                    subtitle = null
                )
                // Riskförändringen är bytets, inte den betraktande fondens: pengarna går alltid
                // från säljkandidatens nivå till köpkandidatens. Att vända pilen när man tittar på
                // köpkandidaten hade visat "Risk 5 → 4" för ett byte som faktiskt höjer risken.
                RiskBadge(
                    level = suggestion.fromLevel,
                    toLevel = suggestion.toLevel,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }
        // Följ ligger **utanför** den utfällbara raden, inte i dess rubrik: rubriken är en
        // `clickable` som slår ihop sina barns semantik, så knappen hade blivit en del av "fäll
        // ut"-noden för skärmläsaren — och ett tryck hade fällt ut raden. Följ öppnar
        // säljformuläret förifyllt (BYT-2); kvitterat (i Facit) eller redan pågående (BYT-1)
        // har förslaget inget sälj kvar att registrera.
        if (!suggestion.followed && !suggestion.inProgress) {
            AppButton(
                text = stringResource(R.string.beslut_follow),
                onClick = { onFollow(suggestion) },
                variant = ButtonVariant.Outlined,
                contentDescription = stringResource(R.string.format_beslut_follow_a11y, suggestion.sellFundName, suggestion.buyFundName)
            )
        }
        // Bevaka alternativ (ANA-12) — för ett förslag kvitterat i Facit, av samma skäl som på
        // Beslut: bevakningen gäller perioden **efter** säljet, och en öppen bevakning för
        // säljfonden ska inte kunna startas en gång till.
        if (suggestion.followed && !watched) {
            AppButton(
                text = stringResource(R.string.switch_watch_start),
                onClick = { onStartSwitchWatch(suggestion) },
                variant = ButtonVariant.Text
            )
        }
    }
}

/**
 * Ett billigare, likvärdigt alternativ (ANA-9) — namn, årsbesparing och kandidatens risknivå
 * (UI-10) direkt på raden, avgiften och jämförelsediagrammet under när den fälls ut.
 *
 * Följ (BYT-2) visas bara när alternativet har en inspelad rad ([recorded], issue #91) som inte
 * redan kvitterats. Listan här räknas om mot dagsfärska kurser varje gång kortet öppnas, medan
 * inspelningen sker en gång per dygn i bakgrundsjobbet — ett alternativ som just dykt upp har
 * alltså ännu inget förslag att registrera säljet *för*, och en knapp som tyst inte kopplar till
 * något vore värre än ingen.
 */
@Composable
private fun FeeAlternativeRow(
    alternative: FeeComparisonCalc.Alternative,
    chartPoints: List<Pair<Long, Double>>,
    comparison: ComparisonUiState?,
    recorded: RecordedFeeSwitch?,
    onSuggestionExpand: (String) -> Unit,
    onFollowFee: (Long) -> Unit
) {
    val explanation = stringResource(
        R.string.format_fee_comparison_alternative_explain,
        MoneyFormat.feePercent(alternative.candidateFeePercent)
    )
    Column {
        ExpandableInfoRow(
            explanation = explanation,
            onExpand = { onSuggestionExpand(alternative.candidate.isin) },
            extraContent = {
                ComparisonChart(
                    holdingPoints = chartPoints,
                    candidateName = alternative.candidate.name,
                    comparison = comparison
                )
            }
        ) {
            Column {
                PeriodRow(
                    label = alternative.candidate.name,
                    amount = alternative.annualSavingsKr,
                    fraction = null
                )
                RiskBadge(level = alternative.candidate.risk, modifier = Modifier.padding(top = Spacing.xs))
            }
        }
        // Utanför den utfällbara raden av samma skäl som i SwitchPlanRow: rubriken är en
        // `clickable` som slår ihop sina barns semantik.
        if (recorded != null && !recorded.followed) {
            AppButton(
                text = stringResource(R.string.beslut_follow),
                onClick = { onFollowFee(recorded.recordId) },
                variant = ButtonVariant.Outlined,
                contentDescription = stringResource(R.string.format_fee_follow_a11y, alternative.candidate.name)
            )
        }
    }
}

/**
 * Jämförelsediagrammet för ett utfällt förslag (ANA-11) — innehavets och kandidatens kurvor i
 * samma [FundLineChart], indexerade till 100 vid periodens start (regel 4: den delade
 * komponenten utökades, ingen ny diagramvariant). Går kandidatens historik inte att hämta sägs
 * det ut i stället för att ett tomt diagram ritas.
 */
@Composable
private fun ComparisonChart(
    holdingPoints: List<Pair<Long, Double>>,
    candidateName: String,
    comparison: ComparisonUiState?
) {
    when (comparison) {
        null -> Unit

        ComparisonUiState.Loading -> SwitchWorkingMessage(stringResource(R.string.switch_comparison_loading))

        ComparisonUiState.Unavailable -> ItemRowText(title = null, subtitle = stringResource(R.string.switch_comparison_unavailable))

        is ComparisonUiState.Ready -> FundLineChart(
            points = holdingPoints,
            comparisonSeries = listOf(ChartSeries(label = candidateName, points = comparison.points)),
            modifier = Modifier.padding(bottom = Spacing.s)
        )
    }
}

/**
 * Analysens nyckeltal och signalförklaringar (ANA-1/ANA-5/ANA-7). Statusbannern (ANA-3) och
 * kontexttexten (ANA-6) ligger **inte** här längre utan uppe i rubriken (ANA-10, issue #85):
 * de är verdikten kortet leder med, medan den här sektionen är underlaget man slår upp — och
 * ligger därför hopfälld.
 */
@Composable
private fun AnalysisSection(analysis: FundAnalysisCalc.Analysis, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        SignalExplanations(analysis = analysis)
        Column(modifier = Modifier.padding(top = Spacing.s)) {
            analysis.keyFigures.periodReturns.forEach { periodReturn ->
                // "Sedan köp" blandar två mått: procenten är fondens kursutveckling sedan
                // förstaköpsdagen, kronorna positionens vinst mot anskaffningsvärdet (samma tal som
                // GAV-raden, issue #193). Egen förklaring bara för den perioden; övriga delar den
                // generiska texten.
                val explanation = if (periodReturn.period == FundAnalysisCalc.Period.SEDAN_KOP) {
                    stringResource(R.string.analys_period_sedan_kop_explain)
                } else {
                    stringResource(R.string.analys_period_explain)
                }
                ExpandableInfoRow(explanation = explanation) {
                    PeriodRow(
                        label = periodLabel(periodReturn.period),
                        amount = periodReturn.amount,
                        fraction = periodReturn.fraction
                    )
                }
            }
            ExpandableInfoRow(explanation = stringResource(R.string.analys_cagr_explain)) {
                PeriodRow(label = stringResource(R.string.analys_cagr_label), amount = null, fraction = analysis.keyFigures.cagr)
            }
            ExpandableInfoRow(explanation = stringResource(R.string.analys_gav_explain)) {
                // GAV per andel hör hemma i **etiketten**, inte i beloppsplatsen: den platsen
                // bär vinst/förlust i kronor på varje annan rad, så ett pris där lästes som en
                // vinst — och färgade dessutom raden grön även för ett innehav under GAV, eftersom
                // priset alltid är positivt (issue #78). Procenten är den enda avkastningen på
                // raden och är det som tecken- och färgkodas.
                PeriodRow(
                    label = stringResource(
                        R.string.format_analys_gav_label,
                        MoneyFormat.kr(analysis.keyFigures.gavPerShare)
                    ),
                    amount = null,
                    fraction = analysis.keyFigures.gavFraction
                )
            }
            ExpandableInfoRow(explanation = stringResource(R.string.analys_portfolio_share_explain)) {
                PeriodRow(
                    label = stringResource(R.string.analys_portfolio_share_label),
                    amount = null,
                    fraction = analysis.keyFigures.portfolioShareFraction
                )
            }
            ExpandableInfoRow(explanation = stringResource(R.string.analys_volatility_explain)) {
                PeriodRow(
                    label = stringResource(R.string.analys_volatility_label),
                    amount = null,
                    fraction = null,
                    valueText = analysis.keyFigures.annualizedVolatility?.let { MoneyFormat.percent(it) }
                )
            }
            ExpandableInfoRow(explanation = stringResource(R.string.analys_sharpe_explain)) {
                PeriodRow(
                    label = stringResource(R.string.analys_sharpe_label),
                    amount = null,
                    fraction = null,
                    valueText = analysis.keyFigures.sharpeRatio?.let { MoneyFormat.decimal(it) }
                )
            }
        }
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
                explanation = stringResource(R.string.analys_signal_distance_explain)
            )
        }
        analysis.trend?.let { signal ->
            SignalRow(
                level = signal.level,
                label = stringResource(R.string.analys_signal_trend_label),
                explanation = stringResource(R.string.analys_signal_trend_explain)
            )
        }
        analysis.momentum?.let { signal ->
            SignalRow(
                level = signal.level,
                label = stringResource(R.string.analys_signal_momentum_label),
                explanation = stringResource(R.string.analys_signal_momentum_explain)
            )
        }
    }
}

@Composable
private fun SignalRow(level: FundAnalysisCalc.SignalLevel, label: String, explanation: String) {
    ExpandableInfoRow(explanation = explanation) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(level, modifier = Modifier.padding(end = Spacing.s))
            ItemRowText(title = label, subtitle = null)
        }
    }
}

/**
 * Kort "Så funkar analysen"-ordlista (ANA-6) — de begrepp appen faktiskt visar, var och en
 * utfällbar. Rubriken sätts av den hopfällda sektionen runt omkring (ANA-10), inte här.
 */
@Composable
private fun AnalysisGlossary(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        val terms = listOf(
            R.string.analys_glossary_nav_term to R.string.analys_glossary_nav_def,
            R.string.analys_glossary_gav_term to R.string.analys_glossary_gav_def,
            R.string.analys_glossary_cagr_term to R.string.analys_glossary_cagr_def,
            R.string.analys_glossary_sma_term to R.string.analys_glossary_sma_def,
            R.string.analys_glossary_topp_term to R.string.analys_glossary_topp_def,
            R.string.analys_glossary_horisont_term to R.string.analys_glossary_horisont_def,
            R.string.analys_glossary_ranta_term to R.string.analys_glossary_ranta_def,
            R.string.analys_glossary_volatilitet_term to R.string.analys_glossary_volatilitet_def,
            R.string.analys_glossary_sharpe_term to R.string.analys_glossary_sharpe_def
        )
        terms.forEach { (termRes, defRes) ->
            ExpandableInfoRow(explanation = stringResource(defRes)) {
                ItemRowText(title = stringResource(termRes), subtitle = null)
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
    modifier: Modifier = Modifier
) {
    // rememberSaveable, inte remember: ISIN:et skrivs in för hand (NAV-2) och ska överleva en
    // rotation eller ett tema-/språkbyte. Med remember återställdes fältet till maskinens
    // *förslag*, som kan vara fel — sparade användaren utan att läsa om hamnade fel ISIN på
    // fonden och kurscachen började fyllas från en annan fonds historik (issue #75).
    var text by rememberSaveable { mutableStateOf(suggestedIsin.orEmpty()) }
    LaunchedEffect(suggestedIsin) {
        if (text.isEmpty()) text = suggestedIsin.orEmpty()
    }

    Column(modifier = modifier) {
        ItemRowText(
            title = null,
            subtitle = stringResource(
                if (suggestedIsin != null) R.string.fond_isin_suggested_body else R.string.fond_isin_missing_body
            )
        )
        AppTextField(
            value = text,
            onValueChange = { text = it },
            label = stringResource(R.string.fond_isin_label),
            // ISIN är versaler och siffror (SE0000…) – inga autokorrigerade ord.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii
            ),
            modifier = Modifier.padding(top = Spacing.s)
        )
        AppButton(
            text = stringResource(R.string.save),
            onClick = { onConfirm(text) },
            enabled = text.isNotBlank(),
            modifier = Modifier.padding(top = Spacing.s)
        )
    }
}
