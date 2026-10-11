package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Listrad (UI-13): rubrik, valfri undertext under och valfritt innehåll längst till höger, minst
 * 56 dp hög som i mockupen. Raden läses som **en** nod av skärmläsaren (rubrik och undertext i ett
 * svep); en knapp i [trailing] förblir en egen nod med egen åtgärd. Rubriken kapas inte bort till
 * förmån för [trailing] — den får raden utom det [trailing] behöver (UI-6).
 *
 * Första steget mot appens enda listrad (skill shared-ui-components → Kända dubbletter): i dag
 * fondsökens träffrad; de egna raderna på övriga skärmar flyttas hit en i taget.
 */
@Composable
fun ItemRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(headline, style = MaterialTheme.typography.bodyLarge)
            supporting?.invoke()
        }
        trailing?.invoke()
    }
}
