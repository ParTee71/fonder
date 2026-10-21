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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
 * Visas både procent och kr ("+7,8 % · +29 870 kr") färgas varje tal efter sitt eget tecken:
 * procenten (fondens kursutveckling) och kronorna (värdeförändringen på ägda andelar, issue #193)
 * kan ha olika tecken, t.ex. när andelarna köpts efter en uppgång.
 *
 * @param amount kr-förändring, eller null om det saknas — antingen för att historiken inte
 *   räcker tillbaka (otillräcklig data, om [fraction] också är null), eller för att nyckeltalet
 *   aldrig har ett kr-belopp (rent procentuellt, om [fraction] är satt).
 * @param partial sant om totalen är delvis osäker (något innehav saknade historik men andra
 *   kunde beräknas) — irrelevant för ett enskilt innehavs rad.
 * @param stackValue lägger kr-beloppet på **egen rad under** procenten i stället för bredvid den
 *   (facit, SET-5/issue #80 — där procenten är huvudmåttet och kronorna dess konsekvens). Opt-in
 *   just för att varje befintligt anropsställe ska rendera exakt som förut; utan flaggan är den
 *   här komponenten oförändrad. Ignoreras om inte både [amount] och [fraction] finns — då finns
 *   det bara ett tal att visa.
 * @param valueText ett färdigformaterat, neutralfärgat värde att visa i stället för
 *   kr/procent-formateringen (issue #24) — för nyckeltal som inte är avkastningar och därför
 *   inte ska tecken-/färgkodas som vinst/förlust (t.ex. volatilitet, Sharpe-kvot). Är det null
 *   markeras raden som otillräcklig data, samma som en saknad avkastning.
 * @param muted dämpar hela raden (etikett och värde i `onSurfaceVariant`, ingen vinst/förlust-färg)
 *   för en **underordnad** siffra som inte ska konkurrera med radens huvudtal — totalkortets
 *   "Varav orealiserat" under totalresultatet (HEM-1, issue #191). Opt-in: utan flaggan är raden
 *   oförändrad.
 * @param insufficientText texten som visas i stället för "Otillräcklig data" när värdet saknas —
 *   när orsaken är känd och säger mer, t.ex. "Visas efter ett år" för en årstakt som inte får
 *   räknas på en yngre portfölj (HEM-1, issue #191). Null = standardtexten.
 * @param supportingLabel en dämpad rad **under etiketten** – vad värdet bygger på, t.ex. "4 utvärderade
 *   av 5" eller mätdagen (facit, SET-5/issue #197). Null = bara etiketten, som förut.
 * @param supportingValue en dämpad rad **under värdet** – t.ex. kronsumman under en träffandel eller
 *   "median · 2 av 3 bättre" under en procent (issue #197). Visas även under otillräcklig data
 *   ([insufficientText]), så att ett känt stödvärde aldrig tappas för att huvudvärdet saknas. Null =
 *   inget under värdet, som förut.
 * @param valueTone färgar [valueText] som vinst eller förlust efter tecknet, för ett färdigformaterat
 *   värde som ändå är ett utfall ("3 av 5 bättre", "+8,4 % per år", issue #197); exakt 0.0 ger den
 *   dämpade färgen (`onSurfaceVariant`), t.ex. för "1 av 2 bättre". Null = neutral färg, som förut.
 *   Ignoreras när [muted] är satt.
 */
@Composable
fun PeriodRow(
    label: String,
    amount: Double?,
    fraction: Double?,
    modifier: Modifier = Modifier,
    partial: Boolean = false,
    stackValue: Boolean = false,
    valueText: String? = null,
    muted: Boolean = false,
    insufficientText: String? = null,
    supportingLabel: String? = null,
    supportingValue: String? = null,
    valueTone: Double? = null
) {
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Vikt + ellips på etiketten: en Row mäter oviktade barn i tur och ordning och drar av
        // redan förbrukad bredd, så en etikett som fyller raden lämnade **noll** till värdet.
        // Med fasta etiketter ("Idag") märktes inget, men HEM-5 och ANA-9 skickar in fondnamn —
        // "Handelsbanken Amerika Småbolag Tema Criteria A1" radbröt över två rader och avgiften
        // försvann helt ur vyn (issue #78). Samma lösning som PortfoljScreen redan använder för
        // fondnamnet i sin egen rad.
        val labelModifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
        val labelColor = if (muted) mutedColor else Color.Unspecified
        if (supportingLabel == null) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = labelModifier)
        } else {
            Column(modifier = labelModifier) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(supportingLabel, style = MaterialTheme.typography.bodySmall, color = mutedColor)
            }
        }
        if (valueText != null) {
            val valueColor = when {
                muted -> mutedColor
                valueTone == 0.0 -> mutedColor
                valueTone != null -> ReturnColors.forAmount(valueTone)
                else -> Color.Unspecified
            }
            val valueStyle = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium)
            if (supportingValue == null) {
                Text(valueText, style = valueStyle, color = valueColor)
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Text(valueText, style = valueStyle, color = valueColor)
                    SupportingValue(supportingValue)
                }
            }
        } else if (amount == null && fraction == null) {
            val insufficient = insufficientText ?: stringResource(R.string.period_insufficient_data)
            if (supportingValue == null) {
                Text(insufficient, style = MaterialTheme.typography.bodySmall, color = mutedColor)
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Text(insufficient, style = MaterialTheme.typography.bodySmall, color = mutedColor)
                    SupportingValue(supportingValue)
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.End) {
                val stacked = stackValue && amount != null && fraction != null
                // Tecken och färg följs åt **per tal** (issue #78): procenten färgas efter procenten och
                // kronorna efter kronorna. GAV-raden i Fonddetalj skickade en gång in ett *pris* som belopp
                // — alltid positivt — och färgade ett innehav 30 % under GAV grönt. Sedan #193 kan de två
                // talen dessutom få olika tecken på riktigt: procenten är fondens kursutveckling, kronorna
                // värdeförändringen på de andelar som ägts (köpt efter en uppgång → + % men − kr).
                val fractionColor = if (muted) mutedColor else ReturnColors.forAmount(fraction ?: amount!!)
                val text = when {
                    stacked -> AnnotatedString(MoneyFormat.percentSigned(fraction!!))

                    amount != null && fraction != null -> {
                        val amountColor = if (muted) mutedColor else ReturnColors.forAmount(amount)
                        buildAnnotatedString {
                            // Separatorn hör till procenten — med samma tecken renderas raden exakt som förut.
                            append("${MoneyFormat.percentSigned(fraction)} · ")
                            withStyle(SpanStyle(color = amountColor)) { append(MoneyFormat.kr(amount)) }
                        }
                    }

                    amount != null -> AnnotatedString(MoneyFormat.kr(amount))

                    else -> AnnotatedString(MoneyFormat.percentSigned(fraction!!))
                }
                Text(
                    text,
                    style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                    color = fractionColor
                )
                if (stacked) SupportingValue(MoneyFormat.kr(amount!!))
                supportingValue?.let { SupportingValue(it) }
                if (partial) {
                    Text(
                        stringResource(R.string.period_partial_data),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Den dämpade raden under värdet — samma stil som den staplade kronsumman (`stackValue`). */
@Composable
private fun SupportingValue(text: String) {
    Text(
        text,
        style = MonoAmountStyle.merge(MaterialTheme.typography.bodySmall),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
