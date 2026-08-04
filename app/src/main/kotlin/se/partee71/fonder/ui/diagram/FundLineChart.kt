package se.partee71.fonder.ui.diagram

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.shape.rounded
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.common.Insets
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.ChartPeriodFilter
import se.partee71.fonder.domain.usecase.ChartSeriesNormalizer
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.PurchaseMarkerFilter
import se.partee71.fonder.ui.theme.ChartSeriesColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/** En namngiven kursserie att rita bredvid innehavets egen i [FundLineChart] (ANA-11, issue #85). */
data class ChartSeries(val label: String, val points: List<Pair<Long, Double>>)

/**
 * Delad linjediagram-komponent (regel 4) som wrappar Vico — resten av appen ska aldrig
 * röra Vico-API:t direkt. Används för fondens kurshistorik i Fonddetalj (issue #7) och, med
 * [comparisonSeries], för jämförelsen mellan ett innehav och en föreslagen fond (ANA-11).
 *
 * En periodväljare (1 mån/3 mån/1 år/Allt, issue #51) styr vilken del av [points] som skickas
 * till Vico — bara den valda periodens punkter, inte hela historiken zoomad. Både x- och
 * y-axeln (se [PriceRangeProvider]) följer därför automatiskt den period användaren faktiskt
 * tittar på, i stället för att alltid räknas ut från hela historikens min/max (känd
 * begränsning i den tidigare fasta zoomlösningen, #47/#49 — Vico räknar bara om axlarna när
 * modellen ändras, inte när användaren nyper/drar). Diagrammet visar hela den valda perioden
 * (`Zoom.Content`) skrollat till slutet, så den senaste kursen alltid syns direkt.
 *
 * Köptillfällen som ingår i den visade perioden markeras med en linje och ett datum (issue
 * #55) — det kan vara flera. Se [PurchaseMarkerFilter]. Markörerna hör bara till [points]:
 * i en jämförelse är köpen gjorda i innehavet, inte i kandidaten.
 *
 * Med minst en [comparisonSeries] **indexeras** alla serier till 100 vid periodens första
 * gemensamma dag ([ChartSeriesNormalizer]) — rå NAV går inte att jämföra mellan två fonder på
 * en gemensam y-axel. Serierna får då etiketter i en teckenförklaring, och beskrivningen säger
 * att skalan är index, inte kronor.
 *
 * @param points (epochDay, NAV), i stigande datumordning. Tom lista ritar inget — visa ett
 *   eget tomt-tillstånd (`EmptyState`) i anropande skärm i stället.
 * @param purchaseEpochDays köpdagar (epochDay) för fonden, i valfri ordning. Markeras bara de
 *   som faller inom den period diagrammet just nu visar.
 * @param primaryLabel etikett för [points] i teckenförklaringen — bara relevant tillsammans
 *   med [comparisonSeries]; utan jämförelse ritas ingen teckenförklaring.
 * @param comparisonSeries ytterligare serier att jämföra mot, beskurna till samma period som
 *   [points] innan de indexeras.
 */
