package se.partee71.fonder.ui.transaktioner

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.SwedishNumberFormat
import se.partee71.fonder.domain.usecase.SwitchOption
import se.partee71.fonder.ui.components.ChoiceChipRow
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.SelectField
import se.partee71.fonder.ui.navigation.TransactionFormKey
import se.partee71.fonder.ui.theme.Filled
import se.partee71.fonder.ui.theme.MonoAmountStyle
import se.partee71.ui.common.EditorEvents
import se.partee71.ui.components.AppButton
import se.partee71.ui.components.AppCard
import se.partee71.ui.components.AppTextField
import se.partee71.ui.components.AppTopBar as LibraryTopBar
import se.partee71.ui.components.ButtonVariant
import se.partee71.ui.components.DateField
import se.partee71.ui.components.EntityEditScreen
import se.partee71.ui.components.TopBarSize
import se.partee71.ui.theme.Spacing
import se.partee71.ui.theme.Tone

@Composable
fun TransactionFormScreen(
    formKey: TransactionFormKey,
    onClose: () -> Unit,
    onWatchFund: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransactionFormViewModel = hiltViewModel<TransactionFormViewModel, TransactionFormViewModel.Factory> { it.create(formKey) }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TransactionFormContent(
        state = state,
        events = viewModel.events,
        onClose = onClose,
        onWatchFund = onWatchFund,
        onFundSelected = viewModel::onFundSelected,
        onTypeChange = viewModel::onTypeChange,
        onDateChange = viewModel::onDateChange,
        onSharesTextChange = viewModel::onSharesTextChange,
        onPriceTextChange = viewModel::onPriceTextChange,
        onFeeTextChange = viewModel::onFeeTextChange,
        onSwitchSelected = viewModel::onSwitchSelected,
        onSellAll = viewModel::onSellAll,
        onSave = viewModel::save,
        onRetry = viewModel::retry,
        modifier = modifier
    )
}

/** Formulärets rubrik (NAV-4, BYT-2): "Ny transaktion", eller "Registrera säljet"/"Registrera köpet" förifyllt. */
@StringRes
internal fun TransactionFormUiState.titleRes(): Int = when {
    !prefilled -> R.string.transaktionsform_title
    type == TransactionType.SALJ -> R.string.transaktionsform_title_sale
    else -> R.string.transaktionsform_title_buy
}

/**
 * Tillståndsdriven, testbar del av [TransactionFormScreen] (NAV-4, BYT-2) — inget ViewModel/Hilt-beroende.
 * Ramen är android-ui:s `EntityEditScreen` (UI-15): liten topprad med rubriken och Spara ("Spara säljet"
 * förifyllt från Följ), "Släng ändringar?" vid bakåt med osparade ändringar, sparfel som snackbar,
 * läsfel med "Försök igen" ([onRetry]) och tillbaka efter lyckad sparning ([onClose]). Ramen lägger
 * 16 dp mellan fälten.
 *
 * Från Följ står förslaget överst och fond och typ är låsta; bytesväljaren ligger sist, med en
 * förklaring medan en fråga utan förval är obesvarad. Vid sälj står innehavet under antalet med
 * "Sälj alla andelar" ([onSellAll], TRX-5). Utan bevakade fonder visas i stället ett tomt läge med
 * "Bevaka en fond" ([onWatchFund], TRX-6).
 */
