package se.partee71.fonder.ui.portfolj

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.PortfolioExposureCalc
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import se.partee71.fonder.ui.components.CardTitleRow
import se.partee71.fonder.ui.components.CenteredState
import se.partee71.fonder.ui.components.ExposureBar
import se.partee71.fonder.ui.components.PeriodRow
import se.partee71.fonder.ui.components.ProfitTakeBadge
import se.partee71.fonder.ui.components.PullToRefreshContainer
import se.partee71.fonder.ui.components.RiskBadge
import se.partee71.fonder.ui.components.StatusDot
import se.partee71.fonder.ui.components.TotalCard
import se.partee71.fonder.ui.components.ValueAsOfRow
import se.partee71.fonder.ui.components.WorkingIndicator
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.fonder.ui.theme.ReturnColors
import se.partee71.fonder.ui.transaktioner.firstTransactionActions
import se.partee71.ui.components.AppCard
import se.partee71.ui.components.EmptyState
import se.partee71.ui.components.LoadErrorState
import se.partee71.ui.theme.Spacing

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** Adresserar innehavslistan för `performScrollToIndex` i tester (UI-5, issue #66) — se [PortfoljContent]. */
const val PORTFOLJ_LIST_TEST_TAG = "portfolj_list"

@Composable
fun PortfoljScreen(
    onFundClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNewTransaction: () -> Unit = {},
    onImportOrders: () -> Unit = {},
    viewModel: PortfoljViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PortfoljContent(
        state = state,
        onFundClick = onFundClick,
        onRefresh = { viewModel.onEvent(PortfoljEvent.Refresh) },
        onRetry = { viewModel.onEvent(PortfoljEvent.Retry) },
        onNewTransaction = onNewTransaction,
        onImportOrders = onImportOrders,
        modifier = modifier
    )
}

/**
 * Tillståndsdriven, testbar del av [PortfoljScreen] — inget ViewModel/Hilt-beroende (issue #14). Utan
 * innehav visar det tomma läget vägarna framåt, "Ny transaktion" och "Importera avräkningsnota" (POR-10);
 * ett läsfel har "Försök igen" ([onRetry], NFR-6).
 */
@Composable
fun PortfoljContent(
    state: PortfoljUiState,
    onFundClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    onNewTransaction: () -> Unit = {},
    onImportOrders: () -> Unit = {}
) {
    val error = state.error
    when {
        state.isEmpty -> CenteredState(modifier) {
            EmptyState(
                icon = R.drawable.ic_holdings,
                title = stringResource(R.string.portfolj_empty_title),
                message = stringResource(R.string.portfolj_empty_body),
                actions = firstTransactionActions(onNewTransaction, onImportOrders)
            )
        }

        error != null -> CenteredState(modifier) { LoadErrorState(stringResource(R.string.error_load_title), error, onRetry) }

        // Både totalkortet och exponeringskortet ligger som `item {}` i samma `LazyColumn` som
        // innehavsraderna (inte i en fast `Column` ovanför) — annars äter de permanent
        // skärmhöjd och kan klippa bort innehav på en liten skärm, exakt buggklassen UI-5
        // beskriver (issue #63). `testTag` gör listan adresserbar för `performScrollToIndex`
        // i tester — till skillnad från Hem har innehavsraderna här riktiga lazy `items()`,
        // inte allihop under en enda icke-lazy `Column`, så `onNodeWithText(...).performScrollTo()`
        // hittar aldrig en rad som ännu inte komponerats (issue #66).
        // Dra ned uppdaterar (UI-11) — bara med innehav: utan dem finns ingen kurs att hämta.
        else -> PullToRefreshContainer(
            refreshing = state.pricesWorking || state.refreshingFundIds.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = modifier
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize().testTag(PORTFOLJ_LIST_TEST_TAG)) {
                item(contentType = "total") {
                    TotalCard(
                        totalValue = state.totalValue,
                        totalInvested = state.totalInvested,
                        gainLoss = state.totalGainLoss,
                        gainLossFraction = state.totalGainLossFraction,
                        navEpochDay = state.navEpochDay,
                        working = state.totalWorking,
                        modifier = Modifier.padding(Spacing.l)
                    )
                }
                item(contentType = "exposure") { ExposureCard(exposure = state.exposure, working = state.exposureWorking) }
                items(state.holdings, key = { it.fund.fundId }, contentType = { "holding" }) { holding ->
                    HoldingRow(
                        holding = holding,
                        performance = state.performance[holding.fund.fundId],
                        analysis = state.analysis[holding.fund.fundId],
                        riskLevel = state.riskLevels[holding.fund.fundId],
                        working = state.holdingWorking(holding.fund.fundId),
                        onClick = { onFundClick(holding.fund.fundId) }
                    )
                }
            }
        }
    }
}

