package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.fonder.ui.theme.ReturnColors
import se.partee71.ui.components.AppCard

/**
 * Portföljens totalkort (HEM-1/POR-3) — totalt värde, vinst/förlust och "Värde per <datum>"
 * (POR-7). **En** komponent för Hem och Portfölj (regel 4, skill `shared-ui-components`: tidigare
 * två identiska kopior med varsitt `UiState`); tar primitiver, inte en skärms tillstånd.
 *
 * Saknar något innehav kurs visas nettoinvesterat och "Kurs saknas ännu" i stället för ett
 * felaktigt värde (POR-3) — [gainLossFraction] är då null.
 */
@Composable
fun TotalCard(
    totalValue: Double,
    totalInvested: Double,
    gainLoss: Double,
    gainLossFraction: Double?,
    navEpochDay: Long?,
    modifier: Modifier = Modifier,
    working: Boolean = false
) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        CardTitleRow(
            title = stringResource(R.string.portfolj_total_value),
            working = working
        )
        if (gainLossFraction != null) {
            Text(MoneyFormat.kr(totalValue), style = MonoAmountStyle.merge(MaterialTheme.typography.headlineMedium))
            Text(
                "${MoneyFormat.percentSigned(gainLossFraction)} · ${MoneyFormat.kr(gainLoss)}",
                style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                color = ReturnColors.forAmount(gainLoss)
            )
            ValueAsOfRow(navEpochDay = navEpochDay, modifier = Modifier.padding(top = 2.dp))
        } else {
            Text(MoneyFormat.kr(totalInvested), style = MonoAmountStyle.merge(MaterialTheme.typography.headlineMedium))
            Text(
                stringResource(R.string.portfolj_price_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
