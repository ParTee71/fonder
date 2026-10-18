package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Förloppsstapel över en hel yta (UI-13) — M3 Expressives vågiga stapel, som laddningsindikatorn
 * ett led i samma formspråk (UI-12). Utan [progress] är den obestämd (fondsökens katalog laddas);
 * med visar den hur långt ett arbete kommit (0–1). Väntan i ett kort visas i stället med
 * [WorkingIndicator] i kortets rubrikrad (NAV-6).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProgressBar(modifier: Modifier = Modifier, progress: (() -> Float)? = null) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.primaryContainer
    if (progress == null) {
        LinearWavyProgressIndicator(modifier = modifier.fillMaxWidth(), color = color, trackColor = track)
    } else {
        LinearWavyProgressIndicator(
            progress = progress,
            modifier = modifier.fillMaxWidth(),
            color = color,
            trackColor = track
        )
    }
}
