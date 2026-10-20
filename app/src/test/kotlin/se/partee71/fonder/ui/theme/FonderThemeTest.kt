package se.partee71.fonder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.ui.theme.AppShapes
import se.partee71.ui.theme.AppTheme
import se.partee71.ui.theme.ExtendedColors

/**
 * [FonderTheme] som omslutning av android-ui:s `AppTheme`: Material 3-komponenterna får Fonders
 * egna former och typskala (inte M3:s standard), bibliotekets tema får Fonders värden, och de
 * semantiska färgerna följer det läge appen valt – inte systemets (UI-4).
 */
@RunWith(RobolectricTestRunner::class)
class FonderThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class Seen {
        lateinit var shapes: Shapes
        lateinit var typography: Typography
        lateinit var appShapes: AppShapes
        lateinit var extended: ExtendedColors
        var primary = Color.Unspecified
        var gain = Color.Unspecified
        var loss = Color.Unspecified
        var gainContainer = Color.Unspecified
        var onGainContainer = Color.Unspecified
        var gul = Color.Unspecified
    }

    private fun capture(dark: Boolean): Seen {
        val seen = Seen()
        composeRule.setContent {
            FonderTheme(darkTheme = dark) {
                seen.shapes = MaterialTheme.shapes
                seen.typography = MaterialTheme.typography
                seen.appShapes = AppTheme.shapes
                seen.extended = AppTheme.extendedColors
                seen.primary = MaterialTheme.colorScheme.primary
                seen.gain = ReturnColors.gain
                seen.loss = ReturnColors.loss
                seen.gainContainer = ReturnColors.gainContainer
                seen.onGainContainer = ReturnColors.onGainContainer
                seen.gul = StatusColors.gul
            }
        }
        composeRule.waitForIdle()
        return seen
    }

    @Test
    fun `ljust läge ger Fonders former, typskala och ljusa färger`() {
        val seen = capture(dark = false)
        assertSame(FonderShapes, seen.shapes)
        assertSame(FonderTypography, seen.typography)
        assertSame(FonderAppShapes, seen.appShapes)
        assertSame(FonderLightExtendedColors, seen.extended)
        assertEquals(Blue700, seen.primary)
        assertEquals(GainLight, seen.gain)
        assertEquals(LossLight, seen.loss)
        assertEquals(GainContainerLight, seen.gainContainer)
        assertEquals(OnGainContainerLight, seen.onGainContainer)
        assertEquals(Copper, seen.gul)
    }

    @Test
    fun `valt mörkt läge ger mörka semantiska färger oavsett systemets läge`() {
        // Robolectric kör i systemets ljusa läge – färgerna ska ändå följa darkTheme.
        val seen = capture(dark = true)
        assertSame(FonderShapes, seen.shapes)
        assertSame(FonderDarkExtendedColors, seen.extended)
        assertEquals(Blue300, seen.primary)
        assertEquals(GainDark, seen.gain)
        assertEquals(LossDark, seen.loss)
        assertEquals(GainContainerDark, seen.gainContainer)
        assertEquals(OnGainContainerDark, seen.onGainContainer)
        assertEquals(CopperBright, seen.gul)
    }
}
