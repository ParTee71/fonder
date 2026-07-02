package se.partee71.fonder.ui.transaktioner

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.theme.MonoAmountStyle

@Composable
fun TransaktionerScreen(
    modifier: Modifier = Modifier,
    viewModel: TransaktionerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.isEmpty) {
        EmptyState(
            title = stringResource(R.string.transaktioner_empty_title),
            body = stringResource(R.string.transaktioner_empty_body),
            modifier = modifier,
        )
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(state.transactions, key = { it.id }) { tx ->
                TransactionRow(tx)
            }
        }
    }
}

@Composable
private fun TransactionRow(tx: Transaction) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("${tx.type} · ${tx.fundIsin}", style = MaterialTheme.typography.titleSmall)
            Text(
                MoneyFormat.kr(tx.amount),
                style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
