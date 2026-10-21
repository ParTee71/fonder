package se.partee71.fonder.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import se.partee71.ui.theme.ExtendedColors
import se.partee71.ui.theme.Tone
import se.partee71.ui.theme.ToneColors

// ─── Nordisk blå-skala ────────────────────────────────────────────────────────
val Blue900 = Color(0xFF001D36) // text på primärbehållare (ljust)
val Blue800 = Color(0xFF00315A) // text på primär (mörkt tema)
val Blue700 = Color(0xFF1F4E79) // primär (ljust tema) — kärnan
val Blue600 = Color(0xFF2B5A87) // primärbehållare (mörkt tema)
val Blue500 = Color(0xFF3F6E9C)
val Blue400 = Color(0xFF5A8BBE)
val Blue300 = Color(0xFF9CC4F0) // primär (mörkt tema)
val Blue100 = Color(0xFFD2E4FF) // primärbehållare (ljust tema)

// ─── Kopparaccent (sparsamt) ──────────────────────────────────────────────────
val Copper = Color(0xFFB4552F) // sekundär (ljust)
val CopperBright = Color(0xFFFFB59A) // sekundär (mörkt)

// ─── Semantiska avkastningsfärger (dämpade, tillgängliga) ─────────────────────
val GainLight = Color(0xFF21724B)
val GainDark = Color(0xFF5FD39B)
val LossLight = Color(0xFFA9453C)
val LossDark = Color(0xFFE58C82)

// Tonad vinstbehållare med text i samma kulör — InfoPill "Köpt" (BYT-4); skiljer sig i ljushet från koppar och blått.
val GainContainerLight = Color(0xFFD3F2E1)
val OnGainContainerLight = Color(0xFF0B3D24)
val GainContainerDark = Color(0xFF0F3D28)
val OnGainContainerDark = GainDark

// ─── Ljusa ytor ───────────────────────────────────────────────────────────────
private val LightBackground = Color(0xFFF7F9FC)
private val LightSurface = Color(0xFFFDFDFF)
private val LightSurfaceVar = Color(0xFFDEE3EB)
private val LightOnSurface = Color(0xFF181C20)
private val LightOnSurfaceVar = Color(0xFF42474E)
private val LightOutline = Color(0xFF72777F)
private val LightOutlineVar = Color(0xFFC2C7CF)
private val LightInverse = Color(0xFF2D3135)
private val LightOnInverse = Color(0xFFEEF0F6)

// ─── Mörka ytor ───────────────────────────────────────────────────────────────
private val DarkBackground = Color(0xFF101418)
private val DarkSurface = Color(0xFF151A1F)
private val DarkSurfaceVar = Color(0xFF42474E)
private val DarkOnSurface = Color(0xFFE0E2E8)
private val DarkOnSurfaceVar = Color(0xFFC2C7CF)
private val DarkOutline = Color(0xFF8C9199)
private val DarkOutlineVar = Color(0xFF42474E)

val FonderLightColorScheme = lightColorScheme(
    primary = Blue700,
    onPrimary = Color.White,
    primaryContainer = Blue100,
    onPrimaryContainer = Blue900,
    secondary = Copper,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCE),
    onSecondaryContainer = Color(0xFF380D00),
    tertiary = Blue500,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE8F5),
    onTertiaryContainer = Blue900,
    error = Color(0xFFB5483E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410002),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVar,
    onSurfaceVariant = LightOnSurfaceVar,
    outline = LightOutline,
    outlineVariant = LightOutlineVar,
    inverseSurface = LightInverse,
    inverseOnSurface = LightOnInverse,
    inversePrimary = Blue300,
    surfaceTint = Blue700,
    surfaceBright = LightBackground,
    surfaceDim = Color(0xFFD8DCE2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F4F9),
    surfaceContainer = Color(0xFFECEFF4),
    surfaceContainerHigh = Color(0xFFE7ECF2),
    surfaceContainerHighest = Color(0xFFE0E5EC)
)

