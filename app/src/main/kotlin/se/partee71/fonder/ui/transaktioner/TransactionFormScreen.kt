package se.partee71.fonder.ui.transaktioner

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.ui.components.AppButton
import se.partee71.fonder.ui.components.AppTextField
import se.partee71.fonder.ui.components.ChoiceChipRow
import se.partee71.fonder.ui.components.DateField
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.SelectField
import se.partee71.fonder.ui.transaktioner.labelRes

@Composable
fun TransactionFormScreen(
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransactionFormViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    if (state.funds.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.transaktionsform_no_funds_title),
            body = stringResource(R.string.transaktionsform_no_funds_body),
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SelectField(
            label = stringResource(R.string.transaktionsform_fund_label),
            options = state.funds,
            selected = state.selectedFund,
            optionLabel = { it.name },
            onSelect = { fund -> fund?.let(viewModel::onFundSelected) },
        )

        ChoiceChipRow(
            options = TransactionType.entries,
            selected = state.type,
            optionLabel = { type -> stringResource(type.labelRes()) },
            onSelect = viewModel::onTypeChange,
            modifier = Modifier.padding(top = 8.dp),
        )

        DateField(
            label = stringResource(R.string.transaktionsform_date_label),
            date = state.date,
            onDateChange = viewModel::onDateChange,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        AppTextField(
            value = state.sharesText,
            onValueChange = viewModel::onSharesTextChange,
            label = stringResource(R.string.transaktionsform_shares_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        AppTextField(
            value = state.priceText,
            onValueChange = viewModel::onPriceTextChange,
            label = stringResource(R.string.transaktionsform_price_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        AppTextField(
            value = state.feeText,
            onValueChange = viewModel::onFeeTextChange,
            label = stringResource(R.string.transaktionsform_fee_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        AppButton(
            text = stringResource(R.string.save),
            onClick = viewModel::save,
            enabled = state.valid,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }
}
