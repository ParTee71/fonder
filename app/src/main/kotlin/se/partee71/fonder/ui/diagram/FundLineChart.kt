package se.partee71.fonder.ui.diagram

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
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
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Delad linjediagram-komponent (regel 4) som wrappar Vico — resten av appen ska aldrig
 * röra Vico-API:t direkt. Används för fondens kurshistorik i Fonddetalj (issue #7).
 *
 * Zoomar som standard in till den senaste månaden och skrollar till slutet, så den senaste
 * kursen alltid syns direkt i stället för att drunkna i hela historiken — hela historiken
 * är fortfarande nåbar genom att nypa ut/dra i diagrammet (Vicos inbyggda pinch/pan).
 * Y-axeln pads runt kursens egna min/max i stället för att alltid inkludera noll, se
 * [PriceRangeProvider].
 *
 * @param points (epochDay, NAV), i stigande datumordning. Tom lista ritar inget — visa ett
 *   eget tomt-tillstånd (`EmptyState`) i anropande skärm i stället.
 */
@Composable
fun FundLineChart(
    points: List<Pair<Long, Double>>,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(points) {
        if (points.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineSeries {
                series(x = points.map { it.first }, y = points.map { it.second })
            }
        }
    }

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(rangeProvider = PriceRangeProvider),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = DateValueFormatter),
        ),
        modelProducer = modelProducer,
        scrollState = rememberVicoScrollState(initialScroll = Scroll.Absolute.End),
        zoomState = rememberVicoZoomState(
            // x-värdena är epoch-dagar, så en enhet är en kalenderdag — Zoom.x(30.0) visar
            // därför ungefär den senaste månaden. Har fonden kortare historik än så vinner
            // Zoom.Content (hela historiken) i stället, så vyn aldrig blir tommare än datan.
            initialZoom = remember { Zoom.max(Zoom.Content, Zoom.x(DEFAULT_ZOOM_WINDOW_DAYS)) },
            minZoom = Zoom.Content,
        ),
        modifier = modifier.fillMaxWidth().height(220.dp),
    )
}

/** Standardzoomens fönsterbredd i dagar — se [FundLineChart]. */
private const val DEFAULT_ZOOM_WINDOW_DAYS = 30.0

/**
 * Vicos inbyggda `CartesianLayerRangeProvider.auto()` tvingar alltid y-axeln att inkludera
 * noll (`minY.coerceAtMost(0.0)`) — för fondkurser, som alltid är positiva och sällan i
 * närheten av noll, klämmer det ihop kursens faktiska rörelse mot en avlägsen nollinje i
 * stället för att fylla diagrammets höjd. I stället pads vi runt datans egna min/max, så
 * även små men relevanta rörelser syns.
 *
 * Baseras på hela historiken (Vico räknar om intervallet när modellen ändras, inte när
 * användaren zoomar/skrollar) — samma intervall oavsett vilken del av historiken som visas.
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
