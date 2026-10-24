package se.partee71.fonder.ui.transaktioner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.RealizedSale
import se.partee71.fonder.domain.usecase.TaxCalc
import se.partee71.fonder.ui.components.ApproxTaxRow
import se.partee71.fonder.ui.components.ChoiceChipRow
import se.partee71.fonder.ui.components.PeriodRow
import se.partee71.fonder.ui.theme.ListFrameCards
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.fonder.ui.theme.ReturnColors
import se.partee71.ui.common.ListUiState
import se.partee71.ui.common.toListUiState
import se.partee71.ui.components.AppButton
import se.partee71.ui.components.AppCard
import se.partee71.ui.components.AppCardStyle
import se.partee71.ui.components.ButtonVariant
import se.partee71.ui.components.ConfirmDialog
import se.partee71.ui.components.DateField
import se.partee71.ui.components.EmptyAction
import se.partee71.ui.components.EmptyContent
import se.partee71.ui.components.EntityListScreen
import se.partee71.ui.components.ItemRow
import se.partee71.ui.components.ItemRowText
import se.partee71.ui.components.R as UiR
import se.partee71.ui.theme.Spacing

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * Fliken Händelser (NAV-1, NAV-5) på android-ui:s listram `EntityListScreen`: stor rubrik som fälls
 * ihop, filtret Alla/Köp/Sälj (TRX-3) fast under rubriken i alla lägen, årets realiserade resultat
 * (TRX-4) överst i listan och alla transaktioner där sälj visar sitt realiserade resultat
 * (SLD-1…SLD-5). Laddning, tomt läge med "Ny transaktion" och "Importera avräkningsnota" och läsfel
 * med "Försök igen" enligt NFR-6. Ingen lägg till-knapp i ramen – plusmenyn står i skalet (NAV-8).
 */
@Composable
fun TransaktionerScreen(
    onOpenSwitchWatch: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onNewTransaction: () -> Unit = {},
    onImportOrders: () -> Unit = {},
    viewModel: TransaktionerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // En nystartad bevakning öppnas direkt (SLD-5) — samma skäl som på Beslut: den är tom tills
    // kandidaterna hämtats, och att bli kvar här hade sett ut som att knappen inte gjorde något.
    val openSwitchWatch by rememberUpdatedState(onOpenSwitchWatch)
    LaunchedEffect(state.startedSwitchWatchId) {
        state.startedSwitchWatchId?.let { watchId ->
            openSwitchWatch(watchId)
            viewModel.onEvent(TransaktionerEvent.SwitchWatchOpened)
        }
    }

    TransaktionerContent(
        state = state,
        onFilterSelect = { viewModel.onEvent(TransaktionerEvent.FilterSelected(it)) },
        onDeleteTransaction = { viewModel.onEvent(TransaktionerEvent.DeleteTransaction(it)) },
        onStartSwitchWatch = { viewModel.onEvent(TransaktionerEvent.StartSwitchWatch(it)) },
        onRetry = { viewModel.onEvent(TransaktionerEvent.Retry) },
        onCorrectDate = { id, date -> viewModel.onEvent(TransaktionerEvent.CorrectDate(id, date)) },
        onNewTransaction = onNewTransaction,
        onImportOrders = onImportOrders,
        modifier = modifier
    )
}

