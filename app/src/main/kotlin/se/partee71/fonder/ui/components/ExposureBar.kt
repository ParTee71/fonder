package se.partee71.fonder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.theme.MonoAmountStyle

/**
 * Delad rad för en kategoris andel av en helhet (POR-9, issue #66) — etikett, procent
 * ([MoneyFormat.percent]) och en bredd-proportionell stapel. Ersätter ett tårtdiagram: Vico
 * (TP-12) är kartesiskt utan tårtdiagram-stöd, och den här återhållsamma, textdrivna raden
 * matchar appens stil bättre (regel 4) — återanvänds för fondtyp-, region- och
 * index/aktivt-sektionerna i Portfölj, ingen ny variant per dimension.
 *
 * @param color stapelns färg. Okänd-hinkar (t.ex. "Okänd region") ska skickas med en dämpad
 *   färg (`MaterialTheme.colorScheme.outline`) så de syns som skilda från de riktiga
 *   kategorierna även rent visuellt, inte bara i sin plats sist i listan.
 */
@Composable
fun ExposureBar(
    label: String,
    fraction: Double,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Vikt + ellips av samma skäl som i [PeriodRow] (issue #78): utan dem kunde en lång
            // kategorietikett äta upp hela raden och lämna noll bredd till procenten.
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
            )
            Text(MoneyFormat.percent(fraction), style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(6.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
                    .background(color, RoundedCornerShape(3.dp)),
            )
        }
    }
}
