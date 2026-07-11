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

/** Varför [PeriodRow] saknar ett värde att visa (issue #18) — skild text för varje orsak, aldrig en gissning. */
enum class UnavailableReason {
    /** Historiken når inte tillbaka till periodens start (t.ex. en nytillagd fond). */
    INSUFFICIENT_DATA,

    /** Vår senast kända kurs är äldre än periodens start — vi vet inte vad som hänt sedan dess. */
    STALE_PRICE,
}

/**
 * Delad rad för att visa en periods värdeförändring (kr + %), t.ex. "Idag" eller "Senaste
 * veckan" (issue #14), eller ett rent procentuellt nyckeltal utan kr-belopp (t.ex. CAGR eller
 * portföljandel, issue #16 — sätt [amount] till null och [fraction] till ett värde). Återanvänds
 * mellan Hem, Portfölj och Fonddetalj (regel 4) — komponenten känner inte till domänmodeller,
 * bara primitiver, så den är oberoende av var förändringen/nyckeltalet kommer ifrån.
 *
 * @param amount kr-förändring, eller null om det saknas — antingen för att historiken inte
 *   räcker tillbaka (otillräcklig data, om [fraction] också är null), eller för att nyckeltalet
 *   aldrig har ett kr-belopp (rent procentuellt, om [fraction] är satt).
 * @param partial sant om totalen är delvis osäker (något innehav saknade historik men andra
 *   kunde beräknas) — irrelevant för ett enskilt innehavs rad.
 * @param unavailableReason vilken text som visas när [amount] och [fraction] båda är null
 *   (issue #18) — skiljer en inaktuell kurs (aldrig ett vilseledande `0`) från äkta otillräcklig
 *   historik.
 * @param valueText ett färdigformaterat, neutralfärgat värde att visa i stället för
 *   kr/procent-formateringen (issue #24) — för nyckeltal som inte är avkastningar och därför
 *   inte ska tecken-/färgkodas som vinst/förlust (t.ex. volatilitet, Sharpe-kvot). Är det null
 *   markeras raden som otillräcklig data, samma som en saknad avkastning.
 */
@Composable
fun PeriodRow(
    label: String,
    amount: Double?,
    fraction: Double?,
    modifier: Modifier = Modifier,
    partial: Boolean = false,
    unavailableReason: UnavailableReason = UnavailableReason.INSUFFICIENT_DATA,
    valueText: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (valueText != null) {
            Text(
                valueText,
                style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
            )
        } else if (amount == null && fraction == null) {
            Text(
                stringResource(
                    if (unavailableReason == UnavailableReason.STALE_PRICE) {
                        R.string.period_stale_price
                    } else {
                        R.string.period_insufficient_data
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(horizontalAlignment = Alignment.End) {
                val text = when {
                    amount != null && fraction != null -> "${MoneyFormat.percentSigned(fraction)} · ${MoneyFormat.kr(amount)}"
                    amount != null -> MoneyFormat.kr(amount)
                    else -> MoneyFormat.percentSigned(fraction!!)
                }
                Text(
                    text,
                    style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                    color = ReturnColors.forAmount(amount ?: fraction!!),
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
