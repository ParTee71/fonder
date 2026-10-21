package se.partee71.fonder.ui.imports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.components.ChoiceChipRow
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.ImportCompleteDialog
import se.partee71.fonder.ui.components.LoadingDialog
import se.partee71.fonder.ui.transaktioner.labelRes
import se.partee71.ui.components.AppButton
import se.partee71.ui.components.AppCard
import se.partee71.ui.components.AppIconButton
import se.partee71.ui.components.AppTextField
import se.partee71.ui.components.ButtonVariant
import se.partee71.ui.components.DateField
import se.partee71.ui.components.R as UiR
import se.partee71.ui.theme.Spacing

// Exporten är i praktiken inte en riktig zip-baserad .xlsx utan kalkylbladets råa XML
// (se HoldingsImportParser) — filväljaren behöver därför acceptera båda mime-typerna,
// beroende på vad enheten/webbläsaren råkar rapportera för den nedladdade filen.
private val IMPORT_MIME_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/xml",
    "application/xml"
)

/** Importera befintliga innehav från Handelsbankens "Innehav Fonder"-Excel-export (issue #8). */
@Composable
fun ImportHoldingsScreen(
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
    viewModel: ImportHoldingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.onEvent(ImportHoldingsEvent.FileSelected(uri))
    }

    if (state.imported) {
        ImportCompleteDialog(importedCount = state.rows.count { it.readyToImport }, onDismiss = onDone)
    }

    val error = state.error
    when {
        state.imported -> Unit

        !state.fileSelected -> Column(
            modifier = modifier.fillMaxSize().padding(Spacing.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.import_pick_file_body), style = MaterialTheme.typography.bodyMedium)
            AppButton(
                text = stringResource(R.string.import_pick_file),
                onClick = { filePicker.launch(IMPORT_MIME_TYPES) },
                modifier = Modifier.padding(top = Spacing.l)
            )
        }

        error != null -> EmptyState(
            error = error,
            title = stringResource(R.string.import_title),
            modifier = modifier
        )

        else -> Column(modifier = modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.rows, key = { it.row.isin + it.row.shares }) { rowState ->
                    ImportRowCard(
                        rowState = rowState,
                        catalogFunds = state.catalogFunds,
                        onFundOverride = { fund -> viewModel.onEvent(ImportHoldingsEvent.FundOverride(rowState.row, fund)) },
                        onIncludedChange = { included -> viewModel.onEvent(ImportHoldingsEvent.IncludedChange(rowState.row, included)) },
                        onOccasionDateChange = { index, date -> viewModel.onEvent(ImportHoldingsEvent.OccasionDateChange(rowState.row, index, date)) },
                        onOccasionSharesChange = { index, text -> viewModel.onEvent(ImportHoldingsEvent.OccasionSharesChange(rowState.row, index, text)) },
                        onOccasionTypeChange = { index, type -> viewModel.onEvent(ImportHoldingsEvent.OccasionTypeChange(rowState.row, index, type)) },
                        onAddOccasion = { viewModel.onEvent(ImportHoldingsEvent.AddOccasion(rowState.row)) },
                        onRemoveOccasion = { index -> viewModel.onEvent(ImportHoldingsEvent.RemoveOccasion(rowState.row, index)) }
                    )
                }
            }
            AppButton(
                text = stringResource(R.string.import_commit_button, state.rows.count { it.readyToImport }),
                onClick = { viewModel.onEvent(ImportHoldingsEvent.Import) },
                enabled = state.canImport,
                modifier = Modifier.fillMaxWidth().padding(Spacing.l)
            )
        }
    }

    // Fondmatchning + kursuppdatering (5 års historik per fond) tar en stund — en
    // overlay-modal gör väntan tydlig i stället för att visa en tom, ointeraktiv lista.
    if (state.loading) {
        LoadingDialog(
            title = stringResource(R.string.import_loading_title),
            body = stringResource(R.string.import_loading_body)
        )
    }
}

@Composable
private fun ImportRowCard(
    rowState: ImportRowUiState,
    catalogFunds: List<Fund>,
    onFundOverride: (Fund?) -> Unit,
    onIncludedChange: (Boolean) -> Unit,
    onOccasionDateChange: (Int, LocalDate) -> Unit,
    onOccasionSharesChange: (Int, String) -> Unit,
    onOccasionTypeChange: (Int, TransactionType) -> Unit,
    onAddOccasion: () -> Unit,
    onRemoveOccasion: (Int) -> Unit
) {
    val row = rowState.row
    AppCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = 6.dp)) {
        ImportRowHeader(
            included = rowState.included,
            onIncludedChange = onIncludedChange,
            fundName = row.fundName,
            supportingText = stringResource(R.string.format_import_row_isin, row.isin),
            detail = stringResource(R.string.format_transaktion_rad, row.shares.toString(), MoneyFormat.kr(row.averageCostPerShare)),
            catalogFunds = catalogFunds,
            matchedFund = rowState.matchedFund,
            matchConfidence = rowState.matchConfidence,
            priceFetchFailed = rowState.priceFetchFailed,
            onFundOverride = onFundOverride
        )

        Text(
            stringResource(R.string.import_occasions_label),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = Spacing.m)
        )
        rowState.occasions.forEachIndexed { index, occasion ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.s)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ChoiceChipRow(
                        options = TransactionType.entries,
                        selected = occasion.type,
                        optionLabel = { type -> stringResource(type.labelRes()) },
                        onSelect = { type -> onOccasionTypeChange(index, type) }
                    )
                    DateField(
                        label = stringResource(R.string.import_date_label),
                        date = occasion.date.toKotlinLocalDate(),
                        onDateChange = { date -> onOccasionDateChange(index, date.toJavaLocalDate()) },
                        modifier = Modifier.padding(top = Spacing.s)
                    )
                    AppTextField(
                        value = occasion.sharesText,
                        onValueChange = { text -> onOccasionSharesChange(index, text) },
                        label = stringResource(R.string.import_occasion_shares_label),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                    if (!occasion.dateConfident) {
                        Text(
                            stringResource(R.string.import_date_uncertain),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (rowState.occasions.size > 1) {
                    AppIconButton(
                        icon = UiR.drawable.ic_close,
                        contentDescription = stringResource(R.string.import_remove_occasion),
                        onClick = { onRemoveOccasion(index) }
                    )
                }
            }
        }
        AppButton(
            text = stringResource(R.string.import_add_occasion),
            onClick = onAddOccasion,
            modifier = Modifier.padding(top = Spacing.xs),
            variant = ButtonVariant.Text
        )
        if (rowState.sharesMismatch) {
            Text(
                stringResource(
                    R.string.format_import_shares_mismatch,
                    rowState.occasionSharesTotal.toString(),
                    row.shares.toString()
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
