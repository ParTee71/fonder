package se.partee71.fonder.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { LIGHT, DARK, AUTO }

/**
 * Single source of truth för app-inställningar (DataStore Preferences).
 * Läser och skriver tema-läget; utökas i takt med att inställningar tillkommer.
 */
@Singleton
class PreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val themeModeKey = stringPreferencesKey("theme_mode")

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[themeModeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.AUTO
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[themeModeKey] = mode.name }
    }
}
