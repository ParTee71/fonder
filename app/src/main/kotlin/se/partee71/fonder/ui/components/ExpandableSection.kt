package se.partee71.fonder.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import se.partee71.fonder.R

/**
 * Delad utfällningsmekanik (regel 4, issue #85) — en alltid synlig rubrikrad med en pil som
 * fäller ut godtyckligt innehåll under. Basen både för [ExpandableInfoRow] (rad + förklaringstext)
 * och för hela sektioner som ligger hopfällda som default, t.ex. Analys och ordlistan i
 * Fonddetalj (ANA-10): utan en gemensam byggsten hade "rubrik med pil som fäller ut"-mönstret
 * funnits i två varianter som kunde glida isär i träffyta, ikon och skärmläsartext.
 *
 * Hela rubrikraden är klickbar med minst 48 dp träffyta och pilen bär en beskrivande
 * `contentDescription` (UI-3). Utfällt läge sparas med `rememberSaveable` så det överlever en
 * rotation (UI-9).
 *
 * @param onExpand körs när sektionen fälls **ut** (inte när den fälls ihop) — kroken för innehåll
 *   som är dyrt att hämta och därför inte ska laddas förrän någon vill se det, t.ex.
 *   jämförelsediagrammets kurshistorik för en föreslagen fond (ANA-11).
 */
@Composable
fun ExpandableSection(
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    onExpand: () -> Unit = {},
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable {
                    expanded = !expanded
                    if (expanded) onExpand()
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) { header() }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.info_collapse else R.string.info_expand,
                ),
                modifier = Modifier.padding(start = 8.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) { content() }
    }
}

/** [ExpandableSection] med en ren textrubrik — det vanliga fallet för en hopfälld sektion. */
@Composable
fun ExpandableSection(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    onExpand: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    ExpandableSection(
        modifier = modifier,
        initiallyExpanded = initiallyExpanded,
        onExpand = onExpand,
        header = { Text(title, style = titleStyle) },
        content = content,
    )
}
