package se.partee71.fonder.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import se.partee71.fonder.R

/**
 * "Genomförd"-markeringen för ett inspelat bytesförslag (SET-5, issue #80) — delad mellan Hems
 * bytesplan (HEM-8), Facit och Fonddetaljs bytesavsnitt (ANA-10), som alla visar **samma**
 * `SuggestionRecord`. Låg tidigare som en egen kopia per skärm; en tredje kopia i Fonddetalj
 * hade varit tre ställen att glömma när träffytan eller skärmläsartexten ändras (regel 4,
 * issue #90).
 *
 * En [CheckRow] med kvitteringens etikett: hela raden är klickbar, en nod med rollen kryssruta
 * (UI-3).
 *
 * Markeringen utför aldrig ett byte — den **registrerar** att användaren gjorde det, så facit
 * kan mäta följda råd separat från alla givna råd. Utan den skillnaden vore ett hypotetiskt och
 * ett verkligt utfall samma siffra.
 */
@Composable
fun FollowedToggleRow(
    followed: Boolean,
    onFollowedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    CheckRow(
        checked = followed,
        onCheckedChange = onFollowedChange,
        label = stringResource(R.string.facit_followed_label),
        labelStyle = MaterialTheme.typography.bodySmall,
        modifier = modifier
    )
}
