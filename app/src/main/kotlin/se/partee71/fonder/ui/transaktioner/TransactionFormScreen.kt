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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import se.partee71.fonder.ui.components.DateField
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.SelectField

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

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.type == TransactionType.KOP,
                onClick = { viewModel.onTypeChange(TransactionType.KOP) },
                label = { Text(stringResource(R.string.transaktion_kop)) },
            )
            FilterChip(
                selected = state.type == TransactionType.SALJ,
                onClick = { viewModel.onTypeChange(TransactionType.SALJ) },
                label = { Text(stringResource(R.string.transaktion_salj)) },
            )
        }

        DateField(
            label = stringResource(R.string.transaktionsform_date_label),
            date = state.date,
            onDateChange = viewModel::onDateChange,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        OutlinedTextField(
            value = state.sharesText,
            onValueChange = viewModel::onSharesTextChange,
            label = { Text(stringResource(R.string.transaktionsform_shares_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        OutlinedTextField(
            value = state.priceText,
            onValueChange = viewModel::onPriceTextChange,
            label = { Text(stringResource(R.string.transaktionsform_price_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        OutlinedTextField(
            value = state.feeText,
            onValueChange = viewModel::onFeeTextChange,
            label = { Text(stringResource(R.string.transaktionsform_fee_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Button(
            onClick = viewModel::save,
            enabled = state.valid,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text(stringResource(R.string.save))
        }
    }
}
