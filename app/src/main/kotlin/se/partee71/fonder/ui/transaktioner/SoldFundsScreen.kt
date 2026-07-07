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
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.fonder.ui.theme.ReturnColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** Realiserat resultat per sälj (FIFO), en egen vy skild från orealiserad utveckling (issue #10). */
@Composable
fun SoldFundsScreen(
    modifier: Modifier = Modifier,
    viewModel: SoldFundsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.isEmpty) {
        EmptyState(
            title = stringResource(R.string.sold_funds_empty_title),
            body = stringResource(R.string.sold_funds_empty_body),
            modifier = modifier,
        )
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(state.rows, key = { it.sale.transactionId }) { row ->
                SoldFundRow(row = row)
            }
        }
    }
}

@Composable
private fun SoldFundRow(row: SoldFundRad) {
    val sale = row.sale
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(row.fundName, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.format_sold_fund_shares,
                    sale.shares.toString(),
                    LocalDate.ofEpochDay(sale.epochDay).format(dateFormatter),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${MoneyFormat.kr(sale.realizedGain)}" +
                    (sale.realizedGainFraction?.let { " · ${MoneyFormat.percentSigned(it)}" } ?: ""),
                style = MonoAmountStyle.merge(MaterialTheme.typography.titleMedium),
                color = ReturnColors.forAmount(sale.realizedGain),
            )
            Text(
                stringResource(
                    R.string.format_sold_fund_detail,
                    MoneyFormat.kr(sale.proceeds),
                    MoneyFormat.kr(sale.fee),
                    MoneyFormat.kr(sale.costBasis),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sale.uncoveredShares > 0.0) {
                Text(
                    stringResource(R.string.sold_fund_uncovered_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
