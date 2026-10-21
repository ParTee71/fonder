package se.partee71.fonder.ui.imports

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.ui.components.CheckRow
import se.partee71.fonder.ui.components.SelectField
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.ui.theme.Spacing

/** Under denna träffsäkerhet visas "osäker matchning" under fondvalet (IMP-serien). */
private const val UNCERTAIN_MATCH_CONFIDENCE = 0.75

/**
 * Början av en importrad, gemensam för innehavs- och orderimporten: kryssruta med fondnamn,
 * radens detaljtext, fondvalet ur katalogen och varningarna för osäker matchning och misslyckad
 * kurshämtning. Resten av kortet (datum, andelar, tillfällen) är importens eget.
 */
@Composable
internal fun ColumnScope.ImportRowHeader(
    included: Boolean,
    onIncludedChange: (Boolean) -> Unit,
    fundName: String,
    supportingText: String,
    detail: String,
    catalogFunds: List<Fund>,
    matchedFund: Fund?,
    matchConfidence: Double?,
    priceFetchFailed: Boolean,
    onFundOverride: (Fund?) -> Unit
) {
    CheckRow(
        checked = included,
        onCheckedChange = onIncludedChange,
        label = fundName,
        supportingText = supportingText,
        labelStyle = MaterialTheme.typography.titleSmall
    )
    Text(
        detail,
        style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
        modifier = Modifier.padding(top = Spacing.s)
    )

    SelectField(
        label = stringResource(R.string.import_match_label),
        options = catalogFunds,
        selected = matchedFund,
        optionLabel = { it.name },
        onSelect = onFundOverride,
        placeholder = stringResource(R.string.import_match_none),
        modifier = Modifier.padding(top = Spacing.s)
    )
    if (matchConfidence != null && matchConfidence < UNCERTAIN_MATCH_CONFIDENCE) {
        ImportRowWarning(stringResource(R.string.import_match_uncertain))
    }
    if (priceFetchFailed) {
        ImportRowWarning(stringResource(R.string.import_price_fetch_failed))
    }
}

@Composable
private fun ImportRowWarning(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
}
