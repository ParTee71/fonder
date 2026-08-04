package se.partee71.fonder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Det mörka läge appen **faktiskt** applicerade, till skillnad från systemets
 * ([isSystemInDarkTheme]). De semantiska färgerna nedan ligger utanför `MaterialTheme`s
 * färgschema och måste läsa samma värde som [FonderTheme] fick — annars följde vinst/förlust
 * och statusprickarna telefonens läge medan alla ytor följde användarens val i Inställningar
 * (ljusa lågkontrastfärger på vit bakgrund, och tvärtom).
 */
private val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun FonderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Fast palett (ingen dynamisk färg) — identiteten grön petrol ska vara konsekvent.
    val colorScheme = if (darkTheme) FonderDarkColorScheme else FonderLightColorScheme

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = FonderTypography,
            shapes      = FonderShapes,
            content     = content,
        )
    }
}

/** Semantiska avkastningsfärger. Får aldrig bäras av enbart färg — para med tecken/pil. */
object ReturnColors {
    val gain: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkTheme.current) GainDark else GainLight

    val loss: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkTheme.current) LossDark else LossLight

    /** Färg för ett värde: vinst om >= 0, annars förlust. */
    @Composable @ReadOnlyComposable
    fun forAmount(amount: Double): Color = if (amount >= 0.0) gain else loss
}

/**
 * Grön/gul/röd statusfärger för säljsignaler (issue #16, ANA-3) — samma fasta palett som
 * övriga appen (UI-1): grön/röd återanvänder [ReturnColors], gul återanvänder den befintliga
 * mässings-/guldaccenten i stället för en ny hårdkodad färg.
 */
object StatusColors {
    val gron: Color
        @Composable @ReadOnlyComposable
        get() = ReturnColors.gain

    val gul: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkTheme.current) BrassBright else Brass

    val rod: Color
        @Composable @ReadOnlyComposable
        get() = ReturnColors.loss
}

/**
 * Kurvfärger i jämförelsediagrammet (ANA-11, issue #85) — innehavet mot en föreslagen fond.
 *
 * De två kurvorna låg tidigare på `primary` och `tertiary`, som i ljust tema är två grannar i
 * samma petrolskala (Petrol700 mot Petrol500) och i praktiken inte gick att skilja åt. Här
 * skiljs de i stället på **ljushet**, inte bara i kulör: innehavet är bläckfärgat (samma ton
 * som brödtexten, alltså nästan svart i ljust tema och nästan vitt i mörkt) och kandidaten är
 * palettens lysande petrol. Skillnaden håller därmed i båda temana.
 *
 * Mässingsaccenten är medvetet inte tagen i anspråk: den bär redan köpmarkörerna i samma
 * diagram (issue #55) och gula risknivåer i övrigt ([StatusColors.gul]).
 */
object ChartSeriesColors {
    /** Innehavets egen kurva — "bläck" på ytan, vänder med temat så den alltid syns. */
    val holding: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurface

    /** Den föreslagna fondens kurva — palettens ljusaste petrol som fortfarande bär mot bakgrunden. */
    val candidate: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkTheme.current) Petrol300 else Petrol400
}
