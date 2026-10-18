package se.partee71.fonder.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import se.partee71.fonder.R
import se.partee71.fonder.ui.theme.MonoAmountStyle

/**
 * Väljare för en risknivå 1–7 (UI-10, ANA-13) — sju lika breda knappar i en rad, **en** vald
 * (fylld i primärfärgen och helt rund, M3 Expressive-formmorf) och högst **en** markerad med en
 * ram ([marked], t.ex. säljfondens egen nivå i Pågående byte). Siffran står alltid i text; för
 * skärmläsaren läses varje knapp som ett radioval "Risknivå 4 av 7" (samma text som `RiskBadge`), med [markedDescription] tillagd
 * på den markerade, så ramen aldrig är ensam bärare av sin betydelse (UI-3).
 *
 * Skild från [ChoiceChipRow]: nivåerna är en skala, inte en sammanhängande grupp av ord, och
 * ska få plats utan att skrolla — samma färger ([choiceButtonColors]) och samma radiosemantik.
 * Tryckytan är 48 dp även när knappen är smalare än så synligt (`minimumInteractiveComponentSize`
 * i `ToggleButton`).
 *
 * @param selected den valda nivån, eller null när ingen nivå är vald än.
 * @param marked nivån som får en ram, eller null.
 * @param markedDescription vad ramen betyder, för skärmläsaren — läses efter nivån.
 * @param enabled false låser väljaren med valet synligt.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RiskLevelPicker(
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    marked: Int? = null,
    markedDescription: String? = null,
    enabled: Boolean = true,
    levels: IntRange = RISK_LEVELS
) {
    val shapes = ToggleButtonShapes(
        shape = MaterialTheme.shapes.extraSmall,
        pressedShape = MaterialTheme.shapes.small,
        checkedShape = CircleShape
    )
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        levels.forEach { level ->
            val isSelected = level == selected
            val isMarked = level == marked
            val description = stringResource(R.string.format_risk_level_description, level, levels.last).let { base ->
                if (isMarked && markedDescription != null) "$base, $markedDescription" else base
            }
            ToggleButton(
                checked = isSelected,
                onCheckedChange = { onSelect(level) },
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {
                        role = Role.RadioButton
                        this.selected = isSelected
                        contentDescription = description
                    },
                shapes = shapes,
                colors = choiceButtonColors(),
                border = if (isMarked) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurfaceVariant) else null,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    level.toString(),
                    style = MonoAmountStyle.merge(MaterialTheme.typography.titleSmall),
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics { }
                )
            }
        }
    }
}

/** Fondernas riskskala (SRI 1–7). */
val RISK_LEVELS: IntRange = 1..7
