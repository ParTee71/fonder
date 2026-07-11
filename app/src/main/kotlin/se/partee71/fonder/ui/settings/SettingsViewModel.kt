package se.partee71.fonder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.datastore.ThemeMode
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val databaseCleared: Boolean = false,
    /** Epoch-millisekunder för senaste lyckade kursuppdatering, null om ingen skett än (SET-2, issue #27). */
    val lastPriceSyncEpochMillis: Long? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRefreshScheduler: FundPriceRefreshScheduler,
) : ViewModel() {

    private val databaseCleared = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> =
        combine(preferences.themeMode, databaseCleared, preferences.lastPriceSyncEpochMillis) { themeMode, cleared, lastSync ->
            SettingsUiState(themeMode = themeMode, databaseCleared = cleared, lastPriceSyncEpochMillis = lastSync)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(),
        )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    /** Tömmer hela databasen (fonder, transaktioner, cachade kurser) — irreversibelt, se SET-1. */
    fun clearDatabase() {
        viewModelScope.launch {
            transactionRepository.clearAll()
            databaseCleared.update { true }
        }
    }

    /** Forcerar en kursuppdatering oavsett staleness-gate — den manuella "Uppdatera nu"-knappen (SET-2, issue #27). */
    fun refreshPricesNow() {
        fundPriceRefreshScheduler.triggerManualRefresh()
    }
}
