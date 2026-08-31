package se.partee71.fonder.ui.fondsok

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCompany
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.PullToRefreshContainer
import se.partee71.fonder.ui.components.RiskBadge
import se.partee71.fonder.ui.components.SelectField

@Composable
fun FundSearchScreen(
    modifier: Modifier = Modifier,
    onPickFund: ((Fund) -> Unit)? = null,
    viewModel: FundSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FundSearchContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onCompanySelected = viewModel::onCompanySelected,
        onAddFund = viewModel::addFund,
        onRefresh = viewModel::refresh,
        onPickFund = onPickFund,
        modifier = modifier,
    )
}

/**
 * Tillståndsdriven, testbar del av [FundSearchScreen] — inget ViewModel/Hilt-beroende, samma
 * mönster som [se.partee71.fonder.ui.portfolj.PortfoljContent]/[se.partee71.fonder.ui.fond.FondDetaljContent]
 * (utbruten i issue #85 för att kunna instrumenttesta risknivån på träffraden, UI-10).
 */
@Composable
fun FundSearchContent(
    state: FundSearchUiState,
    onQueryChange: (String) -> Unit = {},
    onCompanySelected: (FundCompany?) -> Unit = {},
    onAddFund: (Fund) -> Unit = {},
    /**
     * Satt = skärmen är en **väljare** (HEM-10, issue #102): raden erbjuder "Välj" i stället för
     * "Lägg till", och en redan bevakad fond visar ingen bock — den bocken betyder "finns i din
     * bevakning", vilket är irrelevant när man pekar ut en jämförelsefond. Null = normalläget,
     * lägg till i bevakningen.
     */
    onPickFund: ((Fund) -> Unit)? = null,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val allaFondbolag = stringResource(R.string.fondsok_company_all)

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        SelectField(
            label = stringResource(R.string.fondsok_company_label),
            options = state.companies,
            selected = state.selectedCompany,
            optionLabel = { it.name },
            onSelect = onCompanySelected,
            placeholder = allaFondbolag,
            clearOptionLabel = allaFondbolag,
        )

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.fondsok_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        // Dra ned hämtar om katalogen (UI-11). Gesten omsluter även fel- och tomlägena, och
        // inte bara träfflistan: ett nätverksfel är den vanligaste anledningen att vilja dra,
        // och det läget har ingen lista att dra i. `verticalScroll` ger dem något att gripa tag
        // i — utan en skrollbar yta har svepet inget att koppla sig till.
        PullToRefreshContainer(
            refreshing = state.refreshing,
            onRefresh = onRefresh,
        ) {
            when {
                state.loading -> LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )

                // Nätverksfel och "inga träffar" är skilda tillstånd — samma tomma vy för båda
                // fick ett trasigt nät att se ut som att fonden inte fanns (issue #78).
                state.loadFailed -> PullableMessage {
                    EmptyState(
                        title = stringResource(R.string.fondsok_load_failed_title),
                        body = stringResource(R.string.fondsok_load_failed_body),
                    )
                }

                state.results.isEmpty() -> PullableMessage {
                    EmptyState(
                        title = stringResource(R.string.fondsok_empty_title),
                        body = stringResource(R.string.fondsok_empty_body),
                    )
                }

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.results, key = { it.fundId }) { fund ->
                        FundResultRow(
                            fund = fund,
                            added = onPickFund == null && fund.fundId in state.addedFundIds,
                            riskLevel = state.riskLevels[fund.fundId],
                            actionLabelRes = if (onPickFund == null) R.string.add else R.string.fondsok_pick,
                            onAction = { onPickFund?.invoke(fund) ?: onAddFund(fund) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * En träffrad i fondsök — namn, risknivå (UI-10, issue #85) och lägg till-knapp. Risknivån är
 * en delad [RiskBadge] (regel 4) och skrivs ut som okänd när metadatacachen inte känner fonden;
 * en rad helt utan märkning hade sett ut som en fond där risken inte gällde.
 */
@Composable
private fun FundResultRow(
    fund: Fund,
    added: Boolean,
    riskLevel: Int?,
    actionLabelRes: Int,
    onAction: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(fund.name) },
        supportingContent = { RiskBadge(level = riskLevel) },
        trailingContent = {
            if (added) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.fondsok_added))
            } else {
                TextButton(onClick = onAction) {
                    Text(stringResource(actionLabelRes))
                }
            }
        },
    )
}

/**
 * Gör ett tomt-/feltillstånd svepbart (UI-11): `PullToRefreshBox` kopplar sig via nested scroll
 * och behöver därför en skrollbar yta. Utan den skulle gesten fungera överallt **utom** i just
 * det läge där man mest vill använda den — efter ett nätverksfel.
 */
@Composable
private fun PullableMessage(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        content()
    }
}
