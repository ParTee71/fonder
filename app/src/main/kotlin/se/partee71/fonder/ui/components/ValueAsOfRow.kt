package se.partee71.fonder.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import se.partee71.fonder.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * Diskret "Värde per <datum>"-rad (POR-7, issue #27) — visar NAV-datumet bredvid ett värde så
 * en normal endagsförskjutning mot en extern källa (t.ex. banken) blir begriplig i stället för
 * att se ut som ett fel. Delad mellan Portfölj och Hem (regel 4) i stället för en kopia var.
 * Renderar ingenting om [navEpochDay] är null (inget känt värde att ange datum för).
 */
@Composable
fun ValueAsOfRow(navEpochDay: Long?, modifier: Modifier = Modifier) {
    if (navEpochDay == null) return
    Text(
        stringResource(R.string.format_value_as_of, LocalDate.ofEpochDay(navEpochDay).format(dateFormatter)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
