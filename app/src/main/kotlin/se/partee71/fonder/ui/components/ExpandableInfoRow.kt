package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Delad, återanvändbar rad som kan fällas ut med en klartextförklaring (issue #22, regel 4).
 * Radens synliga innehåll ([content]) — t.ex. en [PeriodRow] eller en signalrad — ligger kvar,
 * och en pil till höger fäller ut/ihop en pedagogisk [explanation] under.
 *
 * Ett tunt skal ovanpå [ExpandableSection] (issue #85): själva utfällningsmekaniken — klickbar
 * rad med ≥48 dp träffyta, pil med `contentDescription` (UI-3) och `rememberSaveable`-läge
 * (UI-9) — bor där, så samma mönster ser likadant ut oavsett om det som fälls ut är en mening
 * eller en hel sektion.
 *
 * @param extraContent valfritt innehåll som visas under [explanation] när raden är utfälld, t.ex.
 *   ett jämförelsediagram för ett bytesförslag (ANA-11).
 * @param onExpand se [ExpandableSection.onExpand] — körs när raden fälls ut.
 */
@Composable
fun ExpandableInfoRow(
    explanation: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    onExpand: () -> Unit = {},
    extraContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    ExpandableSection(
        modifier = modifier,
        initiallyExpanded = initiallyExpanded,
        onExpand = onExpand,
        header = content,
    ) {
        Column {
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            extraContent?.invoke()
        }
    }
}
