package se.partee71.fonder.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp

/** Kortets utseende — samma kort i hela appen, skillnaden uttrycks bara här (regel 4). */
enum class AppCardVariant {
    /** Standardkortet: yta med svag kontur (UI-13). */
    Outlined,

    /** Tonad yta utan kontur — för rader och kort som ligger inne i ett annat kort. */
    Filled,

    /** Farozonen: felbehållarens färg, för åtgärder som inte går att ångra. */
    Error
}

/**
 * Var ett kort ligger i en grupp av kort som ska se ut som **ett** kort (UI-13) – Händelsers lista,
 * där varje rad är ett eget item i en `LazyColumn` så att hundratals transaktioner inte komponeras i
 * förväg. Första kortet får de övre hörnen, sista de nedre, de mellanliggande inga, och intilliggande
 * kort delar kontur i stället för att rita den dubbelt. [Single] är ett vanligt fristående kort.
 */
enum class AppCardGroupPosition {
    Single,
    First,
    Middle,
    Last;

    companion object {
        /** Platsen för element [index] av [count] i en grupp. */
        fun of(index: Int, count: Int): AppCardGroupPosition = when {
            count <= 1 -> Single
            index == 0 -> First
            index == count - 1 -> Last
            else -> Middle
        }
    }
}

/** Kortens inre marginal — den som tidigare skrevs som `Column(Modifier.padding(16.dp))` i varje kort. */
val AppCardPadding = PaddingValues(16.dp)

/**
 * Appens enda kort (UI-13, regel 4) — ersätter de drygt 30 råa `Card { Column(padding(16.dp)) }`
 * som låg i skärmarna. Ytan, konturen och rundningen (`shapes.medium`, 18 dp) kommer ur temat,
 * så alla kort ser likadana ut och ändras på ett ställe.
 *
 * Med [onClick] blir hela kortet **en** klickbar nod (M3:s klickbara kort slår ihop innehållet
 * för skärmläsaren); utan är kortet en grupp utan egen åtgärd. [contentPadding] är 16 dp; ett kort
 * vars innehåll själv ska vara klickbart ända ut i kanten (en utfällbar rad) sätter den till noll
 * och lägger marginalen innanför sin klickyta.
 *
 * [groupPosition] låter flera kort bilda ett (se [AppCardGroupPosition]); standard är ett
 * fristående kort med oförändrat utseende.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    variant: AppCardVariant = AppCardVariant.Outlined,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = AppCardPadding,
    groupPosition: AppCardGroupPosition = AppCardGroupPosition.Single,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = when (variant) {
        AppCardVariant.Outlined -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )

        AppCardVariant.Filled -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        )

        AppCardVariant.Error -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    }
    val border = if (variant == AppCardVariant.Outlined) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    } else {
        null
    }
    val shape = groupPosition.shape(MaterialTheme.shapes.medium)
    // Ett kort under ett annat i gruppen dras upp sin konturbredd, så att de två konturerna
    // sammanfaller till en skiljelinje i stället för en dubbelt så tjock.
    val cardModifier = if (groupPosition == AppCardGroupPosition.Middle || groupPosition == AppCardGroupPosition.Last) {
        // Ytterst i kedjan, så att anroparens storleksmodifierare (höjd, marginal) mäts innanför.
        Modifier.layout { measurable, constraints ->
            val overlap = 1.dp.roundToPx()
            val placeable = measurable.measure(constraints)
            layout(placeable.width, (placeable.height - overlap).coerceAtLeast(0)) { placeable.place(0, -overlap) }
        }.then(modifier)
    } else {
        modifier
    }
    val body: @Composable ColumnScope.() -> Unit = {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = cardModifier, shape = shape, colors = colors, border = border, content = body)
    } else {
        Card(modifier = cardModifier, shape = shape, colors = colors, border = border, content = body)
    }
}

private fun AppCardGroupPosition.shape(single: androidx.compose.foundation.shape.CornerBasedShape): Shape = when (this) {
    AppCardGroupPosition.Single -> single
    AppCardGroupPosition.First -> single.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
    AppCardGroupPosition.Middle -> RectangleShape
    AppCardGroupPosition.Last -> single.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize)
}
