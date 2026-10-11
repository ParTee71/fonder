package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import se.partee71.fonder.R

/**
 * Appens enda dialog (UI-13, regel 4): rubrik, brödtext och en eller två knappar på temats
 * dialogyta med 28 dp rundning (`shapes.extraLarge`). Huvudåtgärden är fylld — i felfärgen när den
 * inte går att ångra ([AppButtonVariant.Danger]) — och avbryt är en textknapp. Utan [dismissLabel]
 * har dialogen en enda knapp (som "Import klar").
 */
@Composable
fun AppDialog(
    title: String,
    body: String,
    onDismissRequest: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmVariant: AppButtonVariant = AppButtonVariant.Primary,
    dismissLabel: String? = null,
    onDismiss: () -> Unit = onDismissRequest,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { AppButton(text = confirmLabel, onClick = onConfirm, variant = confirmVariant) },
        dismissButton = dismissLabel?.let { label ->
            { AppButton(text = label, onClick = onDismiss, variant = AppButtonVariant.Text) }
        },
    )
}

/**
 * Bekräftelse före en åtgärd som ersätter eller raderar data (UI-13) — "Ta bort transaktionen?",
 * "Töm databasen?", "Återställ från säkerhetskopia?". Avbryt och ett tryck utanför stänger utan att
 * något händer. [destructive] färgar bekräftelseknappen i felfärgen, som i mockupen.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AppDialog(
        title = title,
        body = body,
        onDismissRequest = onDismiss,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        confirmVariant = if (destructive) AppButtonVariant.Danger else AppButtonVariant.Primary,
        dismissLabel = stringResource(R.string.cancel),
    )
}

/**
 * Blockerande väntedialog för ett arbete användaren just startat och måste invänta (importens
 * fondmatchning och kurshämtning). Går inte att stänga — arbetet avbryts inte av att dialogen
 * försvinner, och en stängd dialog hade lämnat en tom, ointeraktiv lista. Ersätter importernas två
 * likadana dialoger (regel 4). Bakgrundsarbete som inte blockerar visas i stället i kortets
 * rubrikrad ([CardTitleRow], NAV-6).
 */
@Composable
fun LoadingDialog(title: String, body: String) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                WorkingIndicator(working = true, contentDescription = title, size = 48.dp)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
