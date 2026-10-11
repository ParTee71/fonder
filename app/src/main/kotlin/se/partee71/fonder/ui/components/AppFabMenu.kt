package se.partee71.fonder.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.partee71.fonder.R

/** Ett val i [AppFabMenu]. Utan [icon] är valet en ren textpill, som i mockupen. */
data class AppFabMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
)

/** Mått för det som ska ligga bredvid [AppFabMenu] längs nederkanten (flikraden i `AppNavigation`). */
object AppFabMenuDefaults {
    /** Knappens bredd plus menyns egen ändmarginal och 8 dp luft till grannen. */
    val ReservedEndWidth: Dp = 16.dp + 56.dp + 8.dp

    /** Avståndet från menyns nederkant till knappens mitt — grannen centreras på samma linje. */
    val ButtonCenterFromBottom: Dp = 16.dp + 28.dp
}

/**
 * Appens plusknapp (NAV-8, UI-14) — M3 Expressives `FloatingActionButtonMenu` med en
 * `ToggleFloatingActionButton`. Knappen är fylld i primärfärgen, fyrkantigt rundad med "+" när
 * menyn är stängd och helt rund med "×" när den är öppen; valen fälls ut ovanför som pills i
 * primärbehållaren.
 *
 * Ett val stänger menyn innan det utförs, och bakåt stänger en öppen meny i stället för att lämna
 * skärmen. Knappen läses "Lägg till" respektive "Stäng meny" med utfällt-läget som tillstånd.
 * Stateless: [expanded] hoistas till anroparen (sparas med `rememberSaveable` där, UI-9).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppFabMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    items: List<AppFabMenuItem>,
    modifier: Modifier = Modifier,
) {
    val openDescription = stringResource(R.string.fab_menu_open)
    val closeDescription = stringResource(R.string.fab_menu_close)
    val expandedState = stringResource(R.string.fab_menu_expanded)
    val collapsedState = stringResource(R.string.fab_menu_collapsed)
    BackHandler(enabled = expanded) { onExpandedChange(false) }
    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = onExpandedChange,
                containerColor = ToggleFloatingActionButtonDefaults.containerColor(
                    initialColor = MaterialTheme.colorScheme.primary,
                    finalColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.semantics {
                    contentDescription = if (expanded) closeDescription else openDescription
                    stateDescription = if (expanded) expandedState else collapsedState
                },
            ) {
                val iconColor = ToggleFloatingActionButtonDefaults.iconColor(
                    initialColor = MaterialTheme.colorScheme.onPrimary,
                    finalColor = MaterialTheme.colorScheme.onPrimary,
                )
                Icon(
                    if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.animateIcon({ checkedProgress }, color = iconColor),
                )
            }
        },
    ) {
        items.forEach { item ->
            FloatingActionButtonMenuItem(
                onClick = {
                    onExpandedChange(false)
                    item.onClick()
                },
                text = { Text(item.label) },
                icon = { item.icon?.let { Icon(it, contentDescription = null) } },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