@Composable
fun FundLineChart(
    points: List<Pair<Long, Double>>,
    purchaseEpochDays: List<Long> = emptyList(),
    primaryLabel: String? = null,
    comparisonSeries: List<ChartSeries> = emptyList(),
    modifier: Modifier = Modifier,
) {
    // rememberSaveable: vald period ska överleva rotation, annars hoppar diagrammet
    // tillbaka till 1 månad mitt i en jämförelse (issue #78).
    var period by rememberSaveable { mutableStateOf(ChartPeriodFilter.Period.EN_MANAD) }
    val windowedPoints = remember(points, period) { ChartPeriodFilter.apply(points, period) }

    // Jämförelseserierna beskärs till **innehavets** fönster, inte till sitt eget: perioden
    // räknas bakåt från den senaste punkten i datan (se ChartPeriodFilter), och en kandidat
    // vars historik slutar en annan dag hade annars fått ett förskjutet fönster — två kurvor
    // över olika tidsspann ser ut som en jämförelse men är det inte.
    val normalized = remember(windowedPoints, comparisonSeries) {
        if (comparisonSeries.isEmpty()) {
            ChartSeriesNormalizer.Result(listOf(windowedPoints), indexed = false, partial = false, baseEpochDay = null)
        } else {
            val from = windowedPoints.minOfOrNull { it.first }
            val windowedComparisons = comparisonSeries.map { series ->
                if (from == null) series.points else series.points.filter { it.first >= from }
            }
            ChartSeriesNormalizer.index(listOf(windowedPoints) + windowedComparisons)
        }
    }
    val primaryPoints = normalized.series.firstOrNull().orEmpty()
    val markerEpochDays = remember(primaryPoints, purchaseEpochDays) {
        PurchaseMarkerFilter.apply(primaryPoints, purchaseEpochDays)
    }
    val defaultPrimaryLabel = stringResource(R.string.fond_chart_series_holding)
    val labels = listOf(primaryLabel ?: defaultPrimaryLabel) + comparisonSeries.map { it.label }
    // Tomma serier tas bort **tillsammans med sin etikett**, så teckenförklaringens färger
    // fortsätter peka på rätt kurva även om en kandidats historik saknas.
    val visibleSeries = normalized.series.zip(labels).filter { (series, _) -> series.isNotEmpty() }
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(visibleSeries) {
        if (visibleSeries.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineSeries {
                visibleSeries.forEach { (points, _) ->
                    series(x = points.map { it.first }, y = points.map { it.second })
                }
            }
        }
    }

    // Diagrammet är en ren canvas — utan semantik är kursutvecklingen osynlig för en
    // skärmläsare, i strid med att appen annars aldrig bär information i enbart färg/form
    // (issue #78). Beskrivningen sammanfattar det diagrammet faktiskt visar: vald period,
    // antal punkter och kursspannet — eller, i en jämförelse, vilka fonder som jämförs och
    // att skalan är ett index.
    val chartDescription = when {
        visibleSeries.isEmpty() -> stringResource(R.string.fond_chart_description_empty)
        normalized.indexed -> stringResource(
            R.string.format_fond_chart_comparison_description,
            stringResource(periodLabelRes(period)),
            visibleSeries.joinToString(", ") { (_, label) -> label },
        )
        // Beskrivs utifrån den serie som faktiskt ritas — inte utifrån [points], som kan vara
        // tom i det udda fallet att bara en jämförelseserie har data (annars kraschade min/max).
        else -> {
            val described = visibleSeries.first().first
            stringResource(
                R.string.format_fond_chart_description,
                stringResource(periodLabelRes(period)),
                described.size,
                MoneyFormat.kr(described.minOf { it.second }),
                MoneyFormat.kr(described.maxOf { it.second }),
            )
        }
    }

    val seriesColors = seriesColors()
    Column(modifier = modifier) {
        val purchaseMarker = rememberPurchaseMarker()
        val lineProvider = rememberSeriesLineProvider(seriesColors)
        // Nyckling på perioden: byter man period ska diagrammet zooma/skrolla om till den nya
        // datamängden direkt, inte behålla ett nyp/drag-läge som hörde till den förra periodens
        // (helt andra) datavolym.
        key(period) {
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(lineProvider = lineProvider, rangeProvider = PriceRangeProvider),
                    startAxis = VerticalAxis.rememberStart(),
                    bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = DateValueFormatter),
                    persistentMarkers = { _ -> markerEpochDays.forEach { day -> purchaseMarker at day } },
                ),
                modelProducer = modelProducer,
                scrollState = rememberVicoScrollState(initialScroll = Scroll.Absolute.End),
                // Vicos standardzoom (`Zoom.max(Zoom.fixed(), Zoom.Content)`) tillåter aldrig
                // mer utzoomat än "naturligt" punktavstånd — för en period med fler punkter än
                // vad som får plats i den bredden (t.ex. "1 år"/"Allt") visades då bara svansen
                // närmast slutet i stället för hela den valda perioden. `Zoom.Content` ensamt
                // visar alltid ALLA punkter som skickats in, oavsett antal.
                zoomState = rememberVicoZoomState(initialZoom = Zoom.Content),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .semantics { contentDescription = chartDescription },
            )
        }
        if (visibleSeries.size > 1) {
            ChartLegend(
                labels = visibleSeries.map { (_, label) -> label },
                colors = seriesColors,
                indexed = normalized.indexed,
                partial = normalized.partial,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            ChartPeriodFilter.Period.entries.forEach { option ->
                PeriodChip(option, period, periodLabelRes(option)) { period = it }
            }
        }
    }
}

/**
 * Kurvornas färger, i seriernas ordning — appens fasta palett (UI-1, se [ChartSeriesColors])
 * i stället för Vicos standardfärger, så en jämförelse ser ut som resten av appen. Delad mellan
 * diagrammet och teckenförklaringen; hade de valt färg var för sig kunde de glida isär och
 * förklaringen peka på fel kurva.
 */
@Composable
private fun seriesColors(): List<Color> =
    listOf(ChartSeriesColors.holding, ChartSeriesColors.candidate)

/**
 * Linjerna Vico ritar serierna med, i samma ordning som [seriesColors]. Båda skapas alltid,
 * även när bara en serie visas: en `remember`-baserad fabrik som ibland anropas och ibland inte
 * gör kompositionen instabil, och en oanvänd linje kostar ingenting.
 */
@Composable
private fun rememberSeriesLineProvider(colors: List<Color>): LineCartesianLayer.LineProvider {
    val primary = LineCartesianLayer.rememberLine(LineCartesianLayer.LineFill.single(fill(colors[0])))
    val comparison = LineCartesianLayer.rememberLine(LineCartesianLayer.LineFill.single(fill(colors[1])))
    return LineCartesianLayer.LineProvider.series(primary, comparison)
}

