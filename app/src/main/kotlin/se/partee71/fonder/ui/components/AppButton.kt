package se.partee71.fonder.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Knappens tyngd — en per roll, så samma sorts åtgärd ser likadan ut på varje skärm (UI-13). */
enum class AppButtonVariant {
    /** Fylld: skärmens eller kortets huvudåtgärd (Spara, Importera, Exportera). */
    Primary,

    /** Tonad: en åtgärd som ska synas men inte konkurrera med huvudåtgärden. */
    Tonal,

    /** Konturerad: ett jämbördigt alternativ bredvid en fylld knapp. */
    Outlined,

    /** Text: sekundära åtgärder, Stäng och Avbryt. */
    Text,

    /** Fylld i felfärgen: en åtgärd som inte går att ångra (Töm databasen, Ta bort). */
    Danger,
}

/**
 * Appens enda knapp (UI-13, regel 4) — M3 Expressive-knappen med **formmorf**: helt rundad i vila,
 * fyrkantigare när den trycks ned (`ButtonDefaults.shapes()`). Etiketten är text; ingen skärm
 * ritar en egen knapp eller färgar en knapp för hand.
 *
 * @param contentDescription vad skärmläsaren säger i stället för etiketten (UI-3) — för en knapp
 *   som upprepas per kort med samma korta etikett ("Följ", "Avstå"), så att varje knapp säger vad
 *   den gäller. Null (standard) läser etiketten som förut.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val buttonModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else {
        modifier
    }
    val shapes = ButtonDefaults.shapes()
    val label: @Composable () -> Unit = { Text(text) }
    when (variant) {
        AppButtonVariant.Primary ->
            Button(onClick = onClick, shapes = shapes, modifier = buttonModifier, enabled = enabled) { label() }
        AppButtonVariant.Tonal ->
            FilledTonalButton(
                onClick = onClick,
                shapes = shapes,
                modifier = buttonModifier,
                enabled = enabled,
                // Tonad = primärbehållaren (ljusblå), som i mockupen; M3:s standard är secondaryContainer (koppar).
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) { label() }
        AppButtonVariant.Outlined ->
            OutlinedButton(onClick = onClick, shapes = shapes, modifier = buttonModifier, enabled = enabled) { label() }
        AppButtonVariant.Text ->
            TextButton(onClick = onClick, shapes = shapes, modifier = buttonModifier, enabled = enabled) { label() }
        AppButtonVariant.Danger -> Button(
            onClick = onClick,
            shapes = shapes,
            modifier = buttonModifier,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { label() }
    }
}

/** Ikonknappens tyngd. Standard är den genomskinliga; [Tonal] är den fristående ikonknappen i en rubrikrad. */
enum class AppIconButtonVariant {
    /** Genomskinlig: ikonen ensam, i radens egen färg (stäng, ta bort, bakåt). */
    Standard,

    /** Tonad: rund platta i ytvariant med ikonen i primärfärgen — Översiktens Inställningar (NAV-7). */
    Tonal,
}

/**
 * Ikonknapp med samma formmorf som [AppButton] (UI-13). [contentDescription] är obligatorisk: en
 * knapp som bara är en ikon säger annars ingenting till skärmläsaren (UI-3). Tryckytan är M3:s
 * 48 dp.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: AppIconButtonVariant = AppIconButtonVariant.Standard,
) {
    val content: @Composable () -> Unit = { Icon(icon, contentDescription = contentDescription) }
    when (variant) {
        AppIconButtonVariant.Standard ->
            IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes(), modifier = modifier, enabled = enabled, content = content)
        AppIconButtonVariant.Tonal -> FilledTonalIconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            modifier = modifier,
            enabled = enabled,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            content = content,
        )
    }
}
