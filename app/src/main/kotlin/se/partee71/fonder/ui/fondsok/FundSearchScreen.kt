package se.partee71.fonder.ui.fondsok

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCompany
import se.partee71.fonder.ui.components.EmptyState

@Composable
fun FundSearchScreen(
    modifier: Modifier = Modifier,
    viewModel: FundSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        FondbolagDropdown(
            companies = state.companies,
            selected = state.selectedCompany,
            onSelect = viewModel::onCompanySelected,
        )

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            label = { Text(stringResource(R.string.fondsok_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        when {
            state.loading -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )

            state.results.isEmpty() -> EmptyState(
                title = stringResource(R.string.fondsok_empty_title),
                body = stringResource(R.string.fondsok_empty_body),
            )

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.results, key = { it.fundId }) { fund ->
                    FundResultRow(
                        fund = fund,
                        added = fund.fundId in state.addedFundIds,
                        onAdd = { viewModel.addFund(fund) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FondbolagDropdown(
    companies: List<FundCompany>,
    selected: FundCompany?,
    onSelect: (FundCompany?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.name ?: stringResource(R.string.fondsok_company_all),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.fondsok_company_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.fondsok_company_all)) },
                onClick = { onSelect(null); expanded = false },
            )
            companies.forEach { company ->
                DropdownMenuItem(
                    text = { Text(company.name) },
                    onClick = { onSelect(company); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun FundResultRow(fund: Fund, added: Boolean, onAdd: () -> Unit) {
    ListItem(
        headlineContent = { Text(fund.name) },
        trailingContent = {
            if (added) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.fondsok_added))
            } else {
                TextButton(onClick = onAdd) {
                    Text(stringResource(R.string.add))
                }
            }
        },
    )
}
