package se.partee71.fonder.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.datastore.ThemeMode
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    preferences: PreferencesRepository,
) : ViewModel() {

    // null tills DataStore hunnit emit:a — MainActivity håller splash tills värdet finns.
    val themeMode: StateFlow<ThemeMode?> =
        preferences.themeMode.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )
}
