package se.partee71.fonder.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.theme.ReturnColors
import se.partee71.ui.components.InfoPill
import se.partee71.ui.theme.AppTheme
import se.partee71.ui.theme.ToneColors

/**
 * Liten, fristående markering för vinstsignalen (S4, `FundAnalysisCalc.ProfitTakeSignal`,
 * ANA-8, issue #26) — medvetet skild från [StatusDot]/risktrafikljuset (grön/gul/röd)
 * eftersom en stor orealiserad vinst är en möjlighet, inte en risk. Den fasta paletten
 * (UI-1) har redan både kopparaccenten (risksignalens gula, `StatusColors.gul`) och
 * [ReturnColors.gain] (risksignalens gröna, `StatusColors.gron`) upptagna av risknivåerna
 * — en ren färgad prick i endera färgen skulle se ut som en befintlig risknivå och
 * förvirra. Ikon + kort textetikett gör den visuellt skild utan att kräva en ny
 * hårdkodad färg. Återanvänds mellan Portfölj (POR-8) och rimligen Fonddetalj/Hem.
 * Aldrig ett köp-/säljråd (samma princip som ANA-3) — bara en neutral markering. Byggd på
 * android-ui:s [InfoPill] i vinstens kulör på en svag vinsttonad behållare, i märkenas 8 dp-form.
 */
@Composable
fun ProfitTakeBadge(gainFraction: Double, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.analys_profit_take_badge)
    val description = stringResource(R.string.format_analys_profit_take_description, MoneyFormat.percentSigned(gainFraction))
    InfoPill(
        text = label,
        modifier = modifier,
        icon = R.drawable.ic_trending_up,
        colors = ToneColors(container = ReturnColors.gain.copy(alpha = 0.16f), content = ReturnColors.gain),
        shape = AppTheme.shapes.smallTile,
        contentDescription = description
    )
}