/**
 * Exponeringskarta (POR-9, issue #66, risknivådimensionen tillagd i issue #71) — andel av
 * portföljens värde per fondtyp, region, risknivå och index/aktivt förvaltat, ren inventering
 * utan köp- eller rebalanseringstext. Risknivå är, till skillnad från övriga dimensioner, redan
 * sorterad stigande på nivå av [PortfolioExposureCalc] (den ordnade skalans egen ordning, inte
 * fallande på värde). Okänd-hinkar visas sist i sin sektion, med en dämpad stapelfärg
 * (`outline`) som skiljer dem visuellt från de riktiga kategorierna (regel 4, [ExposureBar]).
 */
@Composable
private fun ExposureCard(exposure: PortfolioExposureCalc.Result, working: Boolean) {
    AppCard(modifier = Modifier.fillMaxWidth().padding(Spacing.l)) {
        CardTitleRow(title = stringResource(R.string.portfolj_exposure_title), working = working)

        ExposureDimensionSection(
            title = stringResource(R.string.portfolj_exposure_type_title),
            dimension = exposure.byType,
            unknownLabel = stringResource(R.string.portfolj_exposure_unknown_type)
        )
        ExposureDimensionSection(
            title = stringResource(R.string.portfolj_exposure_region_title),
            dimension = exposure.byRegion,
            unknownLabel = stringResource(R.string.portfolj_exposure_unknown_region),
            explain = stringResource(R.string.portfolj_exposure_region_explain)
        )
        ExposureDimensionSection(
            title = stringResource(R.string.portfolj_exposure_risk_title),
            dimension = exposure.byRiskLevel,
            unknownLabel = stringResource(R.string.portfolj_exposure_unknown_risk)
        )

        Text(
            stringResource(R.string.portfolj_exposure_index_title),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = Spacing.m)
        )
        ExposureBar(label = stringResource(R.string.portfolj_exposure_index_label), fraction = exposure.indexStatus.indexFraction)
        ExposureBar(label = stringResource(R.string.portfolj_exposure_active_label), fraction = exposure.indexStatus.activeFraction)

        if (exposure.excludedCount > 0) {
            Text(
                stringResource(R.string.format_portfolj_exposure_excluded, exposure.excludedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.s)
            )
        }
    }
}

