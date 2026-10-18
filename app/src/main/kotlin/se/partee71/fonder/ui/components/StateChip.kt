package se.partee71.fonder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import se.partee71.fonder.ui.theme.ReturnColors

/**
 * Chipets ton — behållare och text ur temat. Tonerna skiljer sig i kulör och ljushet, men texten
 * bär alltid tillståndet (UI-3): färgen är aldrig ensam bärare.
 */
enum class StateChipTone {
    /** Primärbehållaren — Föreslaget (BYT-4). */
    Primary,

    /** Kopparens sekundärbehållare — Sålt. */
    Accent,

    /** Vinstens tonade behållare — Köpt. */
    Gain,

    /** Tertiärbehållaren — Avgift (billigare alternativ). */
    Tertiary,

    /** Ytvarianten — Avstått, Avbrutet, Utgånget och risknivån ([RiskBadge]). */
    Neutral
}

/**
 * Litet tillståndsmärke: text i `shapes.small` på en tonad behållare (BYT-4, UI-10). Bytesraderna på
 * Beslut bär sitt tillstånd i ett sådant ("Föreslaget", "Sålt · dag 2 av 14", "Köpt 7 okt"), och
 * [RiskBadge] är samma märke i [StateChipTone.Neutral]. Inte klickbart — inget krav på 48 dp.
 *
 * @param contentDescription vad skärmläsaren läser i stället för [text] när texten är förkortad
 *   ("Risk 5/7" → "Risknivå 5 av 7"); null läser [text] som den står.
 */
@Composable
fun StateChip(
    text: String,
    tone: StateChipTone,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val (container, content) = tone.colors()
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

@Composable
private fun StateChipTone.colors(): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        StateChipTone.Primary -> scheme.primaryContainer to scheme.onPrimaryContainer
        StateChipTone.Accent -> scheme.secondaryContainer to scheme.onSecondaryContainer
        StateChipTone.Gain -> ReturnColors.gainContainer to ReturnColors.onGainContainer
        StateChipTone.Tertiary -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        StateChipTone.Neutral -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
}
