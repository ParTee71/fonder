package se.partee71.fonder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import se.partee71.fonder.data.datastore.ThemeMode
import se.partee71.fonder.ui.MainViewModel
import se.partee71.fonder.ui.navigation.AppNavigation
import se.partee71.fonder.ui.theme.FonderTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Håll splash tills DataStore emit:at det riktiga tema-läget.
        splashScreen.setKeepOnScreenCondition { vm.themeMode.value == null }

        enableEdgeToEdge()
        setContent {
            val themeMode by vm.themeMode.collectAsState()
            if (themeMode == null) return@setContent

            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> isSystemInDarkTheme()
            }

            FonderTheme(darkTheme = darkTheme) {
                AppNavigation()
            }
        }
    }
}
