package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Kryssruta med etikett — appens enda kryssrad (UI-13, regel 4). `toggleable` på hela raden i
 * stället för bara på rutan: etiketten blir klickbar, tryckytan når 48 dp och skärmläsaren får
 * **en** nod med rollen kryssruta i stället för en ruta plus en lös text bredvid (UI-3).
 *
 * [supportingText] är en dämpad andra rad (importens ISIN och källfil); [labelStyle] låter en
 * kryssrad som är radens rubrik (importen) se ut som en rubrik och en kvittering (`FollowedToggleRow`)
 * vara liten. Stateless: värdet in, ändringen ut.
 */
@Composable
fun CheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column {
            Text(label, style = labelStyle)
            if (supportingText != null) {
                Text(
                    supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
