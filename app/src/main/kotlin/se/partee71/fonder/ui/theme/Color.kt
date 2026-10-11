package se.partee71.fonder.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

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
val Copper = Color(0xFFB4552F)       // sekundär (ljust)
val CopperBright = Color(0xFFFFB59A) // sekundär (mörkt)

// ─── Semantiska avkastningsfärger (dämpade, tillgängliga) ─────────────────────
val GainLight = Color(0xFF2FA36B)
val GainDark  = Color(0xFF5FD39B)
val LossLight = Color(0xFFC0574E)
val LossDark  = Color(0xFFE58C82)

// ─── Ljusa ytor ───────────────────────────────────────────────────────────────
private val LightBackground   = Color(0xFFF7F9FC)
private val LightSurface      = Color(0xFFFDFDFF)
private val LightSurfaceVar   = Color(0xFFDEE3EB)
private val LightOnSurface    = Color(0xFF181C20)
private val LightOnSurfaceVar = Color(0xFF42474E)
private val LightOutline      = Color(0xFF72777F)
private val LightOutlineVar   = Color(0xFFC2C7CF)
private val LightInverse      = Color(0xFF2D3135)
private val LightOnInverse    = Color(0xFFEEF0F6)

// ─── Mörka ytor ───────────────────────────────────────────────────────────────
private val DarkBackground   = Color(0xFF101418)
private val DarkSurface      = Color(0xFF151A1F)
private val DarkSurfaceVar   = Color(0xFF42474E)
private val DarkOnSurface    = Color(0xFFE0E2E8)
private val DarkOnSurfaceVar = Color(0xFFC2C7CF)
private val DarkOutline      = Color(0xFF8C9199)
private val DarkOutlineVar   = Color(0xFF42474E)

val FonderLightColorScheme = lightColorScheme(
    primary                 = Blue700,
    onPrimary               = Color.White,
    primaryContainer        = Blue100,
    onPrimaryContainer      = Blue900,
    secondary               = Copper,
    onSecondary             = Color.White,
    secondaryContainer      = Color(0xFFFFDBCE),
    onSecondaryContainer    = Color(0xFF380D00),
    tertiary                = Blue500,
    onTertiary              = Color.White,
    tertiaryContainer       = Color(0xFFDCE8F5),
    onTertiaryContainer     = Blue900,
    error                   = Color(0xFFB5483E),
    onError                 = Color.White,
    errorContainer          = Color(0xFFFFDAD5),
    onErrorContainer        = Color(0xFF410002),
    background              = LightBackground,
    onBackground            = LightOnSurface,
    surface                 = LightSurface,
    onSurface               = LightOnSurface,
    surfaceVariant          = LightSurfaceVar,
    onSurfaceVariant        = LightOnSurfaceVar,
    outline                 = LightOutline,
    outlineVariant          = LightOutlineVar,
    inverseSurface          = LightInverse,
    inverseOnSurface        = LightOnInverse,
    inversePrimary          = Blue300,
    surfaceTint             = Blue700,
    surfaceBright           = LightBackground,
    surfaceDim              = Color(0xFFD8DCE2),
    surfaceContainerLowest  = Color.White,
    surfaceContainerLow     = Color(0xFFF1F4F9),
    surfaceContainer        = Color(0xFFECEFF4),
    surfaceContainerHigh    = Color(0xFFE7ECF2),
    surfaceContainerHighest = Color(0xFFE0E5EC),
)

val FonderDarkColorScheme = darkColorScheme(
    primary                 = Blue300,
    onPrimary               = Blue800,
    primaryContainer        = Blue600,
    onPrimaryContainer      = Blue100,
    secondary               = CopperBright,
    onSecondary             = Color(0xFF5A1B00),
    secondaryContainer      = Color(0xFF713518),
    onSecondaryContainer    = Color(0xFFFFDBCE),
    tertiary                = Blue400,
    onTertiary              = Blue800,
    tertiaryContainer       = Color(0xFF23415F),
    onTertiaryContainer     = Color(0xFFDCE8F5),
    error                   = LossDark,
    onError                 = Color(0xFF690005),
    errorContainer          = Color(0xFF93000A),
    onErrorContainer        = Color(0xFFFFDAD5),
    background              = DarkBackground,
    onBackground            = DarkOnSurface,
    surface                 = DarkSurface,
    onSurface               = DarkOnSurface,
    surfaceVariant          = DarkSurfaceVar,
    onSurfaceVariant        = DarkOnSurfaceVar,
    outline                 = DarkOutline,
    outlineVariant          = DarkOutlineVar,
    inverseSurface          = DarkOnSurface,
    inverseOnSurface        = LightInverse,
    inversePrimary          = Blue700,
    surfaceTint             = Blue300,
    surfaceBright           = Color(0xFF363B42),
    surfaceDim              = DarkBackground,
    surfaceContainerLowest  = Color(0xFF0B0F13),
    surfaceContainerLow     = Color(0xFF181C21),
    surfaceContainer        = Color(0xFF1C2127),
    surfaceContainerHigh    = Color(0xFF222830),
    surfaceContainerHighest = Color(0xFF2D333B),
)
