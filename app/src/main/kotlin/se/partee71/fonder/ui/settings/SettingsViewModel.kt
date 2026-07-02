package se.partee71.fonder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.datastore.ThemeMode
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.AUTO,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> =
        preferences.themeMode
            .map { SettingsUiState(themeMode = it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SettingsUiState(),
            )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }
}
