package se.partee71.fonder.ui.hem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.usecase.BackupReminder
import se.partee71.fonder.domain.usecase.BackupReminderCalc
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.PendingDecisions
import se.partee71.fonder.domain.usecase.PortfolioFeeCalc
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import se.partee71.fonder.domain.usecase.PortfolioRiskCalc
import se.partee71.fonder.ui.components.AppCard
import se.partee71.fonder.ui.components.AppCardVariant
import se.partee71.fonder.ui.components.CardTitleRow
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.ExposureBar
import se.partee71.fonder.ui.components.PeriodRow
import se.partee71.fonder.ui.components.PullToRefreshContainer
import se.partee71.fonder.ui.components.ReminderRow
import se.partee71.fonder.ui.components.ReminderRowTone
import se.partee71.fonder.ui.components.StatusDot
import se.partee71.fonder.ui.components.ValueAsOfRow
import se.partee71.fonder.ui.components.statusTriggerMessages
import se.partee71.fonder.ui.diagram.ChartSeries
import se.partee71.fonder.ui.diagram.ChartValueAxis
import se.partee71.fonder.ui.diagram.FundLineChart
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.fonder.ui.theme.ReturnColors

@Composable
fun HemScreen(
    onFundClick: (String) -> Unit,
    onOpenBeslut: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenBackup: () -> Unit = {},
    viewModel: HemViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    HemContent(
        state = state,
        onRefresh = viewModel::refresh,
        onFundClick = onFundClick,
        onOpenBeslut = onOpenBeslut,
        onOpenBackup = onOpenBackup,
        modifier = modifier,
    )
}

/**
 * Tillståndsdriven, testbar del av [HemScreen] — inget ViewModel/Hilt-beroende (issue #14).
 * `LazyColumn` (samma mönster som [se.partee71.fonder.ui.fond.FondDetaljScreen], regel 4) —
 * en vanlig `Column(fillMaxSize())` klippte tyst innehåll som växte förbi skärmhöjden
 * (analys-summeringskortets flaggade fonder, HEM-4, plus avgiftskortets rader, HEM-5) i
 * stället för att göra det nåbart (UI-5, issue #63).
 */
/**
 * Adresserar Hems lista för `performScrollToIndex` i tester (UI-5, issue #66) — samma skäl och
 * samma mönster som Portfölj, Facit och Pågående byte redan använder. Korten ligger som var sitt
 * `item {}` och komponeras alltså inte förrän listan skrollats dit; utan taggen kunde ett test
 * bara nå de kort som råkade rymmas i testviewporten, och en kortrubrik som gjorde korten några
 * dp högre slog ut testerna för det sista kortet (riskkortet).
 */
const val HEM_LIST_TEST_TAG = "hem_list"

