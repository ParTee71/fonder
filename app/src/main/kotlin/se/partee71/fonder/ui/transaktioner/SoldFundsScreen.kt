package se.partee71.fonder.ui.transaktioner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    SoldFundsContent(state = state, modifier = modifier)
}

/** Tillståndsdriven, testbar del av [SoldFundsScreen] — inget ViewModel/Hilt-beroende (issue #21). */
@Composable
fun SoldFundsContent(state: SoldFundsUiState, modifier: Modifier = Modifier) {
    if (state.isEmpty) {
        EmptyState(
            title = stringResource(R.string.sold_funds_empty_title),
            body = stringResource(R.string.sold_funds_empty_body),
            modifier = modifier,
        )
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            SoldFundsTotalCard(state = state)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.rows, key = { it.sale.transactionId }) { row ->
                    SoldFundRow(row = row)
                }
            }
        }
    }
}

/** Summeringskort över allt realiserat resultat (SLD-3, issue #21). */
@Composable
private fun SoldFundsTotalCard(state: SoldFundsUiState) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.sold_funds_total_title), style = MaterialTheme.typography.labelMedium)
            Text(
                "${MoneyFormat.kr(state.totalRealizedGain)}" +
                    (state.totalRealizedGainFraction?.let { " · ${MoneyFormat.percentSigned(it)}" } ?: ""),
                style = MonoAmountStyle.merge(MaterialTheme.typography.headlineMedium),
                color = ReturnColors.forAmount(state.totalRealizedGain),
            )
        }
    }
}

/**
 * Hopfällbart kort (SLD-4, issue #21) — stängt som standard och visar då bara fondnamn och
 * realiserat resultat, expanderat visas övriga detaljer. Byggs lokalt i stället för att
 * återanvända [se.partee71.fonder.ui.components.ExpandableInfoRow] (regel 4) eftersom den
 * komponenten fäller ut en fast klartextförklaring bredvid ett alltid synligt innehåll — här
 * är det tvärtom listans egna detaljrader (belopp/avgift/anskaffningsvärde m.m.) som ska
 * döljas/visas, inte en förklarande text. Samma interaktionsmönster (pil, ≥48 dp träffyta,
 * `rememberSaveable`) återanvänds ändå för konsekvens.
 */
@Composable
private fun SoldFundRow(row: SoldFundRad) {
    val sale = row.sale
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded }
                .padding(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.fundName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${MoneyFormat.kr(sale.realizedGain)}" +
                            (sale.realizedGainFraction?.let { " · ${MoneyFormat.percentSigned(it)}" } ?: ""),
                        style = MonoAmountStyle.merge(MaterialTheme.typography.titleMedium),
                        color = ReturnColors.forAmount(sale.realizedGain),
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.sold_fund_collapse else R.string.sold_fund_expand,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
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
    }
}