/** Tillståndsdriven, testbar del av [TransaktionerScreen] — inget ViewModel/Hilt-beroende. */
@Composable
fun TransaktionerContent(
    state: TransaktionerUiState,
    modifier: Modifier = Modifier,
    onFilterSelect: (HandelseFilter) -> Unit = {},
    onDeleteTransaction: (Long) -> Unit = {},
    onStartSwitchWatch: (TransaktionRad) -> Unit = {},
    onRetry: () -> Unit = {},
    onCorrectDate: (Long, LocalDate) -> Unit = { _, _ -> },
    onNewTransaction: () -> Unit = {},
    onImportOrders: () -> Unit = {}
) {
    // Id:t, inte hela raden: rememberSaveable kräver ett sparbart värde, och raden slås ändå
    // upp ur tillståndet. Utan det försvann bekräftelsedialogen tyst vid rotation (issue #78).
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val pendingDelete = pendingDeleteId?.let { id -> state.allRows.firstOrNull { it.transaction.id == id } }

    ListFrameCards { cardStyle ->
        EntityListScreen(
            title = stringResource(R.string.nav_handelser),
            state = state.shownList(),
            empty = emptyContent(state, onNewTransaction, onImportOrders),
            add = null,
            key = { it.transaction.id },
            modifier = modifier,
            onRetry = onRetry,
            filter = {
                ChoiceChipRow(
                    options = HandelseFilter.entries,
                    selected = state.filter,
                    optionLabel = { stringResource(it.labelRes()) },
                    onSelect = onFilterSelect
                )
            },
            header = if (state.showRealizedCard) {
                { RealizedCard(state, cardStyle) }
            } else {
                null
            }
        ) { row ->
            HandelseRow(
                row = row,
                onLongPress = { pendingDeleteId = row.transaction.id },
                onStartSwitchWatch = onStartSwitchWatch,
                onCorrectDate = onCorrectDate
            )
        }
    }

    pendingDelete?.let { row ->
        ConfirmDialog(
            title = stringResource(R.string.transaktion_delete_title),
            message = stringResource(R.string.format_transaktion_delete_confirm, row.fundName),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                onDeleteTransaction(row.transaction.id)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null },
            destructive = true
        )
    }
}

/**
 * Listan ramen visar: laddning och fel som de är, annars raderna i det valda filtret (TRX-3) – ett
 * filter utan träffar blir ramens tomma läge, med filtret kvar ovanför.
 */
private fun TransaktionerUiState.shownList(): ListUiState<TransaktionRad> = when (list) {
    is ListUiState.Content -> rows.toListUiState()
    else -> list
}

/**
 * Det tomma läget: inga transaktioner alls ger vägarna framåt, samma som plusmenyns (F1, TRX-7) – ett
 * tomt filter (inga köp, inga sälj, SLD-1) bara sitt budskap.
 */
@Composable
private fun emptyContent(state: TransaktionerUiState, onNewTransaction: () -> Unit, onImportOrders: () -> Unit): EmptyContent = when {
    state.isEmpty -> EmptyContent(
        icon = R.drawable.ic_receipt,
        title = stringResource(R.string.transaktioner_empty_title),
        message = stringResource(R.string.transaktioner_empty_body),
        actions = firstTransactionActions(onNewTransaction, onImportOrders)
    )

    state.filter == HandelseFilter.SALJ -> EmptyContent(
        icon = R.drawable.ic_receipt,
        title = stringResource(R.string.sold_funds_empty_title),
        message = stringResource(R.string.sold_funds_empty_body)
    )

    else -> EmptyContent(
        icon = R.drawable.ic_receipt,
        title = stringResource(R.string.handelser_buy_empty_title),
        message = stringResource(R.string.handelser_buy_empty_body)
    )
}

/**
 * Vägarna till den första transaktionen i ett tomt läge (F1): "Ny transaktion" och "Importera
 * avräkningsnota", samma val och texter som plusmenyn (NAV-8) – Händelser och Innehav (TRX-7, POR-10).
 */
@Composable
fun firstTransactionActions(onNewTransaction: () -> Unit, onImportOrders: () -> Unit): List<EmptyAction> = listOf(
    EmptyAction(stringResource(R.string.transaktioner_add), onNewTransaction, UiR.drawable.ic_add),
    EmptyAction(stringResource(R.string.fab_import_orders), onImportOrders, R.drawable.ic_upload, ButtonVariant.Secondary)
)

private fun HandelseFilter.labelRes(): Int = when (this) {
    HandelseFilter.ALLA -> R.string.handelser_filter_all
    HandelseFilter.KOP -> R.string.transaktion_kop
    HandelseFilter.SALJ -> R.string.transaktion_salj
}

/**
 * "Realiserat resultat i år" (TRX-4) och, i säljfiltret, totalen sedan start (SLD-3). Ett osäkert
 * resultat (SLD-2) sägs ut i text under raderna, aldrig bara med färg. I depå/AF (TRX-8) delas
 * årets resultat upp i vinster och förluster, och den ungefärliga skatten – på vinsten efter
 * kvittning och på schablonintäkten – står under, dämpad och med "ca", följd av en förklaring.
 */
