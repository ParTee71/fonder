package se.partee71.fonder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

@Composable
fun FonderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Fast palett (ingen dynamisk färg) — identiteten grön petrol ska vara konsekvent.
    val colorScheme = if (darkTheme) FonderDarkColorScheme else FonderLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = FonderTypography,
        shapes      = FonderShapes,
        content     = content,
    )
}

/** Semantiska avkastningsfärger. Får aldrig bäras av enbart färg — para med tecken/pil. */
object ReturnColors {
    val gain: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) GainDark else GainLight

    val loss: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) LossDark else LossLight

    /** Färg för ett värde: vinst om >= 0, annars förlust. */
    @Composable @ReadOnlyComposable
    fun forAmount(amount: Double): Color = if (amount >= 0.0) gain else loss
}
