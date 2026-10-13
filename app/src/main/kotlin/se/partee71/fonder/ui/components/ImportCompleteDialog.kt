package se.partee71.fonder.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import se.partee71.fonder.R

/**
 * Stängbar "import klar"-dialog (issue #19) — ersätter en tidigare fullskärms
 * tom-tillståndsvy vars enda väg ut var systemets bakåtknapp. Delad mellan Excel-innehålls-
 * och PDF-avräkningsnota-importflödena (regel 4), som annars skulle bygga två identiska
 * dialoger. Stäng är en fylld knapp – dialogens enda och därmed primära åtgärd (UI-12). Ritas
 * med den delade [AppDialog] (UI-13).
 */
@Composable
fun ImportCompleteDialog(importedCount: Int, onDismiss: () -> Unit, notice: String? = null) {
    AppDialog(
        title = stringResource(R.string.import_success_title),
        body = listOfNotNull(stringResource(R.string.format_import_success_body, importedCount), notice).joinToString("\n\n"),
        onDismissRequest = onDismiss,
        confirmLabel = stringResource(R.string.close),
        onConfirm = onDismiss,
    )
}
