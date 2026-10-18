package se.partee71.fonder.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import se.partee71.fonder.R

/**
 * En flik i [AppFloatingToolbar]. [badgeCount] är ett kopparmärke med antal (Beslut, NAV-7);
 * `null` eller 0 ritar inget märke.
 */
data class AppToolbarTab(
    val label: String,
    val icon: ImageVector,
    val badgeCount: Int? = null
)

/** Testtagg för flikraden — gör "raden döljs på djupare skärmar" (NAV-7) adresserbart i tester. */
const val APP_FLOATING_TOOLBAR_TAG = "app_floating_toolbar"

/**
 * Appens flikrad (NAV-7, UI-14) — M3 Expressives flytande verktygsrad längs nederkanten i stället
 * för en `NavigationBar`. Raden är ytan med svag kontur och helt rundad; den valda fliken är en
 * pill i primärbehållaren med ikon **och** namn, de ovalda bara en ikon i dämpad färg. Ett märke
 * med antal ritas i kopparen bredvid en ovald fliks ikon.
 *
 * Varje flik är minst 48 × 48 dp och läses som en flik (`Role.Tab`) med valt-läge och sitt namn —
 * plus märkets antal — som beskrivning, eftersom de ovalda flikarna inte visar sitt namn (UI-3).
 * Stateless: vald flik och klick hoistas till anroparen.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppFloatingToolbar(
    tabs: List<AppToolbarTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier
            .testTag(APP_FLOATING_TOOLBAR_TAG)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = CircleShape),
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = MaterialTheme.colorScheme.surface,
            toolbarContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        shape = CircleShape,
        expandedShadowElevation = TOOLBAR_ELEVATION
    ) {
        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                ToolbarTab(tab = tab, selected = index == selectedIndex, onClick = { onSelect(index) })
            }
        }
    }
}

private val TOOLBAR_ELEVATION = 3.dp
private val TAB_ICON_SIZE = 22.dp

@Composable
private fun ToolbarTab(tab: AppToolbarTab, selected: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        label = "tabContainer"
    )
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val badge = tab.badgeCount?.takeIf { it > 0 && !selected }
    val description = if (badge != null) {
        stringResource(R.string.format_toolbar_tab_badge, tab.label, badge)
    } else {
        tab.label
    }
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(CircleShape)
            .background(container)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(tab.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(TAB_ICON_SIZE))
        AnimatedVisibility(visible = selected) {
            Text(
                tab.label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
        if (badge != null) CountBadge(badge)
    }
}