@Composable
fun TransactionFormContent(
    state: TransactionFormUiState,
    events: EditorEvents,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onWatchFund: () -> Unit = {},
    onFundSelected: (Fund) -> Unit = {},
    onTypeChange: (TransactionType) -> Unit = {},
    onDateChange: (LocalDate) -> Unit = {},
    onSharesTextChange: (String) -> Unit = {},
    onPriceTextChange: (String) -> Unit = {},
    onFeeTextChange: (String) -> Unit = {},
    onSwitchSelected: (SwitchOption) -> Unit = {},
    onSellAll: () -> Unit = {},
    onSave: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    val title = stringResource(state.titleRes())
    if (state.noFunds) {
        NoFunds(title, state.fundLookupFailed, onClose, onWatchFund, modifier)
        return
    }
    val editor = state.editor
    EntityEditScreen(
        title = title,
        state = editor,
        events = events,
        onSave = onSave,
        onClose = onClose,
        modifier = modifier,
        saveLabel = stringResource(if (state.prefilled && state.type == TransactionType.SALJ) R.string.transaktionsform_save_sale else R.string.save),
        onRetry = onRetry,
        formError = if (state.linkFailed) stringResource(R.string.transaktionsform_link_failed) else null,
        horizontalMargin = Spacing.l
    ) {
        state.followIntro?.let { intro ->
            AppCard(tone = Tone.Filled, modifier = Modifier.fillMaxWidth()) {
                Text(followIntroText(intro), style = MaterialTheme.typography.bodyMedium)
            }
        }

        SelectField(
            label = stringResource(R.string.transaktionsform_fund_label),
            options = state.funds,
            selected = state.selectedFund,
            optionLabel = { it.name },
            onSelect = { fund -> fund?.let(onFundSelected) },
            enabled = !state.fundLocked,
            supportingText = when {
                state.fundLookupFailed -> stringResource(R.string.transaktionsform_fund_not_found)
                else -> editor.errorFor(TRANSACTION_FUND)?.let { stringResource(it) }
            }
        )

        ChoiceChipRow(
            options = TransactionType.entries,
            selected = state.type,
            optionLabel = { type -> stringResource(type.labelRes()) },
            onSelect = onTypeChange,
            enabled = !state.typeLocked
        )

        DateField(
            label = stringResource(R.string.transaktionsform_date_label),
            date = state.date.toKotlinLocalDate(),
            onDateChange = { onDateChange(it.toJavaLocalDate()) }
        )

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                val sharesError = editor.errorFor(TRANSACTION_SHARES)
                AppTextField(
                    value = state.sharesText,
                    onValueChange = onSharesTextChange,
                    label = stringResource(R.string.transaktionsform_shares_label),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    error = sharesError?.let { stringResource(it) },
                    modifier = Modifier.weight(1f)
                )
                val priceError = editor.errorFor(TRANSACTION_PRICE)
                AppTextField(
                    value = state.priceText,
                    onValueChange = onPriceTextChange,
                    label = stringResource(R.string.transaktionsform_price_label),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    error = priceError?.let { stringResource(it) },
                    modifier = Modifier.weight(1f)
                )
            }
            state.prefillAmountKr?.let { amount ->
                Text(
                    stringResource(R.string.format_transaktionsform_prefill_note, MoneyFormat.kr(amount)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.ownedShares?.let { owned -> OwnedSharesRow(owned, onSellAll) }
        }

        val feeError = editor.errorFor(TRANSACTION_FEE)
        AppTextField(
            value = state.feeText,
            onValueChange = onFeeTextChange,
            label = stringResource(R.string.transaktionsform_fee_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            error = feeError?.let { stringResource(it) }
        )

        state.switchField?.let { field ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SwitchDecisionField(field = field, fundName = state.selectedFund?.name, onSelect = onSwitchSelected)
                if (state.blockedBySwitch) {
                    Text(
                        stringResource(R.string.transaktionsform_save_blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Innehavet vid sälj och "Sälj alla andelar" (TRX-5); utan andelar finns inget att sälja och ingen knapp. */
@Composable
private fun OwnedSharesRow(owned: Double, onSellAll: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            stringResource(R.string.format_transaktionsform_owned, SwedishNumberFormat.formatShares(owned)),
            style = MonoAmountStyle.merge(MaterialTheme.typography.bodySmall),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (owned > 0.0) {
            AppButton(stringResource(R.string.transaktionsform_sell_all), onSellAll, variant = ButtonVariant.Outlined)
        }
    }
}

/**
 * Inga bevakade fonder att välja bland (TRX-6): ramens lilla topprad med bakåt, utan Spara – det finns
 * inget att spara – och ett tomt läge med vägen till fondsök.
 */
@Composable
private fun NoFunds(title: String, fundLookupFailed: Boolean, onClose: () -> Unit, onWatchFund: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        LibraryTopBar(title, size = TopBarSize.Small, onBack = onClose)
        EmptyState(
            title = stringResource(R.string.transaktionsform_no_funds_title),
            body = stringResource(if (fundLookupFailed) R.string.transaktionsform_fund_not_found_no_funds else R.string.transaktionsform_no_funds_body),
            modifier = Modifier.weight(1f),
            icon = Icons.Outlined.Search,
            action = { AppButton(stringResource(R.string.transaktionsform_watch_fund), onWatchFund) }
        )
    }
}

@Composable
private fun followIntroText(intro: FollowIntro): String {
    val amount = intro.amountKr ?: return stringResource(R.string.format_transaktionsform_follow_no_amount, intro.buyFundName)
    return when (val planIndex = intro.planIndex) {
        null -> stringResource(R.string.format_transaktionsform_follow_cheaper, MoneyFormat.kr(amount), intro.buyFundName)
        else -> stringResource(R.string.format_transaktionsform_follow_plan, planIndex + 1, MoneyFormat.kr(amount), intro.buyFundName)
    }
}
