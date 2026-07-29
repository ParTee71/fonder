package se.partee71.fonder.ui.diagram

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
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
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.common.Insets
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.ChartPeriodFilter
import se.partee71.fonder.domain.usecase.PurchaseMarkerFilter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Delad linjediagram-komponent (regel 4) som wrappar Vico — resten av appen ska aldrig
 * röra Vico-API:t direkt. Används för fondens kurshistorik i Fonddetalj (issue #7).
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
 * #55) — det kan vara flera. Se [PurchaseMarkerFilter].
 *
 * @param points (epochDay, NAV), i stigande datumordning. Tom lista ritar inget — visa ett
 *   eget tomt-tillstånd (`EmptyState`) i anropande skärm i stället.
 * @param purchaseEpochDays köpdagar (epochDay) för fonden, i valfri ordning. Markeras bara de
 *   som faller inom den period diagrammet just nu visar.
 */
@Composable
fun FundLineChart(
    points: List<Pair<Long, Double>>,
    purchaseEpochDays: List<Long> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var period by remember { mutableStateOf(ChartPeriodFilter.Period.EN_MANAD) }
    val windowedPoints = remember(points, period) { ChartPeriodFilter.apply(points, period) }
    val markerEpochDays = remember(windowedPoints, purchaseEpochDays) {
        PurchaseMarkerFilter.apply(windowedPoints, purchaseEpochDays)
    }
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(windowedPoints) {
        if (windowedPoints.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineSeries {
                series(x = windowedPoints.map { it.first }, y = windowedPoints.map { it.second })
            }
        }
    }

    Column(modifier = modifier) {
        val purchaseMarker = rememberPurchaseMarker()
        // Nyckling på perioden: byter man period ska diagrammet zooma/skrolla om till den nya
        // datamängden direkt, inte behålla ett nyp/drag-läge som hörde till den förra periodens
        // (helt andra) datavolym.
        key(period) {
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(rangeProvider = PriceRangeProvider),
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
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            PeriodChip(ChartPeriodFilter.Period.EN_MANAD, period, R.string.fond_chart_period_1man) { period = it }
            PeriodChip(ChartPeriodFilter.Period.TRE_MANADER, period, R.string.fond_chart_period_3man) { period = it }
            PeriodChip(ChartPeriodFilter.Period.ETT_AR, period, R.string.fond_chart_period_1ar) { period = it }
            PeriodChip(ChartPeriodFilter.Period.ALLT, period, R.string.fond_chart_period_allt) { period = it }
        }
    }
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
