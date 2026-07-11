package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import se.partee71.fonder.R

/**
 * Liten bakgrundsindikator för en pågående kursuppdatering (NAV-6, issue #27) — placeras i
 * navigeringschromets `TopAppBar`-`actions` (`AppNavigation`, regel 4: en delad byggsten i
 * stället för en egen variant per skärm). Renderar ingenting när [isRunning] är falskt, så den
 * aldrig stjäl utrymme eller uppmärksamhet i vila.
 */
@Composable
fun WorkerStatusIcon(isRunning: Boolean, modifier: Modifier = Modifier) {
    if (!isRunning) return
    val description = stringResource(R.string.worker_status_running)
    CircularProgressIndicator(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .size(20.dp)
            .semantics { contentDescription = description },
        strokeWidth = 2.dp,
        color = LocalContentColor.current,
    )
}
