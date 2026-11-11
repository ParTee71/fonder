package se.partee71.fonder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.theme.MonoAmountStyle

/** Stapelns tjocklek — 10 dp med helt rundade ändar (M3 Expressive, UI-12). */
private val BAR_HEIGHT = 10.dp

/** Målstrecket (HEM-7): 2 dp brett och 3 dp utanför stapeln upptill och nedtill (mockup C2, issue #238). */
private val TARGET_MARK_WIDTH = 2.dp
private val TARGET_MARK_OVERHANG = 3.dp

/**
 * Delad rad för en kategoris andel av en helhet (POR-9, issue #66) — etikett, procent
 * ([MoneyFormat.percent]) och en bredd-proportionell stapel. Ersätter ett tårtdiagram: Vico
 * (TP-12) är kartesiskt utan tårtdiagram-stöd, och den här återhållsamma, textdrivna raden
 * matchar appens stil bättre (regel 4) — återanvänds för fondtyp-, region- och
 * index/aktivt-sektionerna i Portfölj och riskkortets nivåer (HEM-7), ingen ny variant per dimension.
 *
 * @param color stapelns färg. Okänd-hinkar (t.ex. "Okänd region") ska skickas med en dämpad
 *   färg (`MaterialTheme.colorScheme.outline`) så de syns som skilda från de riktiga
 *   kategorierna även rent visuellt, inte bara i sin plats sist i listan.
 * @param target målandelen när raden jämförs mot ett mål (HEM-7, issue #238): ritas som ett
 *   streck i [targetColor], procenttexten blir "52 % · mål 45 %" och skärmläsaren läser
 *   "Nivå 4: 52 %, mål 45 %".
 */
@Composable
fun ExposureBar(
    label: String,
    fraction: Double,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    target: Double? = null,
    targetColor: Color = MaterialTheme.colorScheme.secondary
) {
    val percentText = MoneyFormat.percent(fraction)
    val targetText = target?.let { MoneyFormat.percent(it) }
    // Med mål läses raden som en mening — strecket i sig syns inte för en skärmläsare, och två lösa
    // procenttal utan ordet "mål" vore tvetydiga.
    val description = targetText?.let { stringResource(R.string.format_exposure_bar_target_description, label, percentText, it) }
    val semantics = if (description != null) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).then(semantics)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Vikt + ellips av samma skäl som i [PeriodRow] (issue #78): utan dem kunde en lång
            // kategorietikett äta upp hela raden och lämna noll bredd till procenten.
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
            )
            Text(
                targetText?.let { stringResource(R.string.format_exposure_bar_target, percentText, it) } ?: percentText,
                style = MonoAmountStyle.merge(MaterialTheme.typography.bodyMedium)
            )
        }
        if (target == null) {
            Bar(fraction, color, Modifier.padding(top = 6.dp))
        } else {
            BarWithTarget(fraction, color, target, targetColor)
        }
    }
}

@Composable
private fun Bar(fraction: Double, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
                .background(color, CircleShape)
        )
    }
}

/**
 * Stapeln med ett lodrätt målstreck vid [target] som sticker ut ovanför och under stapeln, så
 * målet syns även när stapeln är tom eller full. Strecket stannar inom stapelns bredd vid 0 % och 100 %.
 */
@Composable
private fun BarWithTarget(fraction: Double, color: Color, target: Double, targetColor: Color) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp - TARGET_MARK_OVERHANG)
            .height(BAR_HEIGHT + TARGET_MARK_OVERHANG * 2)
    ) {
        Bar(fraction, color, Modifier.align(Alignment.CenterStart))
        Box(
            modifier = Modifier
                .offset(x = (maxWidth - TARGET_MARK_WIDTH) * target.toFloat().coerceIn(0f, 1f))
                .width(TARGET_MARK_WIDTH)
                .fillMaxHeight()
                .background(targetColor)
        )
    }
}
