package se.partee71.fonder.ui.fond

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import se.partee71.fonder.ui.components.AppButton
import se.partee71.fonder.ui.components.AppButtonVariant
import se.partee71.fonder.ui.components.AppTextField
import se.partee71.fonder.ui.components.CenteredState
import se.partee71.fonder.ui.components.ExpandableInfoRow
import se.partee71.fonder.ui.components.ExpandableSection
import se.partee71.fonder.ui.components.PeriodRow
import se.partee71.fonder.ui.components.PullToRefreshContainer
import se.partee71.fonder.ui.components.RiskBadge
import se.partee71.fonder.ui.components.StatusDot
import se.partee71.fonder.ui.components.WorkingIndicator
import se.partee71.fonder.ui.diagram.ChartSeries
import se.partee71.fonder.ui.diagram.FundLineChart
import se.partee71.fonder.ui.navigation.TransactionFormKey
import se.partee71.ui.components.EmptyState
import se.partee71.ui.components.LoadErrorState
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
 */
@Composable
fun FondDetaljScreen(
    fundId: String,
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
            viewModel.onSwitchWatchOpened()
        }
    }

    FondDetaljContent(
        state = state,
        onRefresh = viewModel::refresh,
        onIsinConfirmed = viewModel::onIsinConfirmed,
        onSuggestionExpanded = viewModel::onSuggestionExpanded,
        onFollow = { suggestion -> onOpenTransactionForm(TransactionFormKey.follow(suggestion)) },
        onFollowFee = { recordId -> onOpenTransactionForm(TransactionFormKey.follow(fundId, recordId)) },
        onStartSwitchWatch = viewModel::startSwitchWatch,
        onRetry = viewModel::retry,
        modifier = modifier
    )
}

