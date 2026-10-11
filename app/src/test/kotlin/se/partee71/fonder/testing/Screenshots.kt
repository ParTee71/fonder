package se.partee71.fonder.testing

import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import se.partee71.fonder.ui.theme.FonderTheme

private val themes = listOf("light" to false, "dark" to true)

/**
 * Tar skärmdumpar i ljust och mörkt tema: `<name>_light.png` och `<name>_dark.png` i Roborazzis
 * outputDir (`app/src/test/screenshots`, skill testing-strategy). [name] följer `<Komponent>_<variant>`.
 *
 * Gör ingenting i en vanlig `testDebugUnitTest` – bilderna skrivs bara av `recordRoborazziDebug`
 * och jämförs bara av `verifyRoborazziDebug` (skill ci-budget).
 */
fun captureLightAndDark(name: String, content: @Composable () -> Unit) {
    for ((suffix, dark) in themes) {
        captureRoboImage("${name}_$suffix.png") {
            FonderTheme(darkTheme = dark, content = content)
        }
    }
}
