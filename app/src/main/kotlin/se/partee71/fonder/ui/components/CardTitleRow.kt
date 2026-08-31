package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.partee71.fonder.R

/**
 * Ett korts rubrikrad med plats för en väntesnurra (NAV-6) — den delade byggstenen varje kort
 * som kan vänta på data använder i stället för ett naket `Text` (regel 4).
 *
 * Snurran ligger **i** raden, inte som ett överlägg i kortets hörn: korten under rubriken visar
 * belopp längst till höger (`PeriodRow`), och ett hörnöverlägg hade lagt sig över just det tal
 * kortet finns till för (UI-6). Rubriken bär också `weight`, så det är etiketten som kapas när
 * utrymmet tar slut — aldrig snurran, som annars försvunnit på exakt de kort med längst rubrik.
 *
 * Snurrans `contentDescription` formuleras ur [title] ("Uppdaterar Avgifter"), eftersom flera
 * kort kan snurra samtidigt och en gemensam text då hade sagt ingenting om vilket.
 */
@Composable
fun CardTitleRow(
    title: String,
    modifier: Modifier = Modifier,
    working: Boolean = false,
    style: TextStyle = MaterialTheme.typography.labelMedium,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = style,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        WorkingIndicator(
            working = working,
            contentDescription = stringResource(R.string.format_card_working, title),
        )
    }
}