@Composable
fun HemContent(
    state: HemUiState,
    onRefresh: () -> Unit = {},
    onFundClick: (String) -> Unit = {},
    onOpenBeslut: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when {
        // Påminnelsen (HEM-12) även här: en portfölj där allt sålts har inga innehav men
        // fortfarande transaktioner att förlora.
        state.isEmpty -> Column(modifier = modifier) {
            state.backupReminder?.let { BackupReminderRow(it, onOpenBackup, Modifier.padding(16.dp)) }
            EmptyState(
                title = stringResource(R.string.hem_empty_title),
                body = stringResource(R.string.hem_empty_body),
            )
        }

        // Dra ned uppdaterar (UI-11) — bara här, inte i tomt-tillståndet ovan: utan innehav
        // finns ingen kurs att hämta, och en gest som inte gör något är värre än ingen gest.
        else -> PullToRefreshContainer(
            refreshing = state.pricesWorking,
            onRefresh = onRefresh,
            modifier = modifier,
        ) {
            // Ordningen enligt Översiktens mockup: värdet, det som väntar på beslut, jämförelsen
            // mot index och påminnelsen om säkerhetskopia; resten av Hems kort under dem.
            LazyColumn(modifier = Modifier.fillMaxSize().testTag(HEM_LIST_TEST_TAG)) {
                item { TotalCard(state = state) }
                if (state.decisions.count > 0) {
                    item { DecisionsRow(decisions = state.decisions, onOpen = onOpenBeslut) }
                }
                item { ReturnChartCard(state = state) }
                state.backupReminder?.let { reminder ->
                    item { BackupReminderRow(reminder, onOpenBackup, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) }
                }
                item { PerformanceCard(performance = state.performance, working = state.performanceWorking) }
                item {
                    AnalysisSummaryCard(
                        summary = state.analysisSummary,
                        working = state.analysisWorking,
                        onFundClick = onFundClick,
                    )
                }
                item {
                    FeeCard(
                        summary = state.feeSummary,
                        working = state.feeWorking,
                        onFundClick = onFundClick,
                    )
                }
                state.riskProfile?.let { riskProfile ->
                    item {
                        RiskCard(
                            riskProfile = riskProfile,
                            portfolioRisk = state.portfolioRisk,
                            levelDeviations = state.riskLevelDeviations,
                            working = state.riskWorking,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalCard(state: HemUiState) {
    AppCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        CardTitleRow(
            title = stringResource(R.string.portfolj_total_value),
            working = state.totalWorking,
        )
        val fraction = state.totalGainLossFraction
        if (fraction != null) {
            Text(MoneyFormat.kr(state.totalValue), style = MonoAmountStyle.merge(MaterialTheme.typography.headlineMedium))
            Text(
                "${MoneyFormat.percentSigned(fraction)} · ${MoneyFormat.kr(state.totalGainLoss)}",
                style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                color = ReturnColors.forAmount(state.totalGainLoss),
            )
            ValueAsOfRow(navEpochDay = state.navEpochDay, modifier = Modifier.padding(top = 2.dp))
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

/**
 * Dag/vecka/månad. Kortet saknade rubrik så länge det var självförklarande, men en snurra
 * behöver en rad att sitta i — och den raden får inte vara en av periodraderna, där beloppet
 * ligger längst till höger (UI-6). Rubriken är alltså en följd av väntesnurran (NAV-6), inte
 * dekoration.
 */
@Composable
private fun PerformanceCard(
    performance: PortfolioPerformanceCalc.PortfolioPerformance,
    working: Boolean,
) {
    AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        CardTitleRow(
            title = stringResource(R.string.hem_performance_title),
            working = working,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val (dayAmount, dayFraction, dayPartial) = performance.day.toRowArgs()
        PeriodRow(
            label = stringResource(R.string.period_day),
            amount = dayAmount,
            fraction = dayFraction,
            partial = dayPartial,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        val (weekAmount, weekFraction, weekPartial) = performance.week.toRowArgs()
        PeriodRow(
            label = stringResource(R.string.period_week),
            amount = weekAmount,
            fraction = weekFraction,
            partial = weekPartial,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        val (monthAmount, monthFraction, monthPartial) = performance.month.toRowArgs()
        PeriodRow(
            label = stringResource(R.string.period_month),
            amount = monthAmount,
            fraction = monthFraction,
            partial = monthPartial,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/**
 * Under den här andelen oklassificerat värde sägs ingenting: en blandning som vilar på 95 % av
 * portföljen är i praktiken exakt, och en notis om varje blandfond hade blivit brus.
 */
private const val UNCLASSIFIED_NOTICE_THRESHOLD = 0.10

/**
 * Portföljens **tidsviktade** avkastning i procent över tid (HEM-9, issue #96/#116) med
 * indexjämförelsen som skuggportfölj (HEM-10) — samma delade [FundLineChart] som Fonddetalj
 * (regel 4), i procentläge ([ChartValueAxis.PROCENT]) så y-axeln alltid visar nollinjen och
 * serierna inte indexeras.
 *
 * Kurvan svarar på "hur gick fonderna", totalkortet på "hur mycket har jag tjänat på insatt
 * kapital". De två talen är därför inte samma sedan issue #116, och texten under diagrammet säger
 * det rakt ut i stället för att låta läsaren upptäcka det. Köpdagarna markeras fortfarande — de
 * flyttar inte längre kurvan, men de är det som förklarar varför de två siffrorna skiljer sig.
 *
 * Tre olika "det går inte"-lägen hålls isär i stället för att bakas ihop till ett tomt diagram:
 * ingen kurva alls (för lite historik), en kurva utan jämförelse (referensfonden inte hämtad än)
 * och en kurva räknad utan något innehav en viss dag (HEM-2:s delvis osäker).
 */
@Composable
private fun ReturnChartCard(state: HemUiState) {
    AppCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        CardTitleRow(
            title = stringResource(R.string.hem_return_chart_title),
            working = state.returnChartWorking,
        )
        if (state.returnSeries.isEmpty) {
            Text(
                stringResource(R.string.hem_return_chart_insufficient),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@AppCard
        }
        val benchmark = state.benchmarkSeries
        FundLineChart(
            points = state.returnSeries.points,
            purchaseEpochDays = state.purchaseEpochDays,
            primaryLabel = stringResource(R.string.hem_return_chart_series_portfolio),
            comparisonSeries = benchmark?.let { listOf(ChartSeries(it.fundName, it.points)) }.orEmpty(),
            valueAxis = ChartValueAxis.PROCENT,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            stringResource(R.string.hem_return_chart_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            if (benchmark != null) {
                stringResource(R.string.format_hem_return_chart_benchmark_explain, benchmark.fundName)
            } else {
                stringResource(R.string.hem_return_chart_benchmark_missing)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        // Vikterna vilar bara på det värde som gick att klassificera som aktier eller
        // räntor. Är resten stor är blandningen en grov approximation, och det ska synas
        // (HEM-10) — inte döljas bakom en exakt procentsats i teckenförklaringen.
        if (benchmark != null && benchmark.unclassifiedFraction >= UNCLASSIFIED_NOTICE_THRESHOLD) {
            Text(
                stringResource(
                    R.string.format_hem_return_chart_benchmark_unclassified,
                    MoneyFormat.percent(benchmark.unclassifiedFraction),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (state.returnSeries.partial) {
            Text(
                stringResource(R.string.hem_return_chart_partial),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** [amount]/[fraction]/[partial] — mappar [PortfolioPerformanceCalc.PortfolioPeriodResult] till [PeriodRow]s primitiver (regel 4). Saknas värde blir kr/% null och raden visar "Otillräcklig data". */
private data class PeriodRowArgs(val amount: Double?, val fraction: Double?, val partial: Boolean)

private fun PortfolioPerformanceCalc.PortfolioPeriodResult.toRowArgs(): PeriodRowArgs = when (this) {
    is PortfolioPerformanceCalc.PortfolioPeriodResult.Available ->
        PeriodRowArgs(amount, fraction, partial)
    PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory ->
        PeriodRowArgs(null, null, partial = false)
}

/** Summeringskort över gul-/rödflaggade fonder (issue #16, HEM-4). */
@Composable
private fun AnalysisSummaryCard(summary: AnalysisSummary, working: Boolean, onFundClick: (String) -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        CardTitleRow(title = stringResource(R.string.hem_analysis_title), working = working)
        if (summary.flagged.isEmpty()) {
            Text(
                stringResource(R.string.hem_analysis_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                summary.flagged.forEach { flagged ->
                    FlaggedFundRow(flagged = flagged, onClick = { onFundClick(flagged.fund.fundId) })
                }
            }
        }
    }
}

/**
 * Portföljens totala fondavgift per år (HEM-5, issue #60), samlade besparingspotential
 * (HEM-6, issue #61) och en rad per innehav (störst avgift först, redan sorterat av
 * [PortfolioFeeCalc.compute]) som öppnar fonden i Fonddetalj — annars var totalen inte
 * handlingsbar: användaren visste vad avgifterna kostade totalt, men inte vilken fond som
 * gjorde det (issue #63). Besparingen visas bara för innehav med ett känt, färskt
 * jämförelseresultat ([PortfolioFeeCalc.HoldingFee.annualSavingsKr] null annars) — ett
 * innehav som aldrig genomsökts ser ut som just det (via "N av M genomsökta"), aldrig som
 * "inget billigare hittades".
 */
@Composable
private fun FeeCard(summary: PortfolioFeeCalc.Result, working: Boolean, onFundClick: (String) -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        // Snurran sitter i en egen rubrikrad ovanför totalen, inte i `PeriodRow`n: där ligger
        // beloppet längst till höger, och en snurra i den änden hade trängt undan just det
        // talet kortet finns till för (UI-6).
        CardTitleRow(title = stringResource(R.string.hem_fee_title), working = working)
        PeriodRow(
            label = stringResource(R.string.hem_fee_total_label),
            amount = null,
            fraction = null,
            valueText = MoneyFormat.kr(summary.totalAnnualFeeKr),
        )
        Text(
            stringResource(R.string.hem_fee_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (summary.unknownFeeCount > 0) {
            Text(
                stringResource(R.string.format_hem_fee_unknown, summary.unknownFeeCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (summary.comparableCount > 0) {
            Text(
                stringResource(
                    R.string.format_hem_fee_savings,
                    MoneyFormat.kr(summary.totalAnnualSavingsKr),
                    summary.comparedCount,
                    summary.comparableCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (summary.byHolding.isNotEmpty()) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                summary.byHolding.forEach { holdingFee ->
                    FeeHoldingRow(holdingFee = holdingFee, onClick = { onFundClick(holdingFee.fund.fundId) })
                }
            }
        }
    }
}

@Composable
private fun FeeHoldingRow(holdingFee: PortfolioFeeCalc.HoldingFee, onClick: () -> Unit) {
    AppCard(
        onClick = onClick,
        variant = AppCardVariant.Filled,
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        PeriodRow(
            label = holdingFee.fund.name,
            amount = null,
            fraction = null,
            valueText = MoneyFormat.kr(holdingFee.annualFeeKr),
        )
        // Aldrig jämfört (ingen text) och jämfört-utan-träff ("Redan bland de billigaste",
        // samma text som ANA-9:s eget kort i Fonddetalj — regel 4) är skilda tillstånd,
        // både i data (PortfolioFeeCalc.HoldingFee.wasCompared) och här i UI:t. Annars ser
        // ett outforskat innehav ut som redan optimalt.
        val savings = holdingFee.annualSavingsKr
        when {
            savings != null && savings > 0 -> Text(
                stringResource(R.string.format_hem_fee_holding_savings, MoneyFormat.kr(savings)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            holdingFee.wasCompared -> Text(
                stringResource(R.string.fee_comparison_none_cheaper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun FlaggedFundRow(flagged: FlaggedHolding, onClick: () -> Unit) {
    AppCard(
        onClick = onClick,
        variant = AppCardVariant.Filled,
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(level = flagged.analysis.status!!, modifier = Modifier.padding(end = 12.dp))
            Column {
                Text(flagged.fund.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    statusTriggerMessages(flagged.analysis).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Målfördelning (SET-3) mot innehavens faktiska fördelning per risknivå (HEM-7, uppgraderad
 * från en enskild målnivå till en fördelning i issue #71) — ren läsvy, ingen åtgärdsknapp.
 * Det värdeviktade snittet ([RiskProfile.weightedTargetLevel] mot
 * [PortfolioRiskCalc.Result.weightedAverageRisk]) visas kvar som en sammanfattning överst
 * (samma [PeriodRow]-mönster som tidigare, regel 4), men huvudinnehållet är nu per-nivå-raderna
 * längre ned — en enda skalär kan inte skilja en 50/50-mix av nivå 1 och 6 från 100 % nivå
 * 3,5, se [PortfolioRiskCalc]s precisionsanmärkning. Per-nivå-raderna återanvänder
 * [se.partee71.fonder.ui.components.ExposureBar] (regel 4) — här är den rätt komponenten,
 * till skillnad från #68 där en enskild nivå var en position på en skala, inte en andel.
 *
 * Bytesplanen som konsumerar avvikelsen (HEM-8) visas sedan 0.61.0 på fliken Beslut (NAV-9).
 */
@Composable
private fun RiskCard(
    riskProfile: RiskProfile,
    portfolioRisk: PortfolioRiskCalc.Result,
    levelDeviations: List<PortfolioRiskCalc.LevelDeviation>,
    working: Boolean,
) {
    AppCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        CardTitleRow(title = stringResource(R.string.hem_risk_title), working = working)
        PeriodRow(
            label = stringResource(R.string.hem_risk_target_label),
            amount = null,
            fraction = null,
            valueText = riskProfile.weightedTargetLevel?.let { MoneyFormat.decimal(it) },
            modifier = Modifier.padding(top = 8.dp),
        )
        PeriodRow(
            label = stringResource(R.string.hem_risk_actual_label),
            amount = null,
            fraction = null,
            valueText = portfolioRisk.weightedAverageRisk?.let { MoneyFormat.decimal(it) },
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            stringResource(R.string.hem_risk_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (portfolioRisk.excludedCount > 0) {
            Text(
                stringResource(R.string.format_hem_risk_excluded, portfolioRisk.excludedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (levelDeviations.isNotEmpty()) {
            Text(
                stringResource(R.string.hem_risk_distribution_title),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            levelDeviations.sortedBy { it.level }.forEach { deviation ->
                Text(
                    stringResource(R.string.format_hem_risk_level, deviation.level),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                ExposureBar(label = stringResource(R.string.hem_risk_target_bar_label), fraction = deviation.targetFraction)
                ExposureBar(
                    label = stringResource(R.string.hem_risk_actual_bar_label),
                    fraction = deviation.actualFraction,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/**
 * Påminnelsen om att säkerhetskopiera (HEM-12) — undertexten säger hur många transaktioner som
 * tillkommit och när senaste kopian togs, eller att ingen finns. Knappen leder till
 * Inställningars säkerhetskopiering; själva kopian tas där (SET-6, SET-7).
 */
@Composable
private fun BackupReminderRow(reminder: BackupReminder, onOpenBackup: () -> Unit, modifier: Modifier) {
    val body = when {
        reminder.lastBackupEpochMillis == null -> stringResource(R.string.hem_backup_reminder_never)
        reminder.newTransactions > 0 -> pluralStringResource(
            R.plurals.format_hem_backup_reminder_new_transactions,
            reminder.newTransactions,
            reminder.newTransactions,
            BackupReminderCalc.formatDate(reminder.lastBackupEpochMillis),
        )
        else -> stringResource(
            R.string.format_hem_backup_reminder_last,
            BackupReminderCalc.formatDate(reminder.lastBackupEpochMillis),
        )
    }
    ReminderRow(
        title = stringResource(R.string.hem_backup_reminder_title),
        body = body,
        actionLabel = stringResource(R.string.hem_backup_reminder_action),
        onAction = onOpenBackup,
        // Samma ytterkant som korten; marginalen väljs av platsen (överst i tomt läge, under diagrammet annars).
        modifier = modifier,
    )
}

/**
 * "Att besluta" (HEM-13): antalet öppna bytesförslag och pågående byten i kopparens ton, och vägen
 * till fliken Beslut. Visas bara när något väntar — samma antal som flikradens märke, ur samma
 * källa. Undertexten nämner bara de slag som finns.
 */
@Composable
private fun DecisionsRow(decisions: PendingDecisions, onOpen: () -> Unit) {
    val suggestions = decisions.openSuggestionCount
    val watches = decisions.openWatches.size
    val body = listOfNotNull(
        suggestions.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.format_hem_decisions_suggestions, it, it) },
        watches.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.format_hem_decisions_watches, it, it) },
    ).joinToString(" · ")
    ReminderRow(
        title = stringResource(R.string.hem_decisions_title),
        body = body,
        actionLabel = stringResource(R.string.hem_decisions_action),
        onAction = onOpen,
        count = decisions.count,
        tone = ReminderRowTone.Accent,
        chevron = true,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}
