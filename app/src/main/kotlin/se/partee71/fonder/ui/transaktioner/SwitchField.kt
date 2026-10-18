package se.partee71.fonder.ui.transaktioner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FollowedSuggestion
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.SwitchChoicesSource
import se.partee71.fonder.domain.usecase.SwitchDecision
import se.partee71.fonder.domain.usecase.SwitchOption
import se.partee71.fonder.domain.usecase.SwitchQuestion
import se.partee71.fonder.ui.components.SelectField

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** Vad ett alternativ i bytesväljaren heter — data, inte text, så etiketten slås upp i `strings.xml`. */
sealed interface SwitchOptionLabel {
    data object NoSwitch : SwitchOptionLabel
    data object Standalone : SwitchOptionLabel
    data class PlanRow(val planIndex: Int, val sellFundName: String, val buyFundName: String) : SwitchOptionLabel
    data class Cheaper(val sellFundName: String, val buyFundName: String) : SwitchOptionLabel
    data class Watch(val sellFundName: String, val soldAtEpochDay: Long, val proceedsKr: Double?) : SwitchOptionLabel
    data class OtherDay(val sellFundName: String, val soldAtEpochDay: Long) : SwitchOptionLabel
    data class SaleRow(val sellFundName: String) : SwitchOptionLabel
}

data class SwitchOptionUi(val option: SwitchOption, val label: SwitchOptionLabel)

/**
 * Bytesväljaren (BYT-2) för en transaktion, klar att rita — formuläret (NAV-4) och PDF-importen
 * (IMP-6) visar samma fält med samma texter. [selected] null = frågan är obesvarad, och då är
 * Spara släckt.
 */
@Immutable
data class SwitchFieldUi(
    val type: TransactionType,
    val options: ImmutableList<SwitchOptionUi>,
    val selected: SwitchOptionUi?
) {
    val unanswered: Boolean get() = selected == null

    /** Bara "Inget byte" — inget att fråga om, fältet visas inte. */
    val hidden: Boolean get() = options.size <= 1

    /** Beslutet som sparas — "Inget byte" för en dold väljare. */
    val option: SwitchOption? get() = selected?.option

    companion object {
        /**
         * Fältet för [question] med användarens val [chosen]. [saleRowFundName] ger fondnamnet för
         * en säljrad i samma import ([SwitchDecision.ClosesWatchOfEntry]).
         */
        fun of(
            type: TransactionType,
            question: SwitchQuestion,
            chosen: SwitchOption?,
            inputs: SwitchChoicesSource.Inputs,
            followed: FollowedSuggestion? = null,
            saleRowFundName: (Int) -> String? = { null }
        ): SwitchFieldUi {
            val options = question.options.map { SwitchOptionUi(it, labelFor(it, inputs, followed, saleRowFundName)) }
            val selected = question.resolve(chosen)
            return SwitchFieldUi(type, options.toImmutableList(), options.firstOrNull { it.option == selected })
        }

        private fun labelFor(
            option: SwitchOption,
            inputs: SwitchChoicesSource.Inputs,
            followed: FollowedSuggestion?,
            saleRowFundName: (Int) -> String?
        ): SwitchOptionLabel = when (option) {
            is SwitchOption.LinkOtherDay -> inputs.openWatches.firstOrNull { it.id == option.watchId }
                ?.let { SwitchOptionLabel.OtherDay(it.sellFundName, it.soldAtEpochDay) }
                ?: SwitchOptionLabel.NoSwitch

            is SwitchOption.Decide -> when (val decision = option.decision) {
                SwitchDecision.None -> SwitchOptionLabel.NoSwitch

                SwitchDecision.Standalone -> SwitchOptionLabel.Standalone

                is SwitchDecision.LinksWatch -> inputs.openWatches.firstOrNull { it.id == decision.watchId }
                    ?.let { SwitchOptionLabel.OtherDay(it.sellFundName, it.soldAtEpochDay) }
                    ?: SwitchOptionLabel.NoSwitch

                is SwitchDecision.ForSuggestion -> (inputs.plan + inputs.cheaperAlternatives)
                    .firstOrNull { it.recordId == decision.recordId }
                    ?.let { row ->
                        if (row.kind == SuggestionKind.RISK_PLAN) {
                            SwitchOptionLabel.PlanRow(row.planIndex, row.sellFundName, row.buyFundName)
                        } else {
                            SwitchOptionLabel.Cheaper(row.sellFundName, row.buyFundName)
                        }
                    }
                    ?: followed?.takeIf { it.recordId == decision.recordId }?.let { row ->
                        row.planIndex?.let { SwitchOptionLabel.PlanRow(it, row.sellFundName, row.buyFundName) }
                            ?: SwitchOptionLabel.Cheaper(row.sellFundName, row.buyFundName)
                    }
                    ?: SwitchOptionLabel.Standalone

                is SwitchDecision.ClosesWatch -> inputs.openWatches.firstOrNull { it.id == decision.watchId }
                    ?.let { SwitchOptionLabel.Watch(it.sellFundName, it.soldAtEpochDay, it.proceedsKr) }
                    ?: SwitchOptionLabel.NoSwitch

                is SwitchDecision.ClosesWatchOfEntry -> SwitchOptionLabel.SaleRow(saleRowFundName(decision.entryIndex).orEmpty())
            }
        }
    }
}

