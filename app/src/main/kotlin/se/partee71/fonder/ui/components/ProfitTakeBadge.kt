package se.partee71.fonder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
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
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.theme.ReturnColors

/**
 * Liten, fristående markering för vinstsignalen (S4, `FundAnalysisCalc.ProfitTakeSignal`,
 * ANA-8, issue #26) — medvetet skild från [StatusDot]/risktrafikljuset (grön/gul/röd)
 * eftersom en stor orealiserad vinst är en möjlighet, inte en risk. Den fasta paletten
 * (UI-1) har redan både mässingsaccenten (risksignalens gula, `StatusColors.gul`) och
 * [ReturnColors.gain] (risksignalens gröna, `StatusColors.gron`) upptagna av risknivåerna
 * — en ren färgad prick i endera färgen skulle se ut som en befintlig risknivå och
 * förvirra. Ikon + kort textetikett gör den visuellt skild utan att kräva en ny
 * hårdkodad färg. Återanvänds mellan Portfölj (POR-8) och rimligen Fonddetalj/Hem.
 * Aldrig ett köp-/säljråd (samma princip som ANA-3) — bara en neutral markering.
 */
@Composable
fun ProfitTakeBadge(gainFraction: Double, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.analys_profit_take_badge)
    val description = stringResource(R.string.format_analys_profit_take_description, MoneyFormat.percentSigned(gainFraction))
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(ReturnColors.gain.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.TrendingUp,
            contentDescription = null,
            tint = ReturnColors.gain,
            modifier = Modifier.size(14.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = ReturnColors.gain,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
