package se.partee71.fonder.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Påminnelserad överst på en skärm — en lugn uppmaning om något användaren bör göra, med en
 * åtgärd (HEM-12: "Ändringar sedan senaste säkerhetskopian"). Mässingstonad
 * (`secondaryContainer`) så den skiljer sig från korten utan att se ut som ett fel.
 *
 * **En** klickbar nod: hela raden utför [onAction], och åtgärdens etikett ritas som en
 * textknapp i raden i stället för att vara en egen knapp. Skärmläsaren får därmed en
 * sammanhållen beskrivning — rubrik, undertext och åtgärd — i stället för tre noder, och
 * tryckytan blir hela raden, minst 48 dp hög (UI-3). Ingen stängknapp: raden försvinner när
 * orsaken gör det.
 */
@Composable
fun ReminderRow(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Backup,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onAction)
                .heightIn(min = 48.dp)
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
