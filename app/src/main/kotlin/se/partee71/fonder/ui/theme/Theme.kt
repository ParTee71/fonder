package se.partee71.fonder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FonderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Fast palett (ingen dynamisk färg) — identiteten nordisk blå och koppar ska vara konsekvent.
    val colorScheme = if (darkTheme) FonderDarkColorScheme else FonderLightColorScheme

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        // M3 Expressive (UI-12): uttrycksfulla rörelser (fjädrande formmorf, knappgrupper,
        // formskiftande laddningsindikator) ovanpå samma fasta färger, former och typografi.
        MaterialExpressiveTheme(
            colorScheme  = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography   = FonderTypography,
            shapes       = FonderShapes,
            content      = content,
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
 * kopparaccenten i stället för en ny hårdkodad färg.
 */
object StatusColors {
    val gron: Color
        @Composable @ReadOnlyComposable
        get() = ReturnColors.gain

    val gul: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDarkTheme.current) CopperBright else Copper

    val rod: Color
        @Composable @ReadOnlyComposable
        get() = ReturnColors.loss
}

/**
 * Kurvfärger i jämförelsediagrammet (ANA-11, issue #85) — innehavet mot en föreslagen fond.
 *
 * Två grannar i samma primärskala går i praktiken inte att skilja åt, så kurvorna skiljs på
 * **ljushet**, inte bara i kulör: innehavet är primärblått och kandidaten grå (onSurfaceVariant).
 * Skillnaden håller därmed i båda temana.
 *
 * Kopparaccenten är medvetet inte tagen i anspråk: den bär redan köpmarkörerna i samma
 * diagram (issue #55) och gula risknivåer i övrigt ([StatusColors.gul]).
 */
object ChartSeriesColors {
    /** Innehavets egen kurva — primärfärgen (mörkblå i ljust tema, ljusblå i mörkt, UI-1). */
    val holding: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primary

    /** Den föreslagna fondens kurva — grå, så den skiljer sig från innehavet i ljushet i båda temana. */
    val candidate: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurfaceVariant
}
