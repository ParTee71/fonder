package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Delad dropdown för att välja ett värde ur en lista (regel 4 — återbruk). Använd i stället
 * för att bygga en ny `ExposedDropdownMenuBox`-variant för varje skärm.
 *
 * `ExposedDropdownMenu` är sedan material3 1.5 en importerad extension på
 * `ExposedDropdownMenuBoxScope`; `Modifier.menuAnchor(type)` löses via mottagarscopet.
 *
 * @param clearOptionLabel om satt visas ett extra menyval överst som sätter valet till null
 *   (t.ex. "Alla fondbolag"). Lämna null när ett värde alltid måste vara valt.
 * @param supportingText förklaring under fältet (bytesväljaren, BYT-2) — läses av skärmläsaren
 *   tillsammans med fältet.
 * @param enabled false låser fältet med valet synligt (förifyllt formulär från Följ, BYT-2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SelectField(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    clearOptionLabel: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected?.let(optionLabel) ?: placeholder,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            supportingText = supportingText?.let { text -> { Text(text) } },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            if (clearOptionLabel != null) {
                DropdownMenuItem(
                    text = { Text(clearOptionLabel) },
                    onClick = { onSelect(null); expanded = false },
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}
