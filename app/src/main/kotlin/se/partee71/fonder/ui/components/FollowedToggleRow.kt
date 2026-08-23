package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import se.partee71.fonder.R

/**
 * "Genomförd"-markeringen för ett inspelat bytesförslag (SET-5, issue #80) — delad mellan Hems
 * bytesplan (HEM-8), Facit och Fonddetaljs bytesavsnitt (ANA-10), som alla visar **samma**
 * `SuggestionRecord`. Låg tidigare som en egen kopia per skärm; en tredje kopia i Fonddetalj
 * hade varit tre ställen att glömma när träffytan eller skärmläsartexten ändras (regel 4,
 * issue #90).
 *
 * `toggleable` på hela raden i stället för bara på rutan: etiketten blir klickbar, träffytan
 * når 48 dp och skärmläsaren får **en** nod med rollen kryssruta i stället för en ruta plus en
 * löstext bredvid (UI-3).
 *
 * Markeringen utför aldrig ett byte — den **registrerar** att användaren gjorde det, så facit
 * kan mäta följda råd separat från alla givna råd. Utan den skillnaden vore ett hypotetiskt och
 * ett verkligt utfall samma siffra.
 */
@Composable
fun FollowedToggleRow(
    followed: Boolean,
    onFollowedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = followed,
                role = Role.Checkbox,
                onValueChange = onFollowedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = followed, onCheckedChange = null)
        Text(stringResource(R.string.facit_followed_label), style = MaterialTheme.typography.bodySmall)
    }
}
