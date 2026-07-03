package se.partee71.fonder.ui.transaktioner

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.theme.MonoAmountStyle
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

@Composable
fun TransaktionerScreen(
    modifier: Modifier = Modifier,
    viewModel: TransaktionerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<TransaktionRad?>(null) }

    if (state.isEmpty) {
        EmptyState(
            title = stringResource(R.string.transaktioner_empty_title),
            body = stringResource(R.string.transaktioner_empty_body),
            modifier = modifier,
        )
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(state.rows, key = { it.transaction.id }) { row ->
                TransactionRow(row = row, onLongPress = { pendingDelete = row })
            }
        }
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.transaktion_delete_title)) },
            text = { Text(stringResource(R.string.format_transaktion_delete_confirm, row.fundName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTransaction(row.transaction.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionRow(row: TransaktionRad, onLongPress: () -> Unit) {
    val tx = row.transaction
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .combinedClickable(onClick = {}, onLongClick = onLongPress),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(row.fundName, style = MaterialTheme.typography.titleSmall)
            Text(
                typeLabel(tx.type) + " · " + LocalDate.ofEpochDay(tx.epochDay).format(dateFormatter),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.format_transaktion_rad, tx.shares.toString(), MoneyFormat.kr(tx.pricePerShare)),
                style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
            )
            Text(
                MoneyFormat.kr(tx.amount),
                style = MonoAmountStyle.merge(MaterialTheme.typography.titleMedium),
            )
        }
    }
}

@Composable
private fun typeLabel(type: TransactionType): String = when (type) {
    TransactionType.KOP -> stringResource(R.string.transaktion_kop)
    TransactionType.SALJ -> stringResource(R.string.transaktion_salj)
}
