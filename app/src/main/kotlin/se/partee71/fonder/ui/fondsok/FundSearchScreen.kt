package se.partee71.fonder.ui.fondsok

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCompany
import se.partee71.fonder.ui.components.RiskBadge
import se.partee71.fonder.ui.components.SelectField
import se.partee71.fonder.ui.theme.ListFrameCards
import se.partee71.ui.components.AppButton
import se.partee71.ui.components.AppTextField
import se.partee71.ui.components.ButtonVariant
import se.partee71.ui.components.EmptyContent
import se.partee71.ui.components.EntityListScreen
import se.partee71.ui.components.ItemRow
import se.partee71.ui.components.TopBarSize
import se.partee71.ui.theme.Spacing

/**
 * Fondsök (NAV-3) på android-ui:s listram `EntityListScreen` – med [title] och [onBack] i ramens lilla
 * topprad, eftersom samma skärm är bevakningens "Bevaka ny fond", jämförelsefondens väljare (HEM-10) och
 * Pågående bytes eget alternativ (ANA-13).
 */
@Composable
fun FundSearchScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPickFund: ((Fund) -> Unit)? = null,
    viewModel: FundSearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FundSearchContent(
        state = state,
        title = title,
        onBack = onBack,
        onQueryChange = { viewModel.onEvent(FundSearchEvent.QueryChange(it)) },
        onCompanySelect = { viewModel.onEvent(FundSearchEvent.CompanySelected(it)) },
        onAddFund = { viewModel.onEvent(FundSearchEvent.AddFund(it)) },
        onRefresh = { viewModel.onEvent(FundSearchEvent.Refresh) },
        onRetry = { viewModel.onEvent(FundSearchEvent.Retry) },
        onPickFund = onPickFund,
        modifier = modifier
    )
}

/**
 * Tillståndsdriven, testbar del av [FundSearchScreen] — inget ViewModel/Hilt-beroende. Fondbolag och
 * sökfält står fast under toppraden i alla lägen (ramens `filter`); träffarna står som rader i ett kort
 * med risknivån under namnet (UI-10). Laddning, "Inga fonder hittades" och fel med "Försök igen"
 * ([onRetry]) är ramens lägen (NFR-6), och dra ned hämtar om katalogen (UI-11, [onRefresh]). Ingen lägg
 * till-knapp – varje träff har sin egen.
 */
@Composable
fun FundSearchContent(
    state: FundSearchUiState,
    title: String = stringResource(R.string.fondsok_title),
    onBack: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onCompanySelect: (FundCompany?) -> Unit = {},
    onAddFund: (Fund) -> Unit = {},
    /**
     * Satt = skärmen är en **väljare** (HEM-10, issue #102): raden erbjuder "Välj" i stället för
     * "Lägg till", och en redan bevakad fond visar ingen bock — den bocken betyder "finns i din
     * bevakning", vilket är irrelevant när man pekar ut en jämförelsefond. Null = normalläget,
     * lägg till i bevakningen.
     */
    onPickFund: ((Fund) -> Unit)? = null,
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    ListFrameCards { _ ->
        EntityListScreen(
            title = title,
            state = state.list,
            empty = EmptyContent(
                icon = R.drawable.ic_search,
                title = stringResource(R.string.fondsok_empty_title),
                message = stringResource(R.string.fondsok_empty_body)
            ),
            add = null,
            key = { it.fundId },
            modifier = modifier,
            onRetry = onRetry,
            onBack = onBack,
            filter = { SearchFields(state, onCompanySelect, onQueryChange) },
            topBarSize = TopBarSize.Small,
            refreshing = state.refreshing,
            onRefresh = onRefresh,
            // Nätverksfel och "inga träffar" är skilda lägen (issue #78) – felet säger att det är listan som inte kom.
            errorTitle = stringResource(R.string.fondsok_load_failed_title)
        ) { fund ->
            FundResultRow(
                fund = fund,
                added = onPickFund == null && fund.fundId in state.addedFundIds,
                riskLevel = state.riskLevels[fund.fundId],
                actionLabelRes = if (onPickFund == null) R.string.add else R.string.fondsok_pick,
                onAction = { onPickFund?.invoke(fund) ?: onAddFund(fund) }
            )
        }
    }
}

/** Fondbolag och sökord – ramens `filter`, fast i alla lägen. */
@Composable
private fun SearchFields(state: FundSearchUiState, onCompanySelect: (FundCompany?) -> Unit, onQueryChange: (String) -> Unit) {
    val allaFondbolag = stringResource(R.string.fondsok_company_all)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SelectField(
            label = stringResource(R.string.fondsok_company_label),
            options = state.companies,
            selected = state.selectedCompany,
            optionLabel = { it.name },
            onSelect = onCompanySelect,
            placeholder = allaFondbolag,
            clearOptionLabel = allaFondbolag
        )
        AppTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = stringResource(R.string.fondsok_label),
            // Fondnamn skrivs som de står i katalogen – ingen versal i början som bibliotekets standard.
            keyboardOptions = KeyboardOptions.Default
        )
    }
}

/**
 * En träffrad i fondsök — namn, risknivå (UI-10, issue #85) och lägg till-knapp. Risknivån är
 * en delad [RiskBadge] (regel 4) och skrivs ut som okänd när metadatacachen inte känner fonden;
 * en rad helt utan märkning hade sett ut som en fond där risken inte gällde. Knappen läses med
 * fondens namn ("Lägg till Globalfond A"), så att TalkBack skiljer raderna åt (UI-3).
 */
@Composable
private fun FundResultRow(
    fund: Fund,
    added: Boolean,
    riskLevel: Int?,
    actionLabelRes: Int,
    onAction: () -> Unit
) {
    val actionLabel = stringResource(actionLabelRes)
    ItemRow(
        title = fund.name,
        below = { RiskBadge(level = riskLevel) },
        trailing = {
            if (added) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.fondsok_added))
            } else {
                AppButton(
                    text = actionLabel,
                    onClick = onAction,
                    variant = ButtonVariant.Text,
                    contentDescription = stringResource(R.string.format_fondsok_action, actionLabel, fund.name)
                )
            }
        }
    )
}
