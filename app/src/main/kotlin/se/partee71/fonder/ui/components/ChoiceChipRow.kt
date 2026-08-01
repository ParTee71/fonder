package se.partee71.fonder.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Delad rad av ömsesidigt uteslutande [FilterChip] (regel 4) — extraherad ur den tidigare
 * privata `ThemeChip`-raden i Inställningar (issue #27) så Riskprofilens enkät (SET-3, issue
 * #68) kan återanvända samma val-mönster i stället för en egen variant. Horisontellt
 * skrollbar: en fråga med fler/längre alternativ än vad som får plats ska aldrig klippa bort
 * ett val i stället för att göra det nåbart (samma princip som UI-5).
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
    Row(modifier = modifier.horizontalScroll(rememberScrollState()).selectableGroup()) {
        options.forEachIndexed { index, option ->
            if (index > 0) Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(optionLabel(option)) },
            )
        }
    }
}
