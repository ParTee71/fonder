package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundTag
import se.partee71.fonder.domain.model.SuggestionCandidate
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.theme.Filled
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.fonder.ui.theme.ReturnColors
import se.partee71.fonder.ui.theme.nestedCardStyle
import se.partee71.ui.components.AppCard
import se.partee71.ui.components.InfoPill
import se.partee71.ui.components.ItemRow
import se.partee71.ui.theme.AppTheme
import se.partee71.ui.theme.Spacing
import se.partee71.ui.theme.Tone

/**
 * En fonds fakta för [FundFactsCard] och [FundFactsRow] (BYT-5, issue #240) — bara primitiver, så
 * kortet inte vet om de kommer ur metadatacachen ([of] med [FundMetadata]) eller ur ett sparat
 * förslag ([of] med [SuggestionCandidate]). Null = okänt, aldrig 0 (ANA-4-principen).
 *
 * @param totalFeePercent årlig avgift i procent (0,20 = 0,20 %), som [FundMetadata.totalFee].
 * @param oneYearFraction utvecklingen senaste året som andel (0,039 = +3,9 %).
 * @param type fondens sort i klartext ("Räntefond, Korta räntor") — aldrig källans transportkod
 *   ([FundMetadata.fundType] är `EQUITY_FUND` m.fl., se [se.partee71.fonder.domain.usecase.PortfolioExposureCalc]).
 */
@Immutable
data class FundFacts(
    val name: String,
    val risk: Int? = null,
    val totalFeePercent: Double? = null,
    val oneYearFraction: Double? = null,
    val type: String? = null,
    val company: String? = null
) {
    companion object {
        /** Typen ur källans `TYPE`-tagg och kategorin, i den ordningen. */
        fun of(metadata: FundMetadata): FundFacts = FundFacts(
            name = metadata.name,
            risk = metadata.risk,
            totalFeePercent = metadata.totalFee,
            oneYearFraction = metadata.developmentOneYear,
            type = listOfNotNull(metadata.tags.firstOrNull { it.category == FundTag.CATEGORY_TYPE }?.title, metadata.category)
                .distinct()
                .joinToString(", ")
                .ifEmpty { null },
            company = metadata.companyName
        )

        /** Kandidatens kategori är typen — dess `fundType` är källans transportkod och visas inte. */
        fun of(candidate: SuggestionCandidate): FundFacts = FundFacts(
            name = candidate.name,
            risk = candidate.risk,
            totalFeePercent = candidate.totalFee,
            oneYearFraction = candidate.developmentOneYear,
            type = candidate.category,
            company = candidate.companyName
        )
    }
}

/**
 * Faktakortet om en fond (BYT-5, issue #240, mockup "C · Infokort"): namnet med ett valfritt märke
 * ("Vårt förslag"), ett rutnät med risk, avgift per år, senaste året, typ och fondbolag, och en
 * valfri rad om varför fonden valts. Ett tonat kort inne i ett kort eller ett bottenark, med
 * [PeriodRow] för varje rad (regel 4) — senaste året färgas som vinst eller förlust, ett saknat värde
 * står som "okänt" i stället för att raden tas bort, så att kortet alltid har samma form.
 */
@Composable
fun FundFactsCard(
    facts: FundFacts,
    modifier: Modifier = Modifier,
    badge: String? = null,
    reason: String? = null
) {
    val unknown = stringResource(R.string.fund_facts_unknown)
    AppCard(modifier = modifier.fillMaxWidth(), tone = Tone.Filled, style = nestedCardStyle) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(facts.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            badge?.let { InfoPill(text = it, tone = Tone.Primary, shape = AppTheme.shapes.smallTile) }
        }
        Column(modifier = Modifier.padding(top = Spacing.xs)) {
            FactRow(stringResource(R.string.fund_facts_risk), facts.risk?.let { stringResource(R.string.format_fund_facts_risk, it) }, unknown)
            FactRow(stringResource(R.string.fund_facts_fee), facts.totalFeePercent?.let { MoneyFormat.feePercent(it) }, unknown)
            PeriodRow(
                label = stringResource(R.string.fund_facts_one_year),
                amount = null,
                fraction = facts.oneYearFraction,
                insufficientText = unknown,
                modifier = Modifier.padding(top = Spacing.xs)
            )
            FactRow(stringResource(R.string.fund_facts_type), facts.type, unknown)
            FactRow(stringResource(R.string.fund_facts_company), facts.company, unknown)
        }
        reason?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.s)
            )
        }
    }
}

@Composable
private fun FactRow(label: String, value: String?, unknown: String) {
    PeriodRow(
        label = label,
        amount = null,
        fraction = null,
        valueText = value,
        insufficientText = unknown,
        modifier = Modifier.padding(top = Spacing.xs)
    )
}

/**
 * En fond i en lista med alternativ (BYT-5): namnet, "Risk 2 av 7 · Räntefond" under och avgiften per
 * år över utvecklingen senaste året till höger — kolumnerna förklaras av anroparen. En
 * [ItemRow] som läses i ett svep; saknade värden står som "okänt".
 */
@Composable
fun FundFactsRow(facts: FundFacts, modifier: Modifier = Modifier) {
    val unknown = stringResource(R.string.fund_facts_unknown)
    val oneYear = facts.oneYearFraction
    ItemRow(
        title = facts.name,
        modifier = modifier.semantics(mergeDescendants = true) {},
        subtitle = listOfNotNull(facts.risk?.let { stringResource(R.string.format_fund_facts_risk_short, it) }, facts.type)
            .joinToString(" · ")
            .ifEmpty { null },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    facts.totalFeePercent?.let { MoneyFormat.feePercent(it) } ?: unknown,
                    style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium)
                )
                Text(
                    oneYear?.let { MoneyFormat.percentSigned(it) } ?: unknown,
                    style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium),
                    color = oneYear?.let { ReturnColors.forAmount(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}