@Composable
private fun RealizedCard(state: TransaktionerUiState, cardStyle: AppCardStyle, modifier: Modifier = Modifier) {
    val showTotal = state.filter == HandelseFilter.SALJ
    AppCard(modifier = modifier.fillMaxWidth(), style = cardStyle) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (state.showRealizedRow) {
                PeriodRow(
                    label = stringResource(R.string.handelser_realized_year),
                    amount = state.yearRealized.realizedGain,
                    fraction = state.yearRealized.realizedGainFraction,
                    supportingLabel = if (state.showRealizedTax) {
                        stringResource(
                            R.string.format_handelser_realized_split,
                            MoneyFormat.krWhole(state.yearRealizedTax.gains),
                            MoneyFormat.krWhole(state.yearRealizedTax.losses)
                        )
                    } else {
                        null
                    }
                )
            }
            if (showTotal && state.showRealizedRow) {
                PeriodRow(
                    label = stringResource(R.string.sold_funds_total_title),
                    amount = state.totalRealized.realizedGain,
                    fraction = state.totalRealized.realizedGainFraction
                )
            }
            // Osäkert (SLD-2) hör till resultatet, så det står direkt under resultatraderna — före skatten.
            if (state.yearRealized.uncertain || (showTotal && state.totalRealized.uncertain)) {
                Text(
                    stringResource(R.string.handelser_realized_uncertain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (state.showRealizedTax) {
                ApproxTaxRow(
                    label = stringResource(R.string.handelser_tax_realized),
                    tax = state.yearRealizedTax.tax,
                    basis = stringResource(R.string.format_handelser_tax_realized_basis, MoneyFormat.rate(TaxCalc.CAPITAL_INCOME_TAX))
                )
            }
            state.standardIncomeTax?.let { standardIncome ->
                ApproxTaxRow(
                    label = stringResource(R.string.handelser_tax_standard_income),
                    tax = standardIncome.tax,
                    basis = stringResource(
                        R.string.format_handelser_tax_standard_income_basis,
                        MoneyFormat.rate(TaxCalc.FUND_STANDARD_INCOME_RATE),
                        MoneyFormat.krWhole(standardIncome.valueAtYearStart),
                        MoneyFormat.rate(TaxCalc.CAPITAL_INCOME_TAX)
                    )
                )
            }
            if (state.depa && (state.showRealizedTax || state.standardIncomeTax != null)) {
                ItemRowText(title = null, subtitle = stringResource(R.string.handelser_tax_explain))
            }
        }
    }
}

/**
 * En transaktion (TRX-1): datum, fondnamn, typ och belopp med tecken — in (sälj) eller ut (köp) — i
 * listramens rad. Ett sälj visar sitt realiserade resultat i undertexten, osäkert markerat i text
 * (SLD-2), ett uppskattat datum likaså (TRX-9). Tryck fäller ut detaljerna (SLD-4), långtryck frågar
 * om radering (TRX-2).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HandelseRow(
    row: TransaktionRad,
    onLongPress: () -> Unit,
    onStartSwitchWatch: (TransaktionRad) -> Unit,
    onCorrectDate: (Long, LocalDate) -> Unit
) {
    val tx = row.transaction
    var expanded by rememberSaveable { mutableStateOf(false) }
    val stateText = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    Column {
        ItemRow(
            title = row.fundName,
            subtitle = subtitle(tx.type, row.sale, tx.dateEstimated),
            modifier = Modifier
                .combinedClickable(
                    onClickLabel = stringResource(if (expanded) R.string.sold_fund_collapse else R.string.sold_fund_expand),
                    onClick = { expanded = !expanded },
                    onLongClickLabel = stringResource(R.string.delete),
                    onLongClick = onLongPress
                )
                // Raden saknar pil (mockupen); läget läses i stället upp av TalkBack.
                .semantics { stateDescription = stateText },
            leading = {
                Text(
                    LocalDate.ofEpochDay(tx.epochDay).format(dateFormatter),
                    style = MonoAmountStyle.merge(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailing = {
                val isSell = tx.type == TransactionType.SALJ
                Text(
                    MoneyFormat.krSigned(if (isSell) tx.amount else -tx.amount),
                    style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                    color = if (isSell) ReturnColors.gain else MaterialTheme.colorScheme.onSurface
                )
            }
        )
        AnimatedVisibility(visible = expanded) {
            HandelseDetails(row = row, onStartSwitchWatch = onStartSwitchWatch, onCorrectDate = onCorrectDate)
        }
    }
}

/**
 * "Köp", eller "Sälj · vinst +2 140,00 kr" med "· osäkert" när köphistoriken inte räcker (SLD-2), och
 * "· uppskattat datum" när importen gissade dagen (TRX-9).
 */
@Composable
private fun subtitle(type: TransactionType, sale: RealizedSale?, dateEstimated: Boolean): String {
    val text = saleSubtitle(type, sale)
    return if (dateEstimated) stringResource(R.string.format_handelse_estimated_date, text) else text
}

@Composable
private fun saleSubtitle(type: TransactionType, sale: RealizedSale?): String {
    val typeLabel = stringResource(type.labelRes())
    if (sale == null) return typeLabel
    val gain = sale.realizedGain
    val text = stringResource(
        if (gain >= 0.0) R.string.format_handelse_sell_gain else R.string.format_handelse_sell_loss,
        typeLabel,
        MoneyFormat.krSigned(gain)
    )
    return if (sale.uncoveredShares > 0.0) stringResource(R.string.format_handelse_uncertain, text) else text
}

/**
 * Utfällda detaljer (SLD-4): andelar och kurs, för ett sälj även belopp, avgift,
 * anskaffningsvärde, resultatet i kr + %, en ev. osäkerhetsvarning (SLD-2) och "Bevaka
 * alternativ" (SLD-5) — knappen ligger här så att den hopfällda raden förblir namn och resultat. Ett
 * uppskattat datum förklaras och kan rättas här (TRX-9).
 */
@Composable
private fun HandelseDetails(row: TransaktionRad, onStartSwitchWatch: (TransaktionRad) -> Unit, onCorrectDate: (Long, LocalDate) -> Unit) {
    val tx = row.transaction
    val sale = row.sale
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.m, end = Spacing.m, bottom = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text(
            stringResource(R.string.format_transaktion_rad, tx.shares.toString(), MoneyFormat.kr(tx.pricePerShare)),
            style = MonoAmountStyle.merge(MaterialTheme.typography.bodySmall),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (sale == null) {
            if (tx.fee > 0.0) {
                Text(
                    stringResource(R.string.format_transaktion_fee, MoneyFormat.kr(tx.fee)),
                    style = MonoAmountStyle.merge(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            SaleDetails(sale)
        }
        if (tx.dateEstimated) {
            Text(
                stringResource(R.string.handelse_estimated_date_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DateField(
                label = stringResource(R.string.handelse_correct_date),
                date = LocalDate.ofEpochDay(tx.epochDay).toKotlinLocalDate(),
                onDateChange = { onCorrectDate(tx.id, it.toJavaLocalDate()) }
            )
        }
        if (row.canStartSwitchWatch) {
            AppButton(
                text = stringResource(R.string.switch_watch_start),
                onClick = { onStartSwitchWatch(row) },
                variant = ButtonVariant.Text
            )
        }
    }
}

/** Säljets belopp, avgift och anskaffningsvärde, resultatet i kr + % och en ev. osäkerhetsvarning (SLD-2, SLD-4). */
@Composable
private fun SaleDetails(sale: RealizedSale) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            stringResource(
                R.string.format_sold_fund_detail,
                MoneyFormat.kr(sale.proceeds),
                MoneyFormat.kr(sale.fee),
                MoneyFormat.kr(sale.costBasis)
            ),
            style = MonoAmountStyle.merge(MaterialTheme.typography.bodySmall),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PeriodRow(
            label = stringResource(R.string.handelser_realized_row),
            amount = sale.realizedGain,
            fraction = sale.realizedGainFraction
        )
        if (sale.uncoveredShares > 0.0) {
            Text(
                stringResource(R.string.sold_fund_uncovered_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
