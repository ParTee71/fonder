package se.partee71.fonder.ui.portfolj

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.theme.MonoAmountStyle

@Composable
fun PortfoljScreen(
    onFundClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PortfoljViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        state.isEmpty -> EmptyState(
            title = stringResource(R.string.portfolj_empty_title),
            body = stringResource(R.string.portfolj_empty_body),
            modifier = modifier,
        )

        else -> Column(modifier = modifier.fillMaxSize()) {
            TotalCard(total = state.totalInvested)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.holdings, key = { it.fund.isin }) { holding ->
                    HoldingRow(holding = holding, onClick = { onFundClick(holding.fund.isin) })
                }
            }
        }
    }
}

@Composable
private fun TotalCard(total: Double) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.portfolj_total_value), style = MaterialTheme.typography.labelMedium)
            Text(MoneyFormat.kr(total), style = MonoAmountStyle.merge(MaterialTheme.typography.headlineMedium))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HoldingRow(holding: Holding, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(holding.fund.name, style = MaterialTheme.typography.titleMedium)
            Text(
                MoneyFormat.kr(holding.netInvested),
                style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