val FonderDarkColorScheme = darkColorScheme(
    primary = Blue300,
    onPrimary = Blue800,
    primaryContainer = Blue600,
    onPrimaryContainer = Blue100,
    secondary = CopperBright,
    onSecondary = Color(0xFF5A1B00),
    secondaryContainer = Color(0xFF713518),
    onSecondaryContainer = Color(0xFFFFDBCE),
    tertiary = Blue400,
    onTertiary = Blue800,
    tertiaryContainer = Color(0xFF23415F),
    onTertiaryContainer = Color(0xFFDCE8F5),
    error = LossDark,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD5),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVar,
    onSurfaceVariant = DarkOnSurfaceVar,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVar,
    inverseSurface = DarkOnSurface,
    inverseOnSurface = LightInverse,
    inversePrimary = Blue700,
    surfaceTint = Blue300,
    surfaceBright = Color(0xFF363B42),
    surfaceDim = DarkBackground,
    surfaceContainerLowest = Color(0xFF0B0F13),
    surfaceContainerLow = Color(0xFF181C21),
    surfaceContainer = Color(0xFF1C2127),
    surfaceContainerHigh = Color(0xFF222830),
    surfaceContainerHighest = Color(0xFF2D333B)
)

// ─── android-ui: ExtendedColors och toner ─────────────────────────────────────

/** Vinstton – tonad behållare med text i vinstens kulör (InfoPill "Köpt", BYT-4). */
val Tone.Companion.Gain: Tone get() = Tone("gain")

/** Kopparton – sekundärbehållaren (sparsam accent). Primärtonen är bibliotekets [Tone.Primary]. */
val Tone.Companion.Copper: Tone get() = Tone("copper")

/** Tertiärton – tertiärbehållaren (Besluts "Avgift", BYT-4). */
val Tone.Companion.Tertiary: Tone get() = Tone("tertiary")

/** Neutral ton – ytvarianten med dämpad text (Avstått, Avbrutet, Utgånget och risknivån, UI-10). */
val Tone.Companion.Neutral: Tone get() = Tone("neutral")

/** Neutral platta med primärfärgat innehåll – Översiktens tonade ikonknapp Inställningar (NAV-7). */
val Tone.Companion.NeutralPrimary: Tone get() = Tone("neutral-primary")

/** Fylld ton – kort inne i ett kort (Hems avgifts- och analysrader, transaktionsformulärets förklaring). */
val Tone.Companion.Filled: Tone get() = Tone("filled")

/** Felton – felbehållaren, farozonens kort i Inställningar. */
val Tone.Companion.Error: Tone get() = Tone("error")

/**
 * Bibliotekets [ExtendedColors] ur Fonders färgschema: kort och flikrad på `surface`, tonad rad på
 * `surfaceVariant`, primärtonen i primärbehållaren, och diagramseriernas kulörer som accenter.
 */
private fun extendedColors(
    scheme: ColorScheme,
    gainContainer: Color,
    onGainContainer: Color
) = ExtendedColors(
    card = scheme.surface,
    rowTint = scheme.surfaceVariant,
    toolbar = scheme.surface,
    onToolbar = scheme.onSurface,
    tones = mapOf(
        Tone.Primary to ToneColors(container = scheme.primaryContainer, content = scheme.onPrimaryContainer),
        Tone.Copper to ToneColors(container = scheme.secondaryContainer, content = scheme.onSecondaryContainer),
        Tone.Gain to ToneColors(container = gainContainer, content = onGainContainer),
        Tone.Tertiary to ToneColors(container = scheme.tertiaryContainer, content = scheme.onTertiaryContainer),
        Tone.Neutral to ToneColors(container = scheme.surfaceVariant, content = scheme.onSurfaceVariant),
        Tone.NeutralPrimary to ToneColors(container = scheme.surfaceVariant, content = scheme.primary),
        Tone.Filled to ToneColors(container = scheme.surfaceContainerHigh, content = scheme.onSurface),
        Tone.Error to ToneColors(container = scheme.errorContainer, content = scheme.onErrorContainer)
    ),
    accents = listOf(scheme.primary, scheme.secondary)
)

val FonderLightExtendedColors = extendedColors(FonderLightColorScheme, GainContainerLight, OnGainContainerLight)
val FonderDarkExtendedColors = extendedColors(FonderDarkColorScheme, GainContainerDark, OnGainContainerDark)

/**
 * Fonders egna färger utöver Material 3 och [ExtendedColors]: vinst- och förlustkulören för text,
 * pilar och kurvor (UI-3). Ges av [FonderTheme] för det läge appen faktiskt valt.
 */
@Immutable
data class FonderColors(val gain: Color, val loss: Color)

val FonderLightColors = FonderColors(gain = GainLight, loss = LossLight)
val FonderDarkColors = FonderColors(gain = GainDark, loss = LossDark)
