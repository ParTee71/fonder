package se.partee71.fonder.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ─── Grön petrol-skala ────────────────────────────────────────────────────────
val Petrol900 = Color(0xFF06302B)
val Petrol800 = Color(0xFF0A403A)
val Petrol700 = Color(0xFF0E5249) // primär (ljust tema)
val Petrol600 = Color(0xFF12655A)
val Petrol500 = Color(0xFF167C6E) // kärna
val Petrol400 = Color(0xFF2E9B8A)
val Petrol300 = Color(0xFF5FBDAE) // primär (mörkt tema)

// ─── Mässings-/guldaccent (sparsamt) ─────────────────────────────────────────
val Brass = Color(0xFFB8862F)      // sekundär (ljust)
val BrassBright = Color(0xFFE3C05A) // sekundär (mörkt)

// ─── Semantiska avkastningsfärger (dämpade, tillgängliga) ─────────────────────
val GainLight = Color(0xFF2FA36B)
val GainDark  = Color(0xFF5FD39B)
val LossLight = Color(0xFFC0574E)
val LossDark  = Color(0xFFE58C82)

// ─── Ljusa ytor ───────────────────────────────────────────────────────────────
private val LightBackground   = Color(0xFFF6F9F8)
private val LightSurface      = Color(0xFFFBFDFC)
private val LightSurfaceVar   = Color(0xFFDCE5E2)
private val LightOnSurface    = Color(0xFF0F1513)
private val LightOnSurfaceVar = Color(0xFF3F4B48)
private val LightOutline      = Color(0xFF6F7C79)
private val LightOutlineVar   = Color(0xFFBFCCC8)

// ─── Mörka ytor ───────────────────────────────────────────────────────────────
private val DarkBackground   = Color(0xFF0F1513)
private val DarkSurface      = Color(0xFF121A18)
private val DarkSurfaceVar   = Color(0xFF3F4946)
private val DarkOnSurface    = Color(0xFFDDE4E1)
private val DarkOnSurfaceVar = Color(0xFFBFC9C5)
private val DarkOutline      = Color(0xFF899490)
private val DarkOutlineVar   = Color(0xFF3F4946)

val FonderLightColorScheme = lightColorScheme(
    primary              = Petrol700,
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFF9FE9DB),
    onPrimaryContainer   = Color(0xFF00201B),
    secondary            = Brass,
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFF3E2B4),
    onSecondaryContainer = Color(0xFF3A2E00),
    tertiary             = Petrol500,
    onTertiary           = Color.White,
    tertiaryContainer    = Color(0xFFB6E7DE),
    onTertiaryContainer  = Color(0xFF00201B),
    error                = Color(0xFFB5483E),
    onError              = Color.White,
    errorContainer       = Color(0xFFFFDAD5),
    onErrorContainer     = Color(0xFF410002),
    background           = LightBackground,
    onBackground         = LightOnSurface,
    surface              = LightSurface,
    onSurface            = LightOnSurface,
    surfaceVariant       = LightSurfaceVar,
    onSurfaceVariant     = LightOnSurfaceVar,
    outline              = LightOutline,
    outlineVariant       = LightOutlineVar,
    inverseSurface       = DarkSurface,
    inverseOnSurface     = DarkOnSurface,
    inversePrimary       = Petrol300,
    surfaceTint          = Petrol700,
)

val FonderDarkColorScheme = darkColorScheme(
    primary              = Petrol300,
    onPrimary            = Color(0xFF00382F),
    primaryContainer     = Petrol700,
    onPrimaryContainer   = Color(0xFF9FE9DB),
    secondary            = BrassBright,
    onSecondary          = Color(0xFF3A2E00),
    secondaryContainer   = Color(0xFF574400),
    onSecondaryContainer = Color(0xFFF3E2B4),
    tertiary             = Petrol400,
    onTertiary           = Color(0xFF00382F),
    tertiaryContainer    = Petrol800,
    onTertiaryContainer  = Color(0xFFB6E7DE),
    error                = LossDark,
    onError              = Color(0xFF690005),
    errorContainer       = Color(0xFF93000A),
    onErrorContainer     = Color(0xFFFFDAD5),
    background           = DarkBackground,
    onBackground         = DarkOnSurface,
    surface              = DarkSurface,
    onSurface            = DarkOnSurface,
    surfaceVariant       = DarkSurfaceVar,
    onSurfaceVariant     = DarkOnSurfaceVar,
    outline              = DarkOutline,
    outlineVariant       = DarkOutlineVar,
    inverseSurface       = LightSurface,
    inverseOnSurface     = LightOnSurface,
    inversePrimary       = Petrol700,
    surfaceTint          = Petrol300,
)
