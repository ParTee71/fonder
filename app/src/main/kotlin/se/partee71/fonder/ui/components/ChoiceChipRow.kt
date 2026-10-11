package se.partee71.fonder.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

/**
 * Delad rad av ömsesidigt uteslutande val (regel 4) — en sammanhängande knappgrupp i M3
 * Expressive (UI-12): yttre hörn rundade, inre nästan raka, det valda alternativet fyllt i
 * primärfärgen. Används av Inställningar (tema, kontotyp) och Riskprofilens enkät (SET-3);
 * periodväljaren i diagrammet ritar sina knappar med samma [ConnectedChoiceButton].
 *
 * Horisontellt skrollbar: en fråga med fler/längre alternativ än vad som får plats ska aldrig
 * klippa bort ett val i stället för att göra det nåbart (UI-5).
 *
 * @param optionLabel `@Composable` så anropsstället kan slå upp etiketten via `stringResource`
 *   direkt, utan att själv behöva resolva strängar i förväg.
 */
@Composable
fun <T> ChoiceChipRow(
    options: List<T>,
    selected: T?,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ConnectedChoiceSpacing),
    ) {
        options.forEachIndexed { index, option ->
            ConnectedChoiceButton(
                label = optionLabel(option),
                selected = option == selected,
                index = index,
                count = options.size,
                onClick = { onSelect(option) },
            )
        }
    }
}

/** Avståndet mellan knapparna i en sammanhängande grupp ([ChoiceChipRow], diagrammets periodrad). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val ConnectedChoiceSpacing = ButtonGroupDefaults.ConnectedSpaceBetween

/**
 * En knapp i en sammanhängande knappgrupp. Formen följer platsen i gruppen ([index] av [count]):
 * första och sista får rundade ytterhörn, de mellanliggande nästan raka hörn, och den valda blir
 * helt rundad. Läses som ett radioval (`Role.RadioButton` + vald), inte som en kryssruta, så
 * skärmläsaren säger att bara ett alternativ gäller.
 *
 * Används bara inuti en grupp – [ChoiceChipRow] eller `FundLineChart`s periodrad.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectedChoiceButton(
    label: String,
    selected: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shapes = when {
        count == 1 -> ToggleButtonShapes(
            shape = ToggleButtonDefaults.shape,
            pressedShape = ToggleButtonDefaults.pressedShape,
            checkedShape = ToggleButtonDefaults.checkedShape,
        )
        index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
        index == count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
    }
    ToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        modifier = modifier.semantics {
            role = Role.RadioButton
            this.selected = selected
        },
        shapes = shapes,
        colors = ToggleButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(label, maxLines = 1)
    }
}
