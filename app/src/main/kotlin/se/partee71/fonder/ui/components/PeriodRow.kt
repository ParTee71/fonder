package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.fonder.ui.theme.ReturnColors

/**
 * Delad rad för att visa en periods värdeförändring (kr + %), t.ex. "Idag" eller "Senaste
 * veckan" (issue #14). Återanvänds mellan Hem och Portfölj (regel 4) — komponenten känner
 * inte till domänmodeller, bara primitiver, så den är oberoende av var förändringen kommer
 * ifrån.
 *
 * @param amount kr-förändring, eller null om historiken inte räcker tillbaka (visas som
 *   otillräcklig data i stället för ett gissat eller felaktigt värde).
 * @param partial sant om totalen är delvis osäker (något innehav saknade historik men andra
 *   kunde beräknas) — irrelevant för ett enskilt innehavs rad.
 */
@Composable
fun PeriodRow(
    label: String,
    amount: Double?,
    fraction: Double?,
    modifier: Modifier = Modifier,
    partial: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (amount == null) {
            Text(
                stringResource(R.string.period_insufficient_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(horizontalAlignment = Alignment.End) {
                val text = if (fraction != null) {
                    "${MoneyFormat.percentSigned(fraction)} · ${MoneyFormat.kr(amount)}"
                } else {
                    MoneyFormat.kr(amount)
                }
                Text(
                    text,
                    style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                    color = ReturnColors.forAmount(amount),
                )
                if (partial) {
                    Text(
                        stringResource(R.string.period_partial_data),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
