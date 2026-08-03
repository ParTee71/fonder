package se.partee71.fonder.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.data.datastore.ThemeMode
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.ui.components.ChoiceChipRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val lastSyncFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onImportHoldings: () -> Unit = {},
    onImportOrders: () -> Unit = {},
    onOpenRiskProfile: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onThemeSelected = viewModel::setThemeMode,
        onImportHoldings = onImportHoldings,
        onImportOrders = onImportOrders,
        onOpenRiskProfile = onOpenRiskProfile,
        onAccountTypeSelected = viewModel::setAccountType,
        onRefreshPricesNow = viewModel::refreshPricesNow,
        onClearDatabase = viewModel::clearDatabase,
        onClearedMessageDismissed = viewModel::onClearedMessageDismissed,
        modifier = modifier,
    )
}

/** Tillståndsdriven, testbar del av [SettingsScreen] — inget ViewModel/Hilt-beroende (issue #27). */
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onThemeSelected: (ThemeMode) -> Unit = {},
    onImportHoldings: () -> Unit = {},
    onImportOrders: () -> Unit = {},
    onOpenRiskProfile: () -> Unit = {},
    onAccountTypeSelected: (AccountType) -> Unit = {},
    onRefreshPricesNow: () -> Unit = {},
    onClearDatabase: () -> Unit = {},
    onClearedMessageDismissed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // rememberSaveable: en bekräftelsedialog ska inte försvinna tyst vid rotation (issue #78).
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            stringResource(R.string.settings_theme_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        ChoiceChipRow(
            options = ThemeMode.entries,
            selected = state.themeMode,
            optionLabel = { mode -> stringResource(themeModeLabelRes(mode)) },
            onSelect = onThemeSelected,
        )

        Card(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_riskprofile_section), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_riskprofile_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                Button(onClick = onOpenRiskProfile) { Text(stringResource(R.string.settings_riskprofile_button)) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_account_type_section), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_account_type_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                ChoiceChipRow(
                    options = AccountType.entries,
                    selected = state.accountType,
                    optionLabel = { type -> stringResource(accountTypeLabelRes(type)) },
                    onSelect = onAccountTypeSelected,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_price_update_section), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_price_update_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                Text(
                    lastPriceSyncText(state.lastPriceSyncEpochMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Button(onClick = onRefreshPricesNow) { Text(stringResource(R.string.settings_refresh_prices_button)) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_account_section), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_account_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_import_section), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_import_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                Button(onClick = onImportHoldings) { Text(stringResource(R.string.settings_import_button)) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_import_orders_section), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_import_orders_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                Button(onClick = onImportOrders) { Text(stringResource(R.string.settings_import_orders_button)) }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.settings_danger_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    stringResource(R.string.settings_danger_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                // Meddelandet läses direkt ur tillståndet och kvitteras i ViewModel:en — ingen
                // lokal spegling via LaunchedEffect, som fick engångshändelsen att spelas upp
                // igen vid varje rotation och återbesök (issue #78). Kvitteringen är också
                // meddelandets enda väg ut; tidigare fanns ingen.
                if (state.databaseCleared) {
                    Text(
                        stringResource(R.string.settings_clear_database_success),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    TextButton(
                        onClick = onClearedMessageDismissed,
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) { Text(stringResource(R.string.close)) }
                }
                Button(
                    onClick = { showClearConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.settings_clear_database_button)) }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.clear_database_confirm_title)) },
            text = { Text(stringResource(R.string.clear_database_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearDatabase()
                    showClearConfirm = false
                }) { Text(stringResource(R.string.settings_clear_database_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun lastPriceSyncText(epochMillis: Long?): String {
    if (epochMillis == null) return stringResource(R.string.settings_last_price_sync_never)
    val formatted = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(lastSyncFormatter)
    return stringResource(R.string.format_settings_last_price_sync, formatted)
}

private fun themeModeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.AUTO -> R.string.theme_auto
}

private fun accountTypeLabelRes(type: AccountType): Int = when (type) {
    AccountType.ISK_KF -> R.string.account_type_isk_kf
    AccountType.DEPA_AF -> R.string.account_type_depa_af
}
