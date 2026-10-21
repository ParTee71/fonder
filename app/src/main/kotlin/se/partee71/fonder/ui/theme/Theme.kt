package se.partee71.fonder.ui.theme

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.dp
import se.partee71.ui.components.AppButtonStyle
import se.partee71.ui.components.AppCardStyle
import se.partee71.ui.components.IconButtonVariant
import se.partee71.ui.components.LocalAppButtonStyle
import se.partee71.ui.components.LocalAppCardStyle
import se.partee71.ui.components.LocalTopBarBackStyle
import se.partee71.ui.components.TopBarBackStyle
import se.partee71.ui.theme.AppTheme
import se.partee71.ui.theme.Spacing
import se.partee71.ui.theme.Tone

/**
 * Fonders egna färger för det läge appen **faktiskt** applicerade, till skillnad från systemets
 * ([isSystemInDarkTheme]). [ReturnColors] läser dem härifrån och inte ur systemläget – annars
 * följde vinst/förlust telefonens läge medan alla ytor följde användarens val i Inställningar
 * (ljusa lågkontrastfärger på vit bakgrund, och tvärtom).
 */
private val LocalFonderColors = staticCompositionLocalOf { FonderLightColors }

/**
 * Fonders tema: android-ui:s [AppTheme] (Material 3 Expressive, `MotionScheme.expressive()`, UI-12)
 * med Fonders fasta palett, [FonderAppShapes] och [FonderTypography] – ingen dynamisk färg, identiteten
 * nordisk blå och koppar ska vara konsekvent (skill `ui-style`).
 *
 * Bibliotekets tema tar inte Material 3:s `Shapes`; den inre [MaterialExpressiveTheme] lägger
 * [FonderShapes] ovanpå och ärver färger, typografi och rörelse, så att M3-komponenterna behåller
 * sina hörn.
 *
 * [FonderButtonStyle] ges till bibliotekets knappar ([LocalAppButtonStyle]) här, en gång, så att
 * ramarnas och dialogernas knappar (t.ex. `EntityEditScreen`) ser ut som Fonders egna `AppButton`.
 * [FonderLightCardStyle]/[FonderDarkCardStyle] ger bibliotekets kort ([LocalAppCardStyle]) Fonders kontur (1 dp `outlineVariant`) och
 * 16 dp luft – även mellan ett korts bitar ([se.partee71.ui.components.CardSegment]) – utan extra mellanrum mellan innehållets delar (UI-13).
 * [FonderBackStyle] ger ramarnas topprad ([LocalTopBarBackStyle]) Fonders genomskinliga tillbakapil,
 * samma som Fonders egen `AppTopBar` (UI-8).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FonderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    AppTheme(
        colorScheme = if (darkTheme) FonderDarkColorScheme else FonderLightColorScheme,
        extendedColors = if (darkTheme) FonderDarkExtendedColors else FonderLightExtendedColors,
        shapes = FonderAppShapes,
        typeScale = FonderTypography,
        motionScheme = MotionScheme.expressive()
    ) {
        CompositionLocalProvider(
            LocalFonderColors provides if (darkTheme) FonderDarkColors else FonderLightColors,
            LocalAppButtonStyle provides FonderButtonStyle,
            LocalAppCardStyle provides if (darkTheme) FonderDarkCardStyle else FonderLightCardStyle,
            LocalTopBarBackStyle provides FonderBackStyle
        ) {
            MaterialExpressiveTheme(shapes = FonderShapes, content = content)
        }
    }
}

/**
 * Fonders knappstil för android-ui:s knappar: Material 3:s 40 dp, Expressive-formmorf och den tonade
 * knappen i primärbehållaren (ljusblå) – samma som Fonders egna `AppButton` (UI-13).
 */
private val FonderButtonStyle = AppButtonStyle(minHeight = 40.dp, morph = true, secondaryTone = Tone.Primary)

/**
 * Fonders kortstil för android-ui:s kort: 1 dp kontur i `outlineVariant`, 16 dp luft och inget extra
 * mellanrum mellan innehållets delar – skärmarna lägger sina egna avstånd (UI-13).
 */
private fun fonderCardStyle(border: Color) = AppCardStyle(border = BorderStroke(1.dp, border), padding = Spacing.l, segmentGap = Spacing.l, contentSpacing = 0.dp)
private val FonderLightCardStyle = fonderCardStyle(FonderLightColorScheme.outlineVariant)
private val FonderDarkCardStyle = fonderCardStyle(FonderDarkColorScheme.outlineVariant)

