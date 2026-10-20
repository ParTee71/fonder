package se.partee71.fonder.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import se.partee71.ui.theme.AppShapes

// Hörnradierna i M3 Expressive-mockupen (UI-12) – en gång, för båda formuppsättningarna nedan.
private val FieldRadius = 12.dp
private val ChipRadius = 8.dp
private val CardRadius = 18.dp
private val LargeRadius = 26.dp
private val DialogRadius = 28.dp

/**
 * Material 3-formerna som Fonders komponenter läser via `MaterialTheme.shapes`: fält 12 dp
 * (`extraSmall`, M3:s textfält och menyer), chips och märken 8 dp (`small`), kort 18 dp (`medium`),
 * dialoger 28 dp (`extraLarge`).
 */
val FonderShapes = Shapes(
    extraSmall = RoundedCornerShape(FieldRadius),
    small = RoundedCornerShape(ChipRadius),
    medium = RoundedCornerShape(CardRadius),
    large = RoundedCornerShape(LargeRadius),
    extraLarge = RoundedCornerShape(DialogRadius)
)

/**
 * Samma radier som android-ui:s [AppShapes] (`AppTheme.shapes`): kort och rader 18 dp, fält och
 * menyer 12 dp, liten ikonruta 8 dp, tomt tillstånds ikonruta 18 dp, bottom sheet 28 dp upptill.
 */
val FonderAppShapes = AppShapes(
    cardRadius = CardRadius,
    row = RoundedCornerShape(CardRadius),
    pill = CircleShape,
    field = RoundedCornerShape(FieldRadius),
    menu = RoundedCornerShape(FieldRadius),
    sheet = RoundedCornerShape(topStart = DialogRadius, topEnd = DialogRadius),
    smallTile = RoundedCornerShape(ChipRadius),
    iconTile = RoundedCornerShape(CardRadius)
)
