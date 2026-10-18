package se.partee71.fonder.ui.transaktioner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.SwitchOption
import se.partee71.fonder.ui.components.AppButton
import se.partee71.fonder.ui.components.AppCard
import se.partee71.fonder.ui.components.AppCardVariant
import se.partee71.fonder.ui.components.AppTextField
import se.partee71.fonder.ui.components.ChoiceChipRow
import se.partee71.fonder.ui.components.DateField
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.SelectField
import se.partee71.fonder.ui.navigation.TransactionFormKey

@Composable
fun TransactionFormScreen(
    formKey: TransactionFormKey,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransactionFormViewModel = hiltViewModel<TransactionFormViewModel, TransactionFormViewModel.Factory> { it.create(formKey) }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val notifySaved by rememberUpdatedState(onSaved)
    LaunchedEffect(state.saved) {
        if (state.saved) {
            notifySaved()
            viewModel.onSavedHandled()
        }
    }

    TransactionFormContent(
        state = state,
        onFundSelected = viewModel::onFundSelected,
        onTypeChange = viewModel::onTypeChange,
        onDateChange = viewModel::onDateChange,
        onSharesTextChange = viewModel::onSharesTextChange,
        onPriceTextChange = viewModel::onPriceTextChange,
        onFeeTextChange = viewModel::onFeeTextChange,
        onSwitchSelected = viewModel::onSwitchSelected,
        onSave = viewModel::save,
        modifier = modifier
    )
}

/**
 * Tillståndsdriven, testbar del av [TransactionFormScreen] (NAV-4, BYT-2) — inget ViewModel/Hilt-beroende.
 * Från Följ står förslaget överst och fond och typ är låsta; bytesväljaren ligger sist före Spara,
 * som är släckt med en förklaring tills en fråga utan förval besvarats.
 */
@Composable
fun TransactionFormContent(
    state: TransactionFormUiState,
    modifier: Modifier = Modifier,
    onFundSelected: (Fund) -> Unit = {},
    onTypeChange: (TransactionType) -> Unit = {},
    onDateChange: (LocalDate) -> Unit = {},
    onSharesTextChange: (String) -> Unit = {},
    onPriceTextChange: (String) -> Unit = {},
    onFeeTextChange: (String) -> Unit = {},
    onSwitchSelected: (SwitchOption) -> Unit = {},
    onSave: () -> Unit = {}
) {
    if (state.funds.isEmpty() && (!state.prefilled || state.fundLookupFailed)) {
        // En fond ur nyckeln som inte gick att slå upp, och inga bevakade fonder att välja bland:
        // ett formulär som aldrig kan sparas — säg varför i stället.
        EmptyState(
            title = stringResource(R.string.transaktionsform_no_funds_title),
            body = stringResource(
                if (state.fundLookupFailed) R.string.transaktionsform_fund_not_found_no_funds else R.string.transaktionsform_no_funds_body
            ),
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        state.followIntro?.let { intro ->
            AppCard(variant = AppCardVariant.Filled, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
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
                state.fundLoading -> stringResource(R.string.transaktionsform_fund_loading)
                state.fundLookupFailed -> stringResource(R.string.transaktionsform_fund_not_found)
                else -> null
            }
        )

        ChoiceChipRow(
            options = TransactionType.entries,
            selected = state.type,
            optionLabel = { type -> stringResource(type.labelRes()) },
            onSelect = onTypeChange,
            enabled = !state.typeLocked,
            modifier = Modifier.padding(top = 8.dp)
        )

        DateField(
            label = stringResource(R.string.transaktionsform_date_label),
            date = state.date,
            onDateChange = onDateChange,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            AppTextField(
                value = state.sharesText,
                onValueChange = onSharesTextChange,
                label = stringResource(R.string.transaktionsform_shares_label),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            AppTextField(
                value = state.priceText,
                onValueChange = onPriceTextChange,
                label = stringResource(R.string.transaktionsform_price_label),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }
        state.prefillAmountKr?.let { amount ->
            Text(
                stringResource(R.string.format_transaktionsform_prefill_note, MoneyFormat.kr(amount)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        AppTextField(
            value = state.feeText,
            onValueChange = onFeeTextChange,
            label = stringResource(R.string.transaktionsform_fee_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        state.switchField?.let { field ->
            SwitchDecisionField(
                field = field,
                fundName = state.selectedFund?.name,
                onSelect = onSwitchSelected,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        AppButton(
            text = stringResource(
                if (state.prefilled && state.type == TransactionType.SALJ) R.string.transaktionsform_save_sale else R.string.save
            ),
            onClick = onSave,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        if (state.blockedBySwitch) {
            Text(
                stringResource(R.string.transaktionsform_save_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (state.linkFailed) {
            Text(
                stringResource(R.string.transaktionsform_link_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
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
