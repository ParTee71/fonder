package se.partee71.fonder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.AnalysisGuidance
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.ui.theme.StatusColors

/**
 * Delade byggstenar för att visa [FundAnalysisCalc]-status (issue #16, regel 4) — återanvänds
 * mellan Fonddetaljs statusbanner och Hems summeringskort (ANA-3, HEM-4) så de alltid visar
 * samma titel/triggertexter för samma status. Domänlagret bär bara rådata (nivå + numeriska
 * detaljer) — Swedish text komponeras här, samma princip som POR-3/SLD-2/HEM-2.
 */

@Composable
fun statusColor(level: FundAnalysisCalc.SignalLevel) = when (level) {
    FundAnalysisCalc.SignalLevel.GRON -> StatusColors.gron
    FundAnalysisCalc.SignalLevel.GUL -> StatusColors.gul
    FundAnalysisCalc.SignalLevel.ROD -> StatusColors.rod
}

@Composable
fun statusTitle(level: FundAnalysisCalc.SignalLevel) = when (level) {
    FundAnalysisCalc.SignalLevel.GRON -> stringResource(R.string.analys_status_gron)
    FundAnalysisCalc.SignalLevel.GUL -> stringResource(R.string.analys_status_gul)
    FundAnalysisCalc.SignalLevel.ROD -> stringResource(R.string.analys_status_rod)
}

/**
 * Svensk kontexttext för varje härledd [AnalysisGuidance.GuidanceKey] (issue #22, ANA-6) — sätter
 * signalerna i sammanhang för en nybörjare. Domänlagret bär bara nycklarna, texten komponeras här
 * (samma princip som [statusTriggerMessages]). Alltid förklarande, aldrig ett köp-/säljråd (ANA-3).
 */
@Composable
fun guidanceMessages(analysis: FundAnalysisCalc.Analysis): List<String> =
    AnalysisGuidance.guidanceFor(analysis).map { key ->
        when (key) {
            AnalysisGuidance.GuidanceKey.NEDGANG_MEN_PLUS_MOT_GAV -> stringResource(R.string.analys_guidance_plus_mot_gav)
            AnalysisGuidance.GuidanceKey.DJUP_NEDGANG_TIDSHORISONT -> stringResource(R.string.analys_guidance_djup_nedgang)
            AnalysisGuidance.GuidanceKey.UNDER_TREND_INTE_SALJBUD -> stringResource(R.string.analys_guidance_under_trend)
            AnalysisGuidance.GuidanceKey.SVAG_MOT_PORTFOLJ -> stringResource(R.string.analys_guidance_svag_portfolj)
            AnalysisGuidance.GuidanceKey.LUGNT_LAGE -> stringResource(R.string.analys_guidance_lugnt)
        }
    }

/** Svensk text för varje triggad (icke-grön) signal i [analysis], i fast ordning (avstånd, trend, momentum). */
@Composable
fun statusTriggerMessages(analysis: FundAnalysisCalc.Analysis): List<String> {
    val messages = mutableListOf<String>()
    analysis.distanceFromHigh?.let { signal ->
        if (signal.level != FundAnalysisCalc.SignalLevel.GRON) {
            messages += stringResource(R.string.format_analys_signal_distance, MoneyFormat.percentSigned(signal.distanceFraction))
        }
    }
    analysis.trend?.let { signal ->
        if (signal.level != FundAnalysisCalc.SignalLevel.GRON) messages += stringResource(R.string.analys_signal_trend)
    }
    analysis.momentum?.let { signal ->
        if (signal.level != FundAnalysisCalc.SignalLevel.GRON) messages += stringResource(R.string.analys_signal_momentum)
    }
    return messages
}

@Composable
fun StatusDot(level: FundAnalysisCalc.SignalLevel, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(10.dp).clip(CircleShape).background(statusColor(level)))
}

/** Statusbanner (färg + rubrik + triggertexter) ovanför kurshistoriken i Fonddetalj (ANA-3). */
@Composable
fun AnalysisStatusBanner(analysis: FundAnalysisCalc.Analysis?, modifier: Modifier = Modifier) {
    val level = analysis?.status
    if (analysis == null || level == null) {
        Card(modifier = modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.analys_status_insufficient),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = statusColor(level).copy(alpha = 0.14f)),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(level, modifier = Modifier.padding(end = 12.dp))
            Column {
                Text(statusTitle(level), style = MaterialTheme.typography.titleMedium)
                val messages = statusTriggerMessages(analysis)
                if (messages.isNotEmpty()) {
                    Text(
                        messages.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Neutralt kontextkort under statusbannern i Fonddetalj (ANA-6, issue #22) — förklarar för en
 * nybörjare vad signalerna innebär i sammanhang (t.ex. att kursen ligger under toppen men
 * fortfarande över GAV, eller att en nedgång inte i sig är ett säljläge). Visas inget om analysen
 * saknar vägledning (otillräcklig data, ANA-4) — då renderas kortet inte alls. Aldrig rådgivande.
 */
@Composable
fun AnalysisGuidanceCard(analysis: FundAnalysisCalc.Analysis, modifier: Modifier = Modifier) {
    val messages = guidanceMessages(analysis)
    if (messages.isEmpty()) return
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            messages.forEachIndexed { index, message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (index == 0) Modifier else Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
