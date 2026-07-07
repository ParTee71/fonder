package se.partee71.fonder.ui.salda

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
import se.partee71.fonder.domain.model.SoldFundResult
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.fonder.ui.theme.ReturnColors

/** Sålda fonder: en rad per fond som haft minst en säljtransaktion (issue #10). */
@Composable
fun SaldaFonderScreen(
    modifier: Modifier = Modifier,
    viewModel: SaldaFonderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        state.isEmpty -> EmptyState(
            title = stringResource(R.string.salda_empty_title),
            body = stringResource(R.string.salda_empty_body),
            modifier = modifier,
        )

        else -> LazyColumn(modifier = modifier.fillMaxSize()) {
            items(state.results, key = { it.fund.fundId }) { result ->
                SoldFundRow(result)
            }
        }
    }
}

@Composable
private fun SoldFundRow(result: SoldFundResult) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(result.fund.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.format_salda_shares_sold, result.sharesSold.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val gainLoss = result.realizedGainLoss
            val fraction = result.realizedGainLossFraction
            if (gainLoss != null) {
                Text(
                    if (fraction != null) {
                        "${MoneyFormat.percentSigned(fraction)} · ${MoneyFormat.kr(gainLoss)}"
                    } else {
                        MoneyFormat.kr(gainLoss)
                    },
                    style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                    color = ReturnColors.forAmount(gainLoss),
                )
            } else {
                Text(
                    stringResource(R.string.salda_result_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