/** Tillståndsdriven, testbar del av [FondDetaljScreen] — inget ViewModel/Hilt-beroende (issue #16). */
@Composable
fun FondDetaljContent(
    state: FondDetaljUiState,
    onRefresh: () -> Unit = {},
    onIsinConfirmed: (String) -> Unit = {},
    onSuggestionExpanded: (String) -> Unit = {},
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit = {},
    onFollowFee: (Long) -> Unit = {},
    onStartSwitchWatch: (SwitchPlanResolver.Suggestion) -> Unit = {},
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {}
) {
    val error = state.error
    val content = state.content
    when {
        // Ett läsfel visas i stället för fonden, aldrig en tom fond – med "Försök igen" (NFR-6).
        error != null -> CenteredState(modifier) { LoadErrorState(stringResource(R.string.error_load_title), error, onRetry) }

        state.isEmpty -> CenteredState(modifier) {
            EmptyState(
                icon = R.drawable.ic_chart,
                title = content.fundName ?: stringResource(R.string.fond_title),
                message = stringResource(R.string.fond_history_empty_body)
            )
        }

        // remember: listan är redan sorterad (fallande) av ViewModel:en, så det här är en ren
        // omvändning + mappning — men utan remember kördes den vid varje recomposition, och
        // eftersom listans identitet då ändrades triggades hela Vico-kedjan (periodfilter,
        // LaunchedEffect, ny transaktion) på nytt vid varje kurstick. En backfillad fond har
        // flera tusen punkter (issue #78).
        else -> {
            val chartPoints = remember(content.prices) {
                content.prices.sortedBy { it.epochDay }.map { it.epochDay to it.nav }
            }
            // Ett enda `item`, inte ett per sektion: utan kurstabellen finns inga lazy rader kvar
            // att vinna något på, och en icke-komponerad sektion hade gjort resten av kortet
            // onåbart för `performScrollTo` i instrumenttesterna. LazyColumn behålls för att vyn
            // ska vara skrollbar (UI-5).
            // Dra ned hämtar om fondens kurs och historik (UI-11) — samma kodväg som
            // skärmöppningen använder, se FondDetaljViewModel.refresh.
            PullToRefreshContainer(
                refreshing = state.priceRefreshing,
                onRefresh = onRefresh,
                modifier = modifier
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = Spacing.l)) {
                            FundHeader(state = state, modifier = Modifier.padding(top = Spacing.l))
                            if (content.analysis != null) {
                                AnalysisStatusBanner(analysis = content.analysis, modifier = Modifier.padding(top = Spacing.m))
                                AnalysisGuidanceCard(analysis = content.analysis, modifier = Modifier.padding(top = Spacing.s))
                            }
                            SwitchDecisionSection(
                                state = state,
                                chartPoints = chartPoints,
                                onSuggestionExpanded = onSuggestionExpanded,
                                onFollow = onFollow,
                                onFollowFee = onFollowFee,
                                onStartSwitchWatch = onStartSwitchWatch,
                                modifier = Modifier.padding(vertical = Spacing.l)
                            )
                            HorizontalDivider()
                            FundLineChart(
                                points = chartPoints,
                                purchaseEpochDays = content.purchaseEpochDays,
                                movingAverage = content.movingAverage,
                                working = state.chartWorking,
                                modifier = Modifier.padding(top = Spacing.l)
                            )
                            // Allt förklarande material ligger hopfällt (ANA-10): analysen och
                            // ordlistan är referens man slår upp, inte det man öppnar kortet för.
                            if (content.analysis != null) {
                                ExpandableSection(
                                    title = stringResource(R.string.analys_section_title),
                                    modifier = Modifier.padding(top = Spacing.s)
                                ) {
                                    AnalysisSection(analysis = content.analysis)
                                }
                            }
                            ExpandableSection(
                                title = stringResource(R.string.analys_glossary_title),
                                modifier = Modifier.padding(top = Spacing.s)
                            ) {
                                AnalysisGlossary()
                            }
                            if (content.isin == null) {
                                IsinInput(
                                    suggestedIsin = content.suggestedIsin,
                                    onConfirm = onIsinConfirmed,
                                    modifier = Modifier.padding(vertical = Spacing.l)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Fondens rubrikrad: namn, säljsignal-status (ANA-3), risknivå (UI-10) och innehavets utgångsläge (POR-6). */
@Composable
private fun FundHeader(state: FondDetaljUiState, modifier: Modifier = Modifier) {
    val content = state.content
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // UI-6: fondnamnet är det som kapas, aldrig märkningarna till höger.
            Text(
                content.fundName ?: "",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            content.analysis?.status?.let { status -> StatusDot(status, modifier = Modifier.padding(start = Spacing.s)) }
            RiskBadge(level = state.riskLevel, modifier = Modifier.padding(start = Spacing.s))
            // Rubrikens väntesnurra (NAV-6): fondens kurs och historik hämtas när skärmen
            // öppnas, och nyckeltalen under rubriken räknas på det som hunnit landa.
            WorkingIndicator(
                working = state.chartWorking,
                contentDescription = stringResource(R.string.format_card_working, content.fundName ?: ""),
                modifier = Modifier.padding(start = Spacing.s)
            )
        }
        val firstPurchaseEpochDay = content.firstPurchaseEpochDay
        val netInvested = content.netInvested
        if (firstPurchaseEpochDay != null && netInvested != null) {
            Text(
                stringResource(
                    R.string.format_holding_first_purchase,
                    LocalDate.ofEpochDay(firstPurchaseEpochDay).format(dateFormatter),
                    MoneyFormat.kr(netInvested)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
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
    onSuggestionExpanded: (String) -> Unit,
    onFollow: (SwitchPlanResolver.Suggestion) -> Unit,
    onFollowFee: (Long) -> Unit,
    onStartSwitchWatch: (SwitchPlanResolver.Suggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    val content = state.content
    Column(modifier = modifier) {
        Text(stringResource(R.string.switch_section_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.switch_section_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.s)
        )

        if (state.switchPlan.isNotEmpty()) {
            SwitchGroupLabel(stringResource(R.string.switch_plan_title))
            state.switchPlan.forEach { suggestion ->
                SwitchPlanRow(
                    suggestion = suggestion,
                    fundIsin = content.isin,
                    chartPoints = chartPoints,
                    comparison = state.comparisons[suggestion.counterpartIsin(content.isin)],
                    onSuggestionExpanded = onSuggestionExpanded,
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
            Text(
                stringResource(R.string.fee_comparison_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    onSuggestionExpanded = onSuggestionExpanded,
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
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = Spacing.s, bottom = Spacing.xs)
    )
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
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.s)
    )
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
    onSuggestionExpanded: (String) -> Unit,
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
            onExpand = { onSuggestionExpanded(counterpartIsin) },
            extraContent = {
                ComparisonChart(
                    holdingPoints = chartPoints,
                    candidateName = counterpartName,
                    comparison = comparison
                )
            }
        ) {
            Column {
                Text(
                    stringResource(
                        if (sellingThisFund) R.string.format_switch_plan_to else R.string.format_switch_plan_from,
                        suggestion.planIndex + 1,
                        counterpartName
                    ),
                    style = MaterialTheme.typography.bodyMedium
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
                variant = AppButtonVariant.Outlined,
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
                variant = AppButtonVariant.Text
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
    onSuggestionExpanded: (String) -> Unit,
    onFollowFee: (Long) -> Unit
) {
    val explanation = stringResource(
        R.string.format_fee_comparison_alternative_explain,
        MoneyFormat.feePercent(alternative.candidateFeePercent)
    )
    Column {
        ExpandableInfoRow(
            explanation = explanation,
            onExpand = { onSuggestionExpanded(alternative.candidate.isin) },
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
                variant = AppButtonVariant.Outlined,
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

        ComparisonUiState.Unavailable -> Text(
            stringResource(R.string.switch_comparison_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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
                // "Sedan köp" krockar lätt med "min vinst" — den visar fondens kursutveckling
                // sedan förstaköpsdagen, inte den egna avkastningen (som GAV-raden visar). Egen,
                // tydligare förklaring bara för den perioden; övriga delar den generiska texten.
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
            Text(label, style = MaterialTheme.typography.bodyMedium)
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
        Text(
            text = stringResource(
                if (suggestedIsin != null) R.string.fond_isin_suggested_body else R.string.fond_isin_missing_body
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppTextField(
            value = text,
            onValueChange = { text = it },
            label = stringResource(R.string.fond_isin_label),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s)
        )
        AppButton(
            text = stringResource(R.string.save),
            onClick = { onConfirm(text) },
            enabled = text.isNotBlank(),
            modifier = Modifier.padding(top = Spacing.s)
        )
    }
}
