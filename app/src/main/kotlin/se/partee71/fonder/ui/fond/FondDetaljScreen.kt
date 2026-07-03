package se.partee71.fonder.ui.fond

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
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
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.diagram.FundLineChart
import se.partee71.fonder.ui.theme.MonoAmountStyle
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** Fonddetalj — kurshistorik senaste året i diagram och tabell (issue #7). */
@Composable
fun FondDetaljScreen(
    modifier: Modifier = Modifier,
    viewModel: FondDetaljViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        state.isEmpty -> EmptyState(
            title = state.fundName ?: stringResource(R.string.fond_title),
            body = stringResource(R.string.fond_history_empty_body),
            modifier = modifier,
        )

        else -> LazyColumn(modifier = modifier.fillMaxSize()) {
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(state.fundName ?: "", style = MaterialTheme.typography.titleLarge)
                    FundLineChart(
                        points = state.prices.sortedBy { it.epochDay }.map { it.epochDay to it.nav },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                HorizontalDivider()
            }
            items(state.prices, key = { it.epochDay }) { price ->
                PriceRow(price)
            }
        }
    }
}

@Composable
private fun PriceRow(price: FundPrice) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            LocalDate.ofEpochDay(price.epochDay).format(dateFormatter),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            MoneyFormat.kr(price.nav),
            style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
        )
    }
}
