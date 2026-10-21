package se.partee71.fonder.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import se.partee71.fonder.R
import se.partee71.ui.components.ConfirmDialog

/**
 * Stängbar "import klar"-dialog (issue #19) — ersätter en tidigare fullskärms
 * tom-tillståndsvy vars enda väg ut var systemets bakåtknapp. Delad mellan Excel-innehålls-
 * och PDF-avräkningsnota-importflödena (regel 4), som annars skulle bygga två identiska
 * dialoger. Stäng är en fylld knapp – dialogens enda och därmed primära åtgärd (UI-12). Ritas
 * med android-ui:s `ConfirmDialog` utan avbryt-knapp (`dismissLabel = null`, UI-13); tryck utanför
 * stänger också.
 */
@Composable
fun ImportCompleteDialog(importedCount: Int, onDismiss: () -> Unit, notice: String? = null) {
    ConfirmDialog(
        title = stringResource(R.string.import_success_title),
        message = listOfNotNull(stringResource(R.string.format_import_success_body, importedCount), notice).joinToString("\n\n"),
        confirmLabel = stringResource(R.string.close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        dismissLabel = null
    )
}
