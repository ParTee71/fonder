package se.partee71.fonder.ui.imports

import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.ui.components.DateField
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.SelectField
import se.partee71.fonder.ui.theme.MonoAmountStyle

/** Importera exakta transaktioner från en eller flera Handelsbanken-avräkningsnotor (PDF), issue #8-uppföljning. */
@Composable
fun ImportOrdersScreen(
    modifier: Modifier = Modifier,
    viewModel: ImportOrdersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val files = uris.mapNotNull { uri -> readFile(context, uri) }
            if (files.isNotEmpty()) viewModel.onFilesSelected(files)
        }
    }

    when {
        state.imported -> EmptyState(
            title = stringResource(R.string.import_success_title),
            body = stringResource(R.string.import_success_body),
            modifier = modifier,
        )

        !state.filesSelected -> Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.import_orders_pick_files_body), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { filePicker.launch(arrayOf("application/pdf")) },
                modifier = Modifier.padding(top = 16.dp),
            ) { Text(stringResource(R.string.import_orders_pick_files_button)) }
        }

        state.error != null -> EmptyState(
            title = stringResource(R.string.import_orders_title),
            body = stringResource(R.string.import_orders_error_none_parsed),
            modifier = modifier,
        )

        else -> Column(modifier = modifier.fillMaxSize()) {
            if (state.unparsedFileNames.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.format_import_orders_unparsed,
                        state.unparsedFileNames.size,
                        state.unparsedFileNames.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(
                    state.rows,
                    key = { "${it.transaction.isin}-${it.transaction.epochDay}-${it.transaction.shares}-${it.transaction.sourceFileName}" },
                ) { rowState ->
                    ImportOrderRowCard(
                        rowState = rowState,
                        catalogFunds = state.catalogFunds,
                        onFundOverride = { fund -> viewModel.onFundOverride(rowState.transaction, fund) },
                        onIncludedChange = { included -> viewModel.onIncludedChange(rowState.transaction, included) },
                        onDateChange = { date -> viewModel.onDateChange(rowState.transaction, date) },
                        onSharesTextChange = { text -> viewModel.onSharesTextChange(rowState.transaction, text) },
                        onPriceTextChange = { text -> viewModel.onPriceTextChange(rowState.transaction, text) },
                    )
                }
            }
            Button(
                onClick = viewModel::import,
                enabled = state.canImport,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(stringResource(R.string.import_orders_commit_button, state.rows.count { it.readyToImport }))
            }
        }
    }

    // Läsning/tolkning av flera PDF-filer kan ta en stund — overlay-modal precis som
    // Excel-importet (IMP-4), ingen tom eller ointeraktiv vy under tiden.
    if (state.loading) {
        ImportOrdersLoadingDialog()
    }
}

private fun readFile(context: android.content.Context, uri: Uri): Pair<String, ByteArray>? {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
    } ?: uri.lastPathSegment.orEmpty()
    return name to bytes
}

@Composable
private fun ImportOrdersLoadingDialog() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(
                    stringResource(R.string.import_orders_loading_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(R.string.import_orders_loading_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
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
) {
    val tx = rowState.transaction
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rowState.included, onCheckedChange = onIncludedChange)
                Column {
                    Text(tx.fundName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.format_import_order_isin, tx.isin, tx.sourceFileName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(if (tx.type == TransactionType.KOP) R.string.transaktion_kop else R.string.transaktion_salj),
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

            DateField(
                label = stringResource(R.string.import_date_label),
                date = rowState.date,
                onDateChange = onDateChange,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedTextField(
                value = rowState.sharesText,
                onValueChange = onSharesTextChange,
                label = { Text(stringResource(R.string.import_order_shares_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = rowState.priceText,
                onValueChange = onPriceTextChange,
                label = { Text(stringResource(R.string.import_order_price_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}
