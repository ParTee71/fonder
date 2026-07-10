package se.partee71.fonder.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import se.partee71.fonder.R

/**
 * Stängbar "import klar"-dialog (issue #19) — ersätter en tidigare fullskärms
 * tom-tillståndsvy vars enda väg ut var systemets bakåtknapp. Delad mellan Excel-innehålls-
 * och PDF-avräkningsnota-importflödena (regel 4), som annars skulle bygga två identiska
 * dialoger.
 */
@Composable
fun ImportCompleteDialog(importedCount: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_success_title)) },
        text = { Text(stringResource(R.string.format_import_success_body, importedCount)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
