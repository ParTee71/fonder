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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Delad datumväljare (regel 4 — återbruk). Använder plattformens `DatePickerDialog` i
 * stället för Compose Material3:s (mer experimentella) datumväljar-API, för att hålla
 * beroendet på en stabil, väl beprövad yta.
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

    Box(modifier = modifier) {
        OutlinedTextField(
            value = date?.format(formatter) ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.DateRange, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable {
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth -> onDateChange(LocalDate.of(year, month + 1, dayOfMonth)) },
                        initial.year,
                        initial.monthValue - 1,
                        initial.dayOfMonth,
                    ).show()
                },
        )
    }
}
