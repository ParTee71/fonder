package se.partee71.fonder.ui.components

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import se.partee71.fonder.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Delad datumväljare (regel 4 — återbruk). Använder plattformens `DatePickerDialog` i
 * stället för Compose Material3:s (mer experimentella) datumväljar-API, för att hålla
 * beroendet på en stabil, väl beprövad yta.
 *
 * Två saker som plattformsdialogen kräver att komponenten sköter själv (issue #78):
 *
 * 1. **Livscykeln.** Dialogen ägs av Android, inte av compositionen. Utan [DisposableEffect]
 *    överlevde den inte en rotation — den försvann från skärmen och lämnade ett `WindowLeaked`
 *    efter sig, eftersom den höll kvar den förstörda aktivitetens fönster.
 * 2. **Semantiken.** Det synliga fältet är skrivskyddat och klicket ligger på en genomskinlig
 *    yta ovanpå, så en skärmläsare fick ett textfält utan någon aktiverbar åtgärd. Fältet döljs
 *    därför ur semantikträdet och hela komponenten presenteras som **en** knapp, med etikett
 *    och nuvarande värde.
 */
@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }
    val initial = date ?: LocalDate.now()
    val formattedDate = date?.format(formatter) ?: ""
    val notSet = stringResource(R.string.date_field_not_set)
    val description = stringResource(
        R.string.format_date_field_description,
        label,
        formattedDate.ifEmpty { notSet },
    )
    val openLabel = stringResource(R.string.date_field_open_picker)

    // Den öppna dialogen, så den kan stängas när komponenten lämnar compositionen.
    var openDialog: DatePickerDialog? by remember { mutableStateOf(null) }
    DisposableEffect(Unit) {
        onDispose {
            openDialog?.dismiss()
            openDialog = null
        }
    }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = formattedDate,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.DateRange, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(role = Role.Button, onClickLabel = openLabel) {
                    openDialog = DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth -> onDateChange(LocalDate.of(year, month + 1, dayOfMonth)) },
                        initial.year,
                        initial.monthValue - 1,
                        initial.dayOfMonth,
                    ).also { it.show() }
                }
                .semantics { contentDescription = description },
        )
    }
}
