package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Blockerande väntedialog för ett arbete användaren just startat och måste invänta (importens
 * fondmatchning och kurshämtning). Går inte att stänga — arbetet avbryts inte av att dialogen
 * försvinner, och en stängd dialog hade lämnat en tom, ointeraktiv lista. Ersätter importernas två
 * likadana dialoger (regel 4). Bakgrundsarbete som inte blockerar visas i stället i kortets
 * rubrikrad ([CardTitleRow], NAV-6). Bekräftelser och meddelanden är android-ui:s `ConfirmDialog`
 * (UI-13); den här finns kvar i Fonder eftersom biblioteket saknar en väntedialog.
 */
@Composable
fun LoadingDialog(title: String, body: String) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                WorkingIndicator(working = true, contentDescription = title, size = 48.dp)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
