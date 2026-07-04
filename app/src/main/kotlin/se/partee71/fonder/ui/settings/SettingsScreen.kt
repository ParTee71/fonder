package se.partee71.fonder.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.partee71.fonder.R
import se.partee71.fonder.data.datastore.ThemeMode

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onImportHoldings: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            stringResource(R.string.settings_theme_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(modifier = Modifier.selectableGroup()) {
            ThemeChip(ThemeMode.LIGHT, R.string.theme_light, state.themeMode, viewModel::setThemeMode)
            Spacer(Modifier.width(8.dp))
            ThemeChip(ThemeMode.DARK, R.string.theme_dark, state.themeMode, viewModel::setThemeMode)
            Spacer(Modifier.width(8.dp))
            ThemeChip(ThemeMode.AUTO, R.string.theme_auto, state.themeMode, viewModel::setThemeMode)
        }

        Card(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
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
    }
}

@Composable
private fun ThemeChip(
    mode: ThemeMode,
    labelRes: Int,
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    FilterChip(
        selected = selected == mode,
        onClick = { onSelect(mode) },
        label = { Text(stringResource(labelRes)) },
    )
}