@Composable
private fun ColumnScope.ExposureDimensionSection(
    title: String,
    dimension: PortfolioExposureCalc.Dimension,
    unknownLabel: String,
    explain: String? = null
) {
    Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.m))
    if (explain != null) {
        Text(explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    dimension.buckets.forEach { bucket ->
        ExposureBar(label = bucket.label, fraction = bucket.fraction)
    }
    if (dimension.unknownCount > 0) {
        ExposureBar(label = unknownLabel, fraction = dimension.unknownFraction, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun HoldingRow(
    holding: Holding,
    performance: PortfolioPerformanceCalc.HoldingPerformance?,
    analysis: FundAnalysisCalc.Analysis?,
    riskLevel: Int?,
    working: Boolean,
    onClick: () -> Unit
) {
    AppCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                holding.fund.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f, fill = false)
            )
            // Säljsignal-status (ANA-3) bredvid fondnamnet — samma StatusDot som Fonddetalj/
            // Hem (regel 4), utan att behöva öppna Fonddetalj för att se den (POR-8, issue #26).
            analysis?.status?.let { status -> StatusDot(status, modifier = Modifier.padding(start = Spacing.s)) }
            // Risknivån (UI-10, issue #85) bredvid signalen — samma delade RiskBadge som
            // Fonddetalj och fondsök (regel 4), så en fonds risk ser likadan ut överallt.
            RiskBadge(level = riskLevel, modifier = Modifier.padding(start = Spacing.s))
            // Väntesnurra för just den här fonden (NAV-6): en nyss tillagd fond visar 0 kr
            // tills kursen landat, och utan snurran ser det ut som ett värde i stället för
            // som ett svar på väg.
            WorkingIndicator(
                working = working,
                contentDescription = stringResource(R.string.format_card_working, holding.fund.name),
                modifier = Modifier.padding(start = Spacing.s)
            )
        }
        // Vinstsignalen (S4, ANA-8) är ingen risk och visas därför separat från StatusDot
        // ovan, inte som en del av samma rad/trafikljus (issue #26).
        analysis?.profitTake?.takeIf { it.triggered }?.let { profitTake ->
            ProfitTakeBadge(gainFraction = profitTake.gainFraction, modifier = Modifier.padding(top = Spacing.xs))
        }
        FirstPurchaseRow(holding = holding, modifier = Modifier.padding(top = 2.dp))
        val value = holding.currentValue
        val fraction = holding.gainLossFraction
        if (value != null) {
            Text(
                MoneyFormat.kr(value),
                style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium)
            )
            if (fraction != null) {
                Text(
                    "${MoneyFormat.percentSigned(fraction)} · ${MoneyFormat.kr(holding.gainLoss ?: 0.0)}",
                    style = MonoAmountStyle.merge(MaterialTheme.typography.bodySmall),
                    color = ReturnColors.forAmount(holding.gainLoss ?: 0.0)
                )
            }
            ValueAsOfRow(navEpochDay = holding.navEpochDay, modifier = Modifier.padding(top = 2.dp))
            val (dayAmount, dayFraction) = performance?.day.toRowArgs()
            PeriodRow(
                label = stringResource(R.string.period_day),
                amount = dayAmount,
                fraction = dayFraction,
                modifier = Modifier.padding(top = Spacing.s)
            )
            val (weekAmount, weekFraction) = performance?.week.toRowArgs()
            PeriodRow(
                label = stringResource(R.string.period_week),
                amount = weekAmount,
                fraction = weekFraction
            )
            val (monthAmount, monthFraction) = performance?.month.toRowArgs()
            PeriodRow(
                label = stringResource(R.string.period_month),
                amount = monthAmount,
                fraction = monthFraction
            )
        } else {
            Text(
                MoneyFormat.kr(holding.netInvested),
                style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.portfolj_price_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Första köp-datum + kvarvarande inköpsvärde (FIFO) för innehavet (POR-6, issue #18). */
@Composable
private fun FirstPurchaseRow(holding: Holding, modifier: Modifier = Modifier) {
    val firstPurchaseEpochDay = holding.firstPurchaseEpochDay ?: return
    Text(
        stringResource(
            R.string.format_holding_first_purchase,
            LocalDate.ofEpochDay(firstPurchaseEpochDay).format(dateFormatter),
            MoneyFormat.kr(holding.netInvested)
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** Mappar [PortfolioPerformanceCalc.PeriodResult] till [PeriodRow]s primitiva parametrar (kr/%) — komponenten känner inte till domänmodeller (regel 4). Saknas värde (otillräcklig historik/ingen kurs) blir båda null och raden visar "Otillräcklig data". */
private fun PortfolioPerformanceCalc.PeriodResult?.toRowArgs(): Pair<Double?, Double?> = when (this) {
    is PortfolioPerformanceCalc.PeriodResult.Available -> amount to fraction
    PortfolioPerformanceCalc.PeriodResult.InsufficientHistory, null -> null to null
}
