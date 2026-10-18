package se.partee71.fonder.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Appens enda textfält (UI-13, regel 4) — konturerat, med temats fältrundning (`shapes.extraSmall`,
 * 12 dp) som `SelectField` och `DateField`. Parametrarna är de skärmarna faktiskt använder:
 * etikett, tangentbord (belopp och andelar med decimaltangentbord), enhet efter värdet ("%"),
 * hjälptext och felmarkering. Fältet är enradigt som standard — inget av appens fält tar fritext.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    suffix: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = label?.let { { Text(it) } },
        suffix = suffix?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        shape = MaterialTheme.shapes.extraSmall
    )
}
