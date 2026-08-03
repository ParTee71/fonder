package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.fonder.ui.theme.ReturnColors

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
    valueText: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Vikt + ellips på etiketten: en Row mäter oviktade barn i tur och ordning och drar av
        // redan förbrukad bredd, så en etikett som fyller raden lämnade **noll** till värdet.
        // Med fasta etiketter ("Idag") märktes inget, men HEM-5 och ANA-9 skickar in fondnamn —
        // "Handelsbanken Amerika Småbolag Tema Criteria A1" radbröt över två rader och avgiften
        // försvann helt ur vyn (issue #78). Samma lösning som PortfoljScreen redan använder för
        // fondnamnet i sin egen rad.
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
        )
        if (valueText != null) {
            Text(
                valueText,
                style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
            )
        } else if (amount == null && fraction == null) {
            Text(
                stringResource(R.string.period_insufficient_data),
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
                    // Färgen tas ur **procenten** när den finns, inte ur kr-beloppet. För en
                    // avkastning är de alltid överens, men GAV-raden i Fonddetalj skickade in
                    // ett *pris* som belopp — alltid positivt — och färgade därför ett innehav
                    // 30 % under GAV grönt (issue #78). Tecken och färg ska alltid följas åt.
                    color = ReturnColors.forAmount(fraction ?: amount!!),
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
