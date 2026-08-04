package se.partee71.fonder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import se.partee71.fonder.R

/** Källans riskskala (TP-21) går 1–7 — visas alltid ut, så en femma inte läses som "5 av 5". */
const val RISK_SCALE_MAX = 7

/**
 * Delad märkning av en fonds risknivå (UI-10, issue #85) — samma badge överallt en fond visas
 * för sig: Fonddetaljs rubrik, varje bytesförslag, portföljens innehavsrader och fondsökens
 * träffrader. Utan den gick ett bytesförslag inte att bedöma ("byter jag upp eller ned i
 * risk?") utan att lämna kortet, trots att risknivån redan fanns i [se.partee71.fonder.domain.model.FundMetadata.risk].
 *
 * Siffran står **i text** ("Risk 5/7"), aldrig som enbart en färg: den fasta paletten (UI-1)
 * har redan grönt/gult/rött upptaget av säljsignalens trafikljus ([StatusDot]) och
 * [ProfitTakeBadge] av vinstsignalen — en färgskala till hade lästs som ännu en signal.
 * Behållaren är därför neutral, precis som talet den bär (samma resonemang som
 * [ProfitTakeBadge]s KDoc, fast omvänt: här *ska* märkningen inte betyda något utöver siffran).
 *
 * @param level fondens risknivå, eller null för okänd — då skrivs "Risk okänd" ut i stället för
 *   att märkningen tyst uteblir. En saknad siffra är information (ANA-4-principen: markera,
 *   gissa aldrig), och en rad utan badge hade sett ut som en rad där risken inte gällde.
 * @param toLevel risknivån efter ett föreslaget byte — visas som "Risk 5 → 4" så ett förslag går
 *   att bedöma direkt på raden. Null för en fond som visas för sig själv.
 */
@Composable
fun RiskBadge(level: Int?, modifier: Modifier = Modifier, toLevel: Int? = null) {
    val label = when {
        level == null -> stringResource(R.string.risk_unknown_label)
        toLevel != null -> stringResource(R.string.format_risk_level_change, level, toLevel, RISK_SCALE_MAX)
        else -> stringResource(R.string.format_risk_level, level, RISK_SCALE_MAX)
    }
    val description = when {
        level == null -> stringResource(R.string.risk_unknown_description)
        toLevel != null -> stringResource(R.string.format_risk_level_change_description, level, toLevel, RISK_SCALE_MAX)
        else -> stringResource(R.string.format_risk_level_description, level, RISK_SCALE_MAX)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
