package se.partee71.fonder.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.MoneyFormat

/**
 * En rad med en **ungefärlig skatt** (issue #199): etiketten, vad skatten bygger på under den och
 * beloppet som "ca −3 000 kr" — i hela kronor, med minustecken eftersom skatten är en kostnad, och
 * dämpat ([PeriodRow]s `valueMuted`) så att det varken konkurrerar med radens resultat eller läses
 * som vinst eller förlust. Samma rad på Hem (HEM-16), i Händelser (TRX-8) och i Fonddetalj (ANA-15).
 *
 * @param tax skatten i kr, positiv (en kostnad); visas med minustecken.
 * @param basis vad beloppet bygger på, t.ex. "30 % på vinst efter kvittning"; null = bara etiketten.
 */
@Composable
fun ApproxTaxRow(label: String, tax: Double, modifier: Modifier = Modifier, basis: String? = null) {
    PeriodRow(
        label = label,
        amount = null,
        fraction = null,
        modifier = modifier,
        valueText = stringResource(R.string.format_tax_approx, MoneyFormat.krWhole(-tax)),
        valueMuted = true,
        supportingLabel = basis
    )
}
