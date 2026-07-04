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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.components.DateField
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.SelectField
import se.partee71.fonder.ui.theme.MonoAmountStyle
import java.time.LocalDate

private const val XLSX_MIME_TYPE =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** Importera befintliga innehav från Handelsbankens "Innehav Fonder"-Excel-export (issue #8). */
@Composable
fun ImportHoldingsScreen(
    modifier: Modifier = Modifier,
    viewModel: ImportHoldingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) viewModel.onFileSelected(bytes)
        }
    }

    when {
        state.imported -> EmptyState(
            title = stringResource(R.string.import_success_title),
            body = stringResource(R.string.import_success_body),
            modifier = modifier,
        )

        !state.fileSelected -> Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.import_pick_file_body), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { filePicker.launch(arrayOf(XLSX_MIME_TYPE)) },
                modifier = Modifier.padding(top = 16.dp),
            ) { Text(stringResource(R.string.import_pick_file)) }
        }

        state.error != null -> EmptyState(
            title = stringResource(R.string.import_title),
            body = stringResource(
                if (state.error == ImportError.EMPTY_FILE) R.string.import_error_empty else R.string.import_error_parse,
            ),
            modifier = modifier,
        )

        else -> Column(modifier = modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.rows, key = { it.row.isin + it.row.shares }) { rowState ->
                    ImportRowCard(
                        rowState = rowState,
                        catalogFunds = state.catalogFunds,
                        onFundOverride = { fund -> viewModel.onFundOverride(rowState.row, fund) },
                        onIncludedChange = { included -> viewModel.onIncludedChange(rowState.row, included) },
                        onOccasionDateChange = { index, date -> viewModel.onOccasionDateChange(rowState.row, index, date) },
                        onOccasionSharesChange = { index, text -> viewModel.onOccasionSharesChange(rowState.row, index, text) },
                        onAddOccasion = { viewModel.onAddOccasion(rowState.row) },
                        onRemoveOccasion = { index -> viewModel.onRemoveOccasion(rowState.row, index) },
                    )
                }
            }
            Button(
                onClick = viewModel::import,
                enabled = state.canImport,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(stringResource(R.string.import_commit_button, state.rows.count { it.readyToImport }))
            }
        }
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
    onAddOccasion: () -> Unit,
    onRemoveOccasion: (Int) -> Unit,
) {
    val row = rowState.row
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rowState.included, onCheckedChange = onIncludedChange)
                Column {
                    Text(row.fundName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.format_import_row_isin, row.isin),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(R.string.format_transaktion_rad, row.shares.toString(), MoneyFormat.kr(row.averageCostPerShare)),
                style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                modifier = Modifier.padding(top = 8.dp),
            )

            SelectField(
                label = stringResource(R.string.import_match_label),
                options = catalogFunds,
                selected = rowState.matchedFund,
                optionLabel = { it.name },
                onSelect = onFundOverride,
                placeholder = stringResource(R.string.import_match_none),
                modifier = Modifier.padding(top = 8.dp),
            )
            if (rowState.matchConfidence != null && rowState.matchConfidence < 0.75) {
                Text(
                    stringResource(R.string.import_match_uncertain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                stringResource(R.string.import_occasions_label),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            rowState.occasions.forEachIndexed { index, occasion ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        DateField(
                            label = stringResource(R.string.import_date_label),
                            date = occasion.date,
                            onDateChange = { date -> onOccasionDateChange(index, date) },
                        )
                        OutlinedTextField(
                            value = occasion.sharesText,
                            onValueChange = { text -> onOccasionSharesChange(index, text) },
                            label = { Text(stringResource(R.string.import_occasion_shares_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        if (!occasion.dateConfident) {
                            Text(
                                stringResource(R.string.import_date_uncertain),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (rowState.occasions.size > 1) {
                        IconButton(onClick = { onRemoveOccasion(index) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.import_remove_occasion))
                        }
                    }
                }
            }
            TextButton(onClick = onAddOccasion, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.import_add_occasion))
            }
            if (rowState.sharesMismatch) {
                Text(
                    stringResource(
                        R.string.format_import_shares_mismatch,
                        rowState.occasionSharesTotal.toString(),
                        row.shares.toString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