@Composable
fun switchOptionText(label: SwitchOptionLabel): String = when (label) {
    SwitchOptionLabel.NoSwitch -> stringResource(R.string.switch_option_none)

    SwitchOptionLabel.Standalone -> stringResource(R.string.switch_option_standalone)

    is SwitchOptionLabel.PlanRow -> stringResource(R.string.format_switch_option_plan, label.planIndex + 1, label.sellFundName, label.buyFundName)

    is SwitchOptionLabel.Cheaper -> stringResource(R.string.format_switch_option_cheaper, label.sellFundName, label.buyFundName)

    is SwitchOptionLabel.Watch -> {
        val sold = LocalDate.ofEpochDay(label.soldAtEpochDay).format(dateFormatter)
        label.proceedsKr?.let { stringResource(R.string.format_switch_option_watch_amount, label.sellFundName, sold, MoneyFormat.kr(it)) }
            ?: stringResource(R.string.format_switch_option_watch, label.sellFundName, sold)
    }

    is SwitchOptionLabel.OtherDay -> stringResource(
        R.string.format_switch_option_other_day,
        label.sellFundName,
        LocalDate.ofEpochDay(label.soldAtEpochDay).format(dateFormatter)
    )

    is SwitchOptionLabel.SaleRow -> stringResource(R.string.format_switch_option_sale_row, label.sellFundName)
}

/** Förklaringen under fältet: vad valet gör, eller varför frågan ställs. */
@Composable
private fun switchHelpText(field: SwitchFieldUi, fundName: String?): String? {
    val selected = field.selected
    if (selected == null) {
        val otherDay = field.options.firstNotNullOfOrNull { it.label as? SwitchOptionLabel.OtherDay }
        return when {
            field.type == TransactionType.KOP && field.options.size == 2 && fundName != null ->
                stringResource(R.string.format_switch_help_buy_not_candidate, fundName)

            field.type == TransactionType.KOP -> stringResource(R.string.switch_help_buy_required)

            otherDay != null -> stringResource(
                R.string.format_switch_help_other_day,
                LocalDate.ofEpochDay(otherDay.soldAtEpochDay).format(dateFormatter)
            )

            else -> stringResource(R.string.switch_help_sale_several_rows)
        }
    }
    return when (val option = selected.option) {
        is SwitchOption.LinkOtherDay -> stringResource(R.string.switch_help_link_other_day)

        is SwitchOption.Decide -> when (option.decision) {
            is SwitchDecision.LinksWatch -> stringResource(R.string.switch_help_link_other_day)

            is SwitchDecision.ForSuggestion -> stringResource(R.string.switch_help_sale_for_suggestion)

            SwitchDecision.Standalone -> stringResource(R.string.switch_help_sale_standalone)

            is SwitchDecision.ClosesWatch, is SwitchDecision.ClosesWatchOfEntry -> stringResource(R.string.switch_help_buy_closes)

            SwitchDecision.None -> when {
                field.type == TransactionType.KOP -> null

                field.options.none { it.label is SwitchOptionLabel.PlanRow || it.label is SwitchOptionLabel.Cheaper } ->
                    stringResource(R.string.switch_help_sale_not_in_plan)

                else -> null
            }
        }
    }
}

/**
 * Bytesväljaren (BYT-2): den delade [SelectField] med etiketten "Byte" vid sälj och "Hör till
 * byte" vid köp, "Välj ett svar" när frågan saknar förval, och förklaringen som stödtext — så
 * skärmläsaren läser fältets namn, valet och vad valet gör i ett svep. Ritar ingenting när det
 * bara finns "Inget byte".
 */
@Composable
fun SwitchDecisionField(
    field: SwitchFieldUi,
    fundName: String?,
    onSelect: (SwitchOption) -> Unit,
    modifier: Modifier = Modifier
) {
    if (field.hidden) return
    // SelectFields etikett är ingen composable — texterna slås upp här, en gång per alternativ.
    val texts = field.options.associateWith { switchOptionText(it.label) }
    SelectField(
        label = stringResource(if (field.type == TransactionType.SALJ) R.string.switch_field_sale_label else R.string.switch_field_buy_label),
        options = field.options,
        selected = field.selected,
        optionLabel = { texts.getValue(it) },
        onSelect = { picked -> picked?.let { onSelect(it.option) } },
        placeholder = stringResource(R.string.switch_field_required_placeholder),
        supportingText = switchHelpText(field, fundName),
        modifier = modifier
    )
}
