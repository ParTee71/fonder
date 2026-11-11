package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.PortfolioRiskCalc
import se.partee71.fonder.domain.usecase.RiskSummary
import se.partee71.fonder.domain.usecase.SwitchEffectCalc
import se.partee71.ui.components.AppCard
import se.partee71.ui.theme.Spacing

/**
 * Riskkortet (HEM-7, issue #238, mockup C2) — delat av Översikt och Beslut (issue #240, regel 4):
 * läget i en mening, en stapel per risknivå med **målstreck** ([ExposureBar] med `target`) och hur
 * mycket bytesplanen flyttar. Bara nivåer som finns i målet eller innehavet visas. En nivå med minst
 * 5 procentenheter för mycket ritas i koppar med målstrecket i blått, de andra tvärtom — samma gräns
 * som planen säljer vid.
 *
 * @param portfolioRisk Översiktens snittrader: det värdeviktade snittet
 *   ([SwitchEffectCalc.Result.weightedTargetLevel] mot innehavens
 *   [PortfolioRiskCalc.Result.weightedAverageRisk], utan likviden på väg) med sitt förbehåll — en
 *   enda skalär kan inte skilja en 50/50-mix av nivå 1 och 6 från 100 % nivå 3,5, se
 *   [PortfolioRiskCalc]s precisionsanmärkning. Null = inga snittrader (Beslut, där staplarna och
 *   råden räcker).
 * @param goal målets namn ("Balanserad tillväxt") — inleder skalförklaringen med "Ditt mål är …".
 *   Null = bara skalförklaringen, som på Översikt.
 * @param footer något under kortets innehåll, t.ex. Besluts "Ändra mål".
 */
@Composable
fun RiskCard(
    risk: RiskSummary,
    working: Boolean,
    modifier: Modifier = Modifier,
    portfolioRisk: PortfolioRiskCalc.Result? = null,
    goal: String? = null,
    footer: (@Composable () -> Unit)? = null
) {
    val effect = risk.effect
    AppCard(modifier = modifier.fillMaxWidth().padding(Spacing.l)) {
        CardTitleRow(title = stringResource(R.string.hem_risk_title), working = working)
        effect.direction?.let { direction ->
            Text(
                stringResource(direction.headlineRes()),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = Spacing.s)
            )
        }
        val scale = stringResource(R.string.hem_risk_scale)
        Text(
            goal?.let { stringResource(R.string.format_risk_card_goal, it) + " " + scale } ?: scale,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.xs)
        )
        effect.levels.forEach { level ->
            val over = level.isOverweight
            ExposureBar(
                label = stringResource(R.string.format_hem_risk_level, level.level),
                fraction = level.actualFraction,
                target = level.targetFraction,
                color = if (over) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                targetColor = if (over) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
        }
        val toMove = effect.totalToMoveKr
        if (toMove != null && effect.direction != SwitchEffectCalc.Direction.IN_LINE) {
            // "Så är du framme" bara när planen faktiskt når målet — den kan sluta före (högst tre byten,
            // ingen köpbar kandidat, räknad mot gårdagens kurser).
            val moveRes = if (effect.reachesTargetAfterPlan == true) R.string.format_hem_risk_move else R.string.format_hem_risk_move_partial
            Text(
                stringResource(moveRes, MoneyFormat.krWhole(toMove)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spacing.xs)
            )
        }
        portfolioRisk?.let { AverageRows(effect, it) }
        footer?.invoke()
    }
}

@Composable
private fun AverageRows(effect: SwitchEffectCalc.Result, portfolioRisk: PortfolioRiskCalc.Result) = Column {
    PeriodRow(
        label = stringResource(R.string.hem_risk_target_label),
        amount = null,
        fraction = null,
        valueText = MoneyFormat.decimal(effect.weightedTargetLevel),
        modifier = Modifier.padding(top = Spacing.l)
    )
    PeriodRow(
        label = stringResource(R.string.hem_risk_actual_label),
        amount = null,
        fraction = null,
        // Innehaven själva, utan likviden på väg — det är vad etiketten och förklaringen säger.
        valueText = portfolioRisk.weightedAverageRisk?.let { MoneyFormat.decimal(it) },
        modifier = Modifier.padding(top = Spacing.xs)
    )
    Text(
        stringResource(R.string.hem_risk_explain),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs)
    )
    if (portfolioRisk.excludedCount > 0) {
        Text(
            stringResource(R.string.format_hem_risk_excluded, portfolioRisk.excludedCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs)
        )
    }
}

/** Riskkortets rubrikmening för läget (HEM-7). */
private fun SwitchEffectCalc.Direction.headlineRes(): Int = when (this) {
    SwitchEffectCalc.Direction.TOO_MUCH_RISK -> R.string.hem_risk_direction_too_much
    SwitchEffectCalc.Direction.TOO_LITTLE_RISK -> R.string.hem_risk_direction_too_little
    SwitchEffectCalc.Direction.MIXED -> R.string.hem_risk_direction_mixed
    SwitchEffectCalc.Direction.IN_LINE -> R.string.hem_risk_direction_in_line
}
