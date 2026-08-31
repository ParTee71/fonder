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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.partee71.fonder.R

/**
 * Liten bakgrundsindikator för en pågående kursuppdatering (NAV-6, issue #27) — placeras i
 * navigeringschromets `TopAppBar`-`actions` (`AppNavigation`, regel 4: en delad byggsten i
 * stället för en egen variant per skärm). Renderar ingenting när [isRunning] är falskt, så den
 * aldrig stjäl utrymme eller uppmärksamhet i vila.
 *
 * Chromets indikator svarar på "kör appen något just nu". Vilket **kort** som väntar på data
 * svarar [WorkingIndicator] på, via [CardTitleRow].
 */
@Composable
fun WorkerStatusIcon(isRunning: Boolean, modifier: Modifier = Modifier) {
    WorkingIndicator(
        working = isRunning,
        contentDescription = stringResource(R.string.worker_status_running),
        size = 20.dp,
        modifier = modifier.padding(horizontal = 12.dp),
    )
}

/** Storleken på kortens snurra — ska läsa som en del av rubrikraden, inte som ett eget element. */
private val CARD_INDICATOR_SIZE = 16.dp

/**
 * Snurran som säger att datan bakom just den här ytan håller på att bearbetas (NAV-6) — samma
 * byggsten som chromets [WorkerStatusIcon], bara mindre och utan chromets marginal (regel 4:
 * en snurra i appen, inte en per skärm).
 *
 * Renderar ingenting när [working] är falskt: en snurra som ligger kvar som tom yta hade fått
 * layouten att hoppa varje gång ett jobb startar eller slutar, och en ständigt närvarande
 * platshållare hade lärt användaren att inte se den.
 *
 * [contentDescription] är obligatorisk och skärmspecifik — "Uppdaterar" utan att säga *vad* är
 * värdelöst för en skärmläsare när flera kort snurrar samtidigt. Använd [CardTitleRow], som
 * formulerar den ur kortets egen rubrik.
 */
@Composable
fun WorkingIndicator(
    working: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = CARD_INDICATOR_SIZE,
) {
    if (!working) return
    // Lokal kopia: inne i `semantics`-lambdan skuggar mottagarens (write-only)
    // `contentDescription` parameternamnet, och en läsning av den kompilerar inte.
    val description = contentDescription
    CircularProgressIndicator(
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = description },
        strokeWidth = 2.dp,
        color = LocalContentColor.current,
    )
}
