package se.partee71.fonder.ui.imports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.ImportedOrderTransaction
import se.partee71.fonder.domain.usecase.SwitchOption
import se.partee71.fonder.ui.components.CheckRow
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.ImportCompleteDialog
import se.partee71.fonder.ui.components.LoadingDialog
import se.partee71.fonder.ui.components.SelectField
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.fonder.ui.transaktioner.SwitchDecisionField
import se.partee71.fonder.ui.transaktioner.labelRes
import se.partee71.ui.components.AppButton
import se.partee71.ui.components.AppCard
import se.partee71.ui.components.AppTextField
import se.partee71.ui.components.DateField
import se.partee71.ui.theme.Spacing

/** Importera exakta transaktioner från en eller flera Handelsbanken-avräkningsnotor (PDF), issue #8-uppföljning. */
@Composable
fun ImportOrdersScreen(
    onDone: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ImportOrdersViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        viewModel.onFilesSelected(uris)
    }

    if (state.imported) {
        ImportCompleteDialog(
            importedCount = state.rows.count { it.readyToImport },
            onDismiss = onDone,
            notice = state.linkFailedCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.format_import_orders_link_failed, it, it) }
        )
    }

    val error = state.error
    when {
        state.imported -> Unit

        !state.filesSelected -> Column(
            modifier = modifier.fillMaxSize().padding(Spacing.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.import_orders_pick_files_body), style = MaterialTheme.typography.bodyMedium)
            AppButton(
                text = stringResource(R.string.import_orders_pick_files_button),
                onClick = { filePicker.launch(arrayOf("application/pdf")) },
                modifier = Modifier.padding(top = Spacing.l)
            )
        }

        error != null -> EmptyState(
            error = error,
            title = stringResource(R.string.import_orders_title),
            modifier = modifier
        )

        else -> ImportOrdersReview(
            state = state,
            onFundOverride = viewModel::onFundOverride,
            onIncludedChange = viewModel::onIncludedChange,
            onDateChange = viewModel::onDateChange,
            onSharesTextChange = viewModel::onSharesTextChange,
            onPriceTextChange = viewModel::onPriceTextChange,
            onSwitchSelected = viewModel::onSwitchSelected,
            onImport = viewModel::import,
            modifier = modifier
        )
    }

    // Läsning/tolkning av flera PDF-filer kan ta en stund — overlay-modal precis som
    // Excel-importet (IMP-4), ingen tom eller ointeraktiv vy under tiden.
    if (state.loading) {
        LoadingDialog(
            title = stringResource(R.string.import_orders_loading_title),
            body = stringResource(R.string.import_orders_loading_body)
        )
    }
}

/**
 * Granskningen av de tolkade avräkningsnotorna (IMP-6) — tillståndsdriven och testbar, utan
 * ViewModel. Raderna står sälj före köp, var och en med bytesväljaren (BYT-2).
 */
@Composable
fun ImportOrdersReview(
    state: ImportOrdersUiState,
    onFundOverride: (ImportedOrderTransaction, Fund?) -> Unit,
    onIncludedChange: (ImportedOrderTransaction, Boolean) -> Unit,
    onDateChange: (ImportedOrderTransaction, java.time.LocalDate) -> Unit,
    onSharesTextChange: (ImportedOrderTransaction, String) -> Unit,
    onPriceTextChange: (ImportedOrderTransaction, String) -> Unit,
    onSwitchSelected: (ImportedOrderTransaction, SwitchOption) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (state.unparsedFileNames.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.format_import_orders_unparsed,
                    state.unparsedFileNames.size,
                    state.unparsedFileNames.joinToString(", ")
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(Spacing.l)
            )
        }
        if (state.hasSwitchQuestions) {
            Text(
                stringResource(R.string.import_orders_switch_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(
                state.rows,
                key = { "${it.transaction.isin}-${it.transaction.epochDay}-${it.transaction.shares}-${it.transaction.sourceFileName}" }
            ) { rowState ->
                ImportOrderRowCard(
                    rowState = rowState,
                    catalogFunds = state.catalogFunds,
                    onFundOverride = { fund -> onFundOverride(rowState.transaction, fund) },
                    onIncludedChange = { included -> onIncludedChange(rowState.transaction, included) },
                    onDateChange = { date -> onDateChange(rowState.transaction, date) },
                    onSharesTextChange = { text -> onSharesTextChange(rowState.transaction, text) },
                    onPriceTextChange = { text -> onPriceTextChange(rowState.transaction, text) },
                    onSwitchSelected = { option -> onSwitchSelected(rowState.transaction, option) },
                    switchSaleRowSkipped = state.switchSaleRowSkipped(rowState)
                )
            }
        }
        AppButton(
            text = stringResource(R.string.import_orders_commit_button, state.rows.count { it.readyToImport }),
            onClick = onImport,
            enabled = state.canImport,
            modifier = Modifier.fillMaxWidth().padding(Spacing.l)
        )
    }
}

@Composable
private fun ImportOrderRowCard(
    rowState: ImportOrderRowUiState,
    catalogFunds: List<Fund>,
    onFundOverride: (Fund?) -> Unit,
    onIncludedChange: (Boolean) -> Unit,
    onDateChange: (java.time.LocalDate) -> Unit,
    onSharesTextChange: (String) -> Unit,
    onPriceTextChange: (String) -> Unit,
    onSwitchSelected: (SwitchOption) -> Unit,
    switchSaleRowSkipped: Boolean
) {
    val tx = rowState.transaction
    AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = 6.dp)) {
        CheckRow(
            checked = rowState.included,
            onCheckedChange = onIncludedChange,
            label = tx.fundName,
            supportingText = stringResource(R.string.format_import_order_isin, tx.isin, tx.sourceFileName),
            labelStyle = MaterialTheme.typography.titleSmall
        )
        Text(
            stringResource(tx.type.labelRes()),
            style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
            modifier = Modifier.padding(top = Spacing.s)
        )

        SelectField(
            label = stringResource(R.string.import_match_label),
            options = catalogFunds,
            selected = rowState.matchedFund,
            optionLabel = { it.name },
            onSelect = onFundOverride,
            placeholder = stringResource(R.string.import_match_none),
            modifier = Modifier.padding(top = Spacing.s)
        )
        if (rowState.matchConfidence != null && rowState.matchConfidence < 0.75) {
            Text(
                stringResource(R.string.import_match_uncertain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (rowState.priceFetchFailed) {
            Text(
                stringResource(R.string.import_price_fetch_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        DateField(
            label = stringResource(R.string.import_date_label),
            date = rowState.date.toKotlinLocalDate(),
            onDateChange = { onDateChange(it.toJavaLocalDate()) },
            modifier = Modifier.padding(top = Spacing.s)
        )
        AppTextField(
            value = rowState.sharesText,
            onValueChange = onSharesTextChange,
            label = stringResource(R.string.import_order_shares_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s)
        )
        AppTextField(
            value = rowState.priceText,
            onValueChange = onPriceTextChange,
            label = stringResource(R.string.import_order_price_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s)
        )
        // Bytesväljaren (BYT-2) — samma fält och texter som i transaktionsformuläret.
        rowState.switchField?.let { field ->
            SwitchDecisionField(
                field = field,
                fundName = rowState.matchedFund?.name ?: tx.fundName,
                onSelect = onSwitchSelected,
                modifier = Modifier.padding(top = Spacing.s)
            )
        }
        if (switchSaleRowSkipped) {
            Text(
                stringResource(R.string.import_orders_switch_sale_row_skipped),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