/**
 * Teckenförklaring för en jämförelse (ANA-11) — färgprick **plus** fondnamn, aldrig färgen
 * ensam (UI-3). Säger också rakt ut att skalan är ett index och, när kandidatens historik är
 * kortare än perioden, att jämförelsen bara täcker den gemensamma delen (samma
 * markera-hellre-än-gissa-princip som HEM-2/ANA-4).
 */
@Composable
private fun ChartLegend(
    labels: List<String>,
    colors: List<Color>,
    indexed: Boolean,
    partial: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        labels.forEachIndexed { index, label ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(colors[index % colors.size]),
                )
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (indexed) {
            Text(
                stringResource(R.string.fond_chart_indexed_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (partial) {
            Text(
                stringResource(R.string.fond_chart_partial_comparison),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Etikett för en diagramperiod — delad mellan chipparna och diagrammets semantik. */
private fun periodLabelRes(period: ChartPeriodFilter.Period): Int = when (period) {
    ChartPeriodFilter.Period.EN_MANAD -> R.string.fond_chart_period_1man
    ChartPeriodFilter.Period.TRE_MANADER -> R.string.fond_chart_period_3man
    ChartPeriodFilter.Period.ETT_AR -> R.string.fond_chart_period_1ar
    ChartPeriodFilter.Period.ALLT -> R.string.fond_chart_period_allt
}

@Composable
private fun PeriodChip(
    value: ChartPeriodFilter.Period,
    selected: ChartPeriodFilter.Period,
    labelRes: Int,
    onSelect: (ChartPeriodFilter.Period) -> Unit,
) {
    FilterChip(
        selected = value == selected,
        onClick = { onSelect(value) },
        label = { Text(stringResource(labelRes)) },
    )
}

/**
 * Persistent markör (issue #55) för ett köptillfälle — en vertikal linje och en etikett med
 * köpdagens datum, i appens mässingsaccent (`secondary`) så den syns tydligt mot kurslinjen
 * utan att kunna förväxlas med den.
 */
@Composable
private fun rememberPurchaseMarker(): CartesianMarker {
    val label = rememberTextComponent(
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        textSize = 11.sp,
        padding = Insets(horizontalDp = 6f, verticalDp = 3f),
        background = rememberShapeComponent(
            fill = fill(MaterialTheme.colorScheme.secondaryContainer),
            shape = CorneredShape.rounded(4.dp),
        ),
    )
    val guideline = rememberLineComponent(
        fill = fill(MaterialTheme.colorScheme.secondary),
        thickness = 1.dp,
    )
    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = PurchaseValueFormatter,
        guideline = guideline,
    )
}

/**
 * Visar köpdagens datum i markörens etikett — kursens y-värde (Vicos standardformatterare)
 * syns redan i diagrammet självt, så datumet är den nya informationen markören bidrar med.
 */
private object PurchaseValueFormatter : DefaultCartesianMarker.ValueFormatter {
    private val format = DateTimeFormatter.ofPattern("yy-MM-dd")

    override fun format(context: CartesianDrawingContext, targets: List<CartesianMarker.Target>): CharSequence {
        val x = targets.firstOrNull()?.x ?: return ""
        return LocalDate.ofEpochDay(x.roundToLong()).format(format)
    }
}

/**
 * Vicos inbyggda `CartesianLayerRangeProvider.auto()` tvingar alltid y-axeln att inkludera
 * noll (`minY.coerceAtMost(0.0)`) — för fondkurser, som alltid är positiva och sällan i
 * närheten av noll, klämmer det ihop kursens faktiska rörelse mot en avlägsen nollinje i
 * stället för att fylla diagrammets höjd. I stället pads vi runt datans egna min/max, så
 * även små men relevanta rörelser syns.
 *
 * Räknas ut från vad som faktiskt skickas till Vico, dvs. den valda periodens punkter (se
 * [FundLineChart]) — inte hela historiken.
 */
internal object PriceRangeProvider : CartesianLayerRangeProvider {
    override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double =
        minY - padding(minY, maxY)

    override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double =
        maxY + padding(minY, maxY)

    /** Andel av kursintervallet som läggs till som marginal ovanför/under min/max. */
    private const val PADDING_FRACTION = 0.1

    private fun padding(minY: Double, maxY: Double): Double {
        val range = maxY - minY
        return if (range > 0.0) range * PADDING_FRACTION else max(abs(maxY), 1.0) * PADDING_FRACTION
    }
}

/**
 * X-värdena är epoch-dagar, och utan formaterare skrev Vico ut dem som råa tal ("20535",
 * "20557" — obegripligt för en läsare, issue #41). Formateras som datum i stället.
 *
 * Kort format (`yy-MM-dd`) eftersom axeln är smal på en telefon; svensk datumordning så den
 * går att läsa i samma riktning som kurstabellen under diagrammet.
 */
private object DateValueFormatter : CartesianValueFormatter {
    private val format = DateTimeFormatter.ofPattern("yy-MM-dd")

    override fun format(
        context: CartesianMeasuringContext,
        value: Double,
        verticalAxisPosition: Axis.Position.Vertical?,
    ): CharSequence = LocalDate.ofEpochDay(value.roundToLong()).format(format)
}
