package se.partee71.fonder.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.ui.theme.ExtendedColors
import se.partee71.ui.theme.Tone

/**
 * Varje färg som bär text klarar 4,5:1 mot sin bakgrund i båda temana (skill `ui-style`, UI-1).
 * Det gäller också vinst och förlust (UI-3), som visas som beloppstext.
 */
class ThemeContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun textPairs(scheme: ColorScheme, extended: ExtendedColors) = mapOf(
        "text på bakgrund" to (scheme.onBackground to scheme.background),
        "dämpad text på bakgrund" to (scheme.onSurfaceVariant to scheme.background),
        "text på kort" to (scheme.onSurface to extended.card),
        "dämpad text på kort" to (scheme.onSurfaceVariant to extended.card),
        "dämpad text på tonad rad" to (scheme.onSurfaceVariant to extended.rowTint),
        "text på fyllt kort" to (scheme.onSurface to scheme.surfaceContainerHigh),
        "primär text på kort" to (scheme.primary to extended.card),
        "primär text på bakgrund" to (scheme.primary to scheme.background),
        "text på primärknapp" to (scheme.onPrimary to scheme.primary),
        "text på primärbehållare" to (scheme.onPrimaryContainer to scheme.primaryContainer),
        "text på kopparknapp" to (scheme.onSecondary to scheme.secondary),
        "text på sekundärbehållare" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
        "felknapp" to (scheme.onError to scheme.error),
        "fel på kort" to (scheme.error to extended.card),
        "text på felbehållare" to (scheme.onErrorContainer to scheme.errorContainer),
        "snackbar" to (scheme.inverseOnSurface to scheme.inverseSurface),
        "flikrad" to (extended.onToolbar to extended.toolbar),
        "primärton" to (extended.primaryTone.content to extended.primaryTone.container),
        "kopparton" to (extended.tone(Tone.Copper).content to extended.tone(Tone.Copper).container),
        "vinstton" to (extended.tone(Tone.Gain).content to extended.tone(Tone.Gain).container)
    )

    private fun signalPairs(scheme: ColorScheme, extended: ExtendedColors, colors: FonderColors) = mapOf(
        "vinst på kort" to (colors.gain to extended.card),
        "vinst på bakgrund" to (colors.gain to scheme.background),
        "förlust på kort" to (colors.loss to extended.card),
        "förlust på bakgrund" to (colors.loss to scheme.background)
    )

    @Test
    fun `ljust tema - text`() = check("ljust", textPairs(FonderLightColorScheme, FonderLightExtendedColors), TEXT)

    @Test
    fun `mörkt tema - text`() = check("mörkt", textPairs(FonderDarkColorScheme, FonderDarkExtendedColors), TEXT)

    @Test
    fun `ljust tema - vinst och förlust`() = check("ljust", signalPairs(FonderLightColorScheme, FonderLightExtendedColors, FonderLightColors), TEXT)

    @Test
    fun `mörkt tema - vinst och förlust`() = check("mörkt", signalPairs(FonderDarkColorScheme, FonderDarkExtendedColors, FonderDarkColors), TEXT)

    @Test
    fun `appens toner har egna färger i båda temana`() {
        // Bibliotekets tone() faller annars tyst tillbaka till primärtonen.
        listOf("ljust" to FonderLightExtendedColors, "mörkt" to FonderDarkExtendedColors).forEach { (theme, extended) ->
            listOf(Tone.Primary, Tone.Copper, Tone.Gain).forEach { tone ->
                assertTrue("$theme: ${tone.key} saknas i tones", tone in extended.tones)
            }
        }
    }

    private fun check(theme: String, pairs: Map<String, Pair<Color, Color>>, min: Double) {
        val failures = pairs.mapNotNull { (name, colors) ->
            val ratio = contrast(colors.first, colors.second)
            "$theme: $name har ${"%.2f".format(ratio)}:1 (krav $min:1)".takeIf { ratio < min }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private companion object {
        const val TEXT = 4.5
    }
}