/**
 * Fonders kortstil utan inre luft – för ett kort vars rader själva går ut i kanten och har sin luft innanför
 * klickytan (Facits utfällbara rader, Besluts bytesrader och Facit-raden).
 */
val flushCardStyle: AppCardStyle
    @Composable @ReadOnlyComposable
    get() = LocalAppCardStyle.current.copy(padding = 0.dp, segmentGap = 0.dp)

/**
 * Fonders kortstil för ett kort inne i ett kort (`AppCard(tone = Tone.Filled)`): 12 dp luft i stället för 16,
 * så att raderna i Hems avgifts- och analyskort inte tar för mycket plats.
 */
val nestedCardStyle: AppCardStyle
    @Composable @ReadOnlyComposable
    get() = LocalAppCardStyle.current.copy(padding = Spacing.m)

/**
 * Listramens rader ([se.partee71.ui.components.EntityListScreen]) som bibliotekets kortbitar – utan kontur
 * och med 12 dp luft, så att Händelsers och Fondsökens rader ser ut som innan Fonders kortstil kom in i temat.
 * Ett kort i ramens `header` som ska se ut som Fonders övriga kort tar [cardStyle] (Fonders stil, läst utanför).
 */
@Composable
fun ListFrameCards(content: @Composable (cardStyle: AppCardStyle) -> Unit) {
    val cardStyle = LocalAppCardStyle.current
    CompositionLocalProvider(LocalAppCardStyle provides ListFrameCardStyle) { content(cardStyle) }
}

private val ListFrameCardStyle = AppCardStyle()

/** Fonders tillbakapil i ramarnas topprad: genomskinlig ikonknapp, inte bibliotekets tonade platta (UI-8). */
private val FonderBackStyle = TopBarBackStyle(variant = IconButtonVariant.Plain)

/** Semantiska avkastningsfärger. Får aldrig bäras av enbart färg — para med tecken/pil. */
object ReturnColors {
    val gain: Color
        @Composable @ReadOnlyComposable
        get() = LocalFonderColors.current.gain

    val loss: Color
        @Composable @ReadOnlyComposable
        get() = LocalFonderColors.current.loss

    /** Tonad behållare i vinstens kulör (märken som "Köpt", BYT-4) — para med [onGainContainer]. */
    val gainContainer: Color
        @Composable @ReadOnlyComposable
        get() = AppTheme.tone(Tone.Gain).container

    /** Text på [gainContainer]. */
    val onGainContainer: Color
        @Composable @ReadOnlyComposable
        get() = AppTheme.tone(Tone.Gain).content

    /** Färg för ett värde: vinst om >= 0, annars förlust. */
    @Composable @ReadOnlyComposable
    fun forAmount(amount: Double): Color = if (amount >= 0.0) gain else loss
}

/**
 * Grön/gul/röd statusfärger för säljsignaler (issue #16, ANA-3) — samma fasta palett som
 * övriga appen (UI-1): grön/röd återanvänder [ReturnColors], gul återanvänder den befintliga
 * kopparaccenten (`secondary`) i stället för en ny hårdkodad färg.
 */
object StatusColors {
    val gron: Color
        @Composable @ReadOnlyComposable
        get() = ReturnColors.gain

    val gul: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.secondary

    val rod: Color
        @Composable @ReadOnlyComposable
        get() = ReturnColors.loss
}

/**
 * Kurvfärger i jämförelsediagrammet (ANA-11, issue #85) — innehavet mot en föreslagen fond.
 *
 * Två grannar i samma primärskala går i praktiken inte att skilja åt, så kurvorna skiljs på
 * **ljushet**, inte bara i kulör: innehavet är primärblått och heldraget, jämförelsen koppar och streckad. Köpmarkörerna är också koppar men lodräta, så de förväxlas inte med kurvan.
 * Skillnaden håller därmed i båda temana.
 *
 * Kopparaccenten delas med köpmarkörerna (issue #55), men de är lodräta linjer med datum och
 * jämförelsekurvan är streckad, så de går inte att förväxla.
 */
object ChartSeriesColors {
    /** Innehavets egen kurva — primärfärgen (mörkblå i ljust tema, ljusblå i mörkt, UI-1). */
    val holding: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primary

    /** Jämförelsekurvan (referens/kandidat) — koppar och streckad i diagrammet, så den skiljer sig från innehavet i både kulör och form. */
    val candidate: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.secondary

    /** 200-dagarssnittet (ANA-14) — dämpat `onSurfaceVariant` och prickat, så det varken förväxlas med kursen eller jämförelsen. */
    val movingAverage: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurfaceVariant
}
