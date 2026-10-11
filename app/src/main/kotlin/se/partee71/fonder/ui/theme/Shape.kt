package se.partee71.fonder.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Formerna i M3 Expressive-mockupen (UI-12): fält 12 dp (`extraSmall`, M3:s textfält och menyer),
 * chips och märken 8 dp (`small`), kort 18 dp (`medium`), dialoger 28 dp (`extraLarge`).
 */
val FonderShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small      = RoundedCornerShape(8.dp),
    medium     = RoundedCornerShape(18.dp),
    large      = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
