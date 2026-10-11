package se.partee71.fonder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Storleken på [CountBadge]: liten bredvid en flikikon, stor som ledande element i en rad. */
enum class CountBadgeSize { Small, Large }

/**
 * Kopparmärket med ett antal (UI-14) – på Beslut-fliken i [AppFloatingToolbar] och i Översiktens
 * "Att besluta"-rad ([ReminderRow] med `count`). Rund, i sekundärfärgen (koppar) med text i
 * `onSecondary`; växer på bredden för tvåsiffriga tal.
 *
 * Dekorativt för skärmläsaren: antalet ska alltid också stå i omgivande text eller beskrivning
 * (flikens "Beslut, 3 nya", radens undertext), så märket läses inte en gång till.
 */
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    size: CountBadgeSize = CountBadgeSize.Small,
) {
    val minSize = if (size == CountBadgeSize.Large) 28.dp else 18.dp
    Box(
        modifier = modifier
            .clearAndSetSemantics {}
            .heightIn(min = minSize)
            .widthIn(min = minSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondary)
            .padding(horizontal = if (size == CountBadgeSize.Large) 8.dp else 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            style = if (size == CountBadgeSize.Large) {
                MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            } else {
                MaterialTheme.typography.labelSmall
            },
            color = MaterialTheme.colorScheme.onSecondary,
        )
    }
}
