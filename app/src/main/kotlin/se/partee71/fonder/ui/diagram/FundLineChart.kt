package se.partee71.fonder.ui.diagram

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.ChartPeriodFilter
import se.partee71.fonder.domain.usecase.ChartSeriesNormalizer
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.PurchaseMarkerFilter
import se.partee71.fonder.ui.components.ConnectedChoiceButton
import se.partee71.fonder.ui.components.ConnectedChoiceSpacing
import se.partee71.fonder.ui.components.WorkingIndicator
import se.partee71.fonder.ui.theme.ChartSeriesColors
import se.partee71.fonder.ui.theme.MonoAmountStyle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/** En namngiven kursserie att rita bredvid innehavets egen i [FundLineChart] (ANA-11, issue #85). */
data class ChartSeries(val label: String, val points: List<Pair<Long, Double>>)

/**
 * Vad y-värdena i [FundLineChart] betyder — avgör axelformatering, intervall och beskrivning.
 *
 * [KRONOR] är fondkurser (NAV) och [PROCENT] avkastning som andel (0.12 = +12 %), t.ex.
 * portföljens avkastningskurva på Hem (HEM-9). Skillnaden ligger i **etiketterna**, inte i
 * intervallet: båda pads runt datans egna min/max (se [PriceRangeProvider], issue #49), så
 * rörelsen fyller diagramhöjden i stället för att tryckas ihop mot en fast referenslinje. Att en
 * avkastningskurva ligger plus eller minus syns på axelns tecken (`+12,3 %` / `−5,0 %`) — den
 * behöver alltså inte tvinga in nollinjen i bilden, och gör det inte heller.
 *
 * Serier i procentläge indexeras aldrig (se [FundLineChart]).
 */
enum class ChartValueAxis { KRONOR, PROCENT }

/**
 * Periodväljarens rad. Den skrollar (sex perioder får inte plats på en telefon), så en chip
 * utanför vyn är inte komponerad och `performScrollTo` kan inte hitta den — instrumenttester
 * måste gå via `performScrollToIndex` på den här taggen i stället (UI-5, samma sak som
 * `PORTFOLJ_LIST_TEST_TAG` löser för innehavslistan, issue #66).
 */
const val CHART_PERIOD_ROW_TEST_TAG = "chart_period_row"

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
 * @param valueAxis vad y-värdena betyder — kronor (NAV) eller procent (avkastning). Se
 *   [ChartValueAxis]; i procentläge indexeras serierna aldrig.
 * @param movingAverage 200-dagarssnittet (ANA-14, [se.partee71.fonder.domain.usecase.MovingAverageCalc])
 *   för [points], räknat ur **hela** historiken — beskärs här till den valda periodens fönster,
 *   så också en kort period får en snittlinje från sin första punkt. Ritas prickat i
 *   [ChartSeriesColors.movingAverage] utan yta, med en teckenförklaring som visar senaste kurs och
 *   senaste snitt. Bara för en ensam kurva i kronor: med [comparisonSeries] eller i procentläge
 *   ignoreras den. `null` (förval) eller en serie utan punkter i perioden ritar ingen linje och
 *   ingen förklaring (ANA-4-principen).
 */
@Composable
fun FundLineChart(
    points: List<Pair<Long, Double>>,
    purchaseEpochDays: List<Long> = emptyList(),
    primaryLabel: String? = null,
    comparisonSeries: List<ChartSeries> = emptyList(),
    valueAxis: ChartValueAxis = ChartValueAxis.KRONOR,
    movingAverage: List<Pair<Long, Double>>? = null,
    working: Boolean = false,
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
    val normalized = remember(windowedPoints, comparisonSeries, valueAxis) {
        val from = windowedPoints.minOfOrNull { it.first }
        val windowedComparisons = comparisonSeries.map { series ->
            if (from == null) series.points else series.points.filter { it.first >= from }
        }
        val all = listOf(windowedPoints) + windowedComparisons
        when {
            // Avkastningskurvor nollställs mot periodens första gemensamma dag, så alla kurvor
            // startar på 0 % där. Utan det ritas två *ackumulerade* avkastningar sedan
            // respektive start som parallella band på olika höjd, och den fråga en enmånadsvy
            // ställer — vilken växte mest den här månaden? — går inte att läsa ur bilden.
            valueAxis == ChartValueAxis.PROCENT -> ChartSeriesNormalizer.rebaseReturns(all)
            // Rå NAV går inte att jämföra mellan två fonder på en gemensam y-axel; en ensam
            // kurva ska däremot visa fondens verkliga kurs i kronor (se ChartSeriesNormalizer).
            comparisonSeries.isEmpty() ->
                ChartSeriesNormalizer.Result(listOf(windowedPoints), indexed = false, partial = false, baseEpochDay = null)
            else -> ChartSeriesNormalizer.index(all)
        }
    }
    val primaryPoints = normalized.series.firstOrNull().orEmpty()
    // Snittet hör bara till en ensam kurva i kronor: i ett indexerat eller nollställt läge hade
    // det haft fel skala, och bredvid en jämförelse vore det en tredje kurva utan namn i bilden.
    val windowedAverage = remember(windowedPoints, movingAverage, comparisonSeries, valueAxis) {
        val from = windowedPoints.minOfOrNull { it.first }
        if (movingAverage == null || from == null || comparisonSeries.isNotEmpty() || valueAxis != ChartValueAxis.KRONOR) {
            emptyList()
        } else {
            movingAverage.filter { it.first >= from }
        }
    }
    val markerEpochDays = remember(primaryPoints, purchaseEpochDays) {
        PurchaseMarkerFilter.apply(primaryPoints, purchaseEpochDays)
    }
    val defaultPrimaryLabel = stringResource(R.string.fond_chart_series_holding)
    val labels = listOf(primaryLabel ?: defaultPrimaryLabel) + comparisonSeries.map { it.label }
    // Tomma serier tas bort **tillsammans med sin etikett**, så teckenförklaringens färger
    // fortsätter peka på rätt kurva även om en kandidats historik saknas.
    val visibleSeries = normalized.series.zip(labels).filter { (series, _) -> series.isNotEmpty() }
    // Modellen byggs synkront i kompositionen (som i Dagboken) i stället för via
    // CartesianChartModelProducer, som räknar på en annan tråd: diagrammet ritas då i samma
    // bildruta som datan kommer, och skärmdumpstesterna slipper vänta på en bakgrundstråd.
    val showMovingAverage = visibleSeries.size == 1 && windowedAverage.isNotEmpty()
    val model = remember(visibleSeries, windowedAverage, showMovingAverage) {
        visibleSeries.takeIf { it.isNotEmpty() }?.let { lines ->
            chartModel(lines.map { (points, _) -> points } + if (showMovingAverage) listOf(windowedAverage) else emptyList())
        }
    }

    // Diagrammet är en ren canvas — utan semantik är kursutvecklingen osynlig för en
    // skärmläsare, i strid med att appen annars aldrig bär information i enbart färg/form
    // (issue #78). Beskrivningen sammanfattar det diagrammet faktiskt visar: vald period,
    // antal punkter och kursspannet — eller, i en jämförelse, vilka fonder som jämförs och
    // att skalan är ett index.
    val chartDescription = when {
        visibleSeries.isEmpty() -> stringResource(R.string.fond_chart_description_empty)
        // Procentläget beskrivs i procent och namnger sina kurvor — informationen får inte bara
        // ligga i linjernas färg (UI-3), och "kr" i beskrivningen hade dessutom varit fel enhet.
        valueAxis == ChartValueAxis.PROCENT -> {
            val described = visibleSeries.first().first
            stringResource(
                R.string.format_fond_chart_description_percent,
                stringResource(periodLabelRes(period)),
                described.size,
                MoneyFormat.percentSigned(described.minOf { it.second }),
                MoneyFormat.percentSigned(described.maxOf { it.second }),
                visibleSeries.joinToString(", ") { (_, label) -> label },
            )
        }
        normalized.indexed -> stringResource(
            R.string.format_fond_chart_comparison_description,
            stringResource(periodLabelRes(period)),
            visibleSeries.joinToString(", ") { (_, label) -> label },
        )
        // Beskrivs utifrån den serie som faktiskt ritas — inte utifrån [points], som kan vara
        // tom i det udda fallet att bara en jämförelseserie har data (annars kraschade min/max).
        else -> {
            val described = visibleSeries.first().first
            val base = stringResource(
                R.string.format_fond_chart_description,
                stringResource(periodLabelRes(period)),
                described.size,
                MoneyFormat.kr(described.minOf { it.second }),
                MoneyFormat.kr(described.maxOf { it.second }),
            )
            // Snittlinjen nämns med sitt senaste värde (ANA-14) — linjen får inte bara finnas i bilden (UI-3).
            if (showMovingAverage) {
                stringResource(R.string.format_fond_chart_description_with_average, base, MoneyFormat.kr(windowedAverage.last().second))
            } else {
                base
            }
        }
    }

    val seriesColors = seriesColors()
    Column(modifier = modifier) {
        // Diagrammets egen väntesnurra (NAV-6), här i den delade komponenten i stället för en
        // per anropsplats (regel 4): ett diagram ligger inte alltid i ett kort med rubrikrad —
        // Fonddetaljs kurshistorik och bytesskärmens jämförelse ligger fritt i sina skärmar.
        // Ligger diagrammet däremot i ett kort som redan snurrar (Hems avkastningskurva) lämnas
        // [working] falskt, så samma besked inte ges två gånger i samma kort.
        if (working) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                WorkingIndicator(working = true, contentDescription = stringResource(R.string.chart_working))
            }
        }
        val purchaseMarker = rememberPurchaseMarker()
        val lineProvider = rememberSeriesLineProvider(
            seriesColors,
            areaUnderPrimary = visibleSeries.size == 1,
            movingAverageSecond = showMovingAverage,
        )
        // Nyckling på perioden: byter man period ska diagrammet zooma/skrolla om till den nya
        // datamängden direkt, inte behålla ett nyp/drag-läge som hörde till den förra periodens
        // (helt andra) datavolym.
        // Vicos standardtema har svarta axeletiketter oavsett tema; M3-temat tar etiketter,
        // axlar och rutnät ur MaterialTheme så de syns i mörkt läge.
        val chartModifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .semantics { contentDescription = chartDescription }
        if (model == null) {
            Box(modifier = chartModifier)
        } else key(period) { ProvideVicoTheme(rememberM3VicoTheme()) {
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(lineProvider = lineProvider, rangeProvider = PriceRangeProvider),
                    startAxis = when (valueAxis) {
                        ChartValueAxis.KRONOR -> VerticalAxis.rememberStart()
                        ChartValueAxis.PROCENT -> VerticalAxis.rememberStart(valueFormatter = PercentValueFormatter)
                    },
                    bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = DateValueFormatter),
                    persistentMarkers = { _ -> markerEpochDays.forEach { day -> purchaseMarker at day } },
                ),
                model = model,
                scrollState = rememberVicoScrollState(initialScroll = Scroll.Absolute.End),
                // Vicos standardzoom (`Zoom.max(Zoom.fixed(), Zoom.Content)`) tillåter aldrig
                // mer utzoomat än "naturligt" punktavstånd — för en period med fler punkter än
                // vad som får plats i den bredden (t.ex. "1 år"/"Allt") visades då bara svansen
                // närmast slutet i stället för hela den valda perioden. `Zoom.Content` ensamt
                // visar alltid ALLA punkter som skickats in, oavsett antal.
                zoomState = rememberVicoZoomState(initialZoom = Zoom.Content),
                modifier = chartModifier,
            )
        } }
        if (visibleSeries.size > 1) {
            ChartLegend(
                entries = visibleSeries.mapIndexed { index, (_, label) ->
                    LegendEntry(label = label, color = seriesColors[index % seriesColors.size])
                },
                indexed = normalized.indexed,
                partial = normalized.partial,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (showMovingAverage) {
            // Kurs och snitt med sina linjesymboler och senaste värden (ANA-14) — symbolen visar
            // vilken linje som är vilken, beloppen gör förklaringen läsbar utan att peka i diagrammet.
            ChartLegend(
                entries = listOf(
                    LegendEntry(
                        label = stringResource(R.string.fond_chart_legend_price),
                        color = seriesColors[0],
                        symbol = LegendSymbol.LINE,
                        value = MoneyFormat.kr(visibleSeries.first().first.last().second),
                    ),
                    LegendEntry(
                        label = stringResource(R.string.fond_chart_legend_moving_average),
                        color = ChartSeriesColors.movingAverage,
                        symbol = LegendSymbol.DOTTED_LINE,
                        value = MoneyFormat.kr(windowedAverage.last().second),
                    ),
                ),
                indexed = false,
                partial = false,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        // Skrollbar rad, inte en vanlig Row: sex perioder får inte plats på en telefonskärm,
        // och en fast rad hade tyst klippt bort "Allt" i stället för att göra den nåbar (UI-5).
        // Knapparna är samma sammanhängande knappgrupp som ChoiceChipRow (UI-12), men i en
        // LazyRow så instrumenttesterna kan nå sista perioden med performScrollToIndex.
        val periods = ChartPeriodFilter.Period.entries
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(ConnectedChoiceSpacing),
            modifier = Modifier.padding(top = 8.dp).testTag(CHART_PERIOD_ROW_TEST_TAG),
        ) {
            itemsIndexed(periods, key = { _, option -> option.name }) { index, option ->
                ConnectedChoiceButton(
                    label = stringResource(periodLabelRes(option)),
                    selected = option == period,
                    index = index,
                    count = periods.size,
                    onClick = { period = option },
                )
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
 *
 * @param areaUnderPrimary tonar ytan under en ensam kurva i `primaryContainer` (UI-12). I en
 *   jämförelse utelämnas ytan: den skulle skugga den andra kurvan och få serierna att se olika
 *   viktiga ut.
 * @param movingAverageSecond andra serien är 200-dagarssnittet (ANA-14), inte en jämförelse: den
 *   ritas då prickad i [ChartSeriesColors.movingAverage], utan yta — skild från kursen (heldragen
 *   med yta) och från jämförelsen (streckad koppar).
 */
@Composable
private fun rememberSeriesLineProvider(
    colors: List<Color>,
    areaUnderPrimary: Boolean,
    movingAverageSecond: Boolean = false,
): LineCartesianLayer.LineProvider {
    val areaColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = AREA_ALPHA)
    val primary = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(colors[0])),
        areaFill = if (areaUnderPrimary) LineCartesianLayer.AreaFill.single(Fill(areaColor)) else null,
    )
    // Referensen/jämförelsen är streckad och i koppar så den skiljs från innehavet både i färg och form.
    val comparison = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(colors[1])),
        stroke = LineCartesianLayer.LineStroke.Dashed(dashLength = COMPARISON_DASH, gapLength = COMPARISON_GAP),
    )
    val movingAverage = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(ChartSeriesColors.movingAverage)),
        stroke = LineCartesianLayer.LineStroke.Dashed(
            thickness = MOVING_AVERAGE_THICKNESS,
            cap = StrokeCap.Round,
            dashLength = 0.dp,
            gapLength = MOVING_AVERAGE_DOT_PITCH,
        ),
    )
    return LineCartesianLayer.LineProvider.series(primary, if (movingAverageSecond) movingAverage else comparison)
}

/** Symbolen före en rad i [ChartLegend]: färgprick (förval, jämförelsen) eller linjens eget streck. */
internal enum class LegendSymbol { DOT, LINE, DOTTED_LINE }

/** En rad i [ChartLegend] — [value] (t.ex. senaste kurs) högerställs med tabulära siffror; null = inget värde. */
internal data class LegendEntry(
    val label: String,
    val color: Color,
    val symbol: LegendSymbol = LegendSymbol.DOT,
    val value: String? = null,
)

/**
 * Teckenförklaring för en jämförelse (ANA-11) — färgprick **plus** fondnamn, aldrig färgen
 * ensam (UI-3). Säger också rakt ut att skalan är ett index och, när kandidatens historik är
 * kortare än perioden, att jämförelsen bara täcker den gemensamma delen (samma
 * markera-hellre-än-gissa-princip som HEM-2/ANA-4).
 *
 * Samma förklaring används för kurs och 200-dagarssnitt (ANA-14): då med linjesymboler
 * ([LegendSymbol]) och senaste värdet högerställt per rad. Förvalen (prick, inget värde)
 * bevarar jämförelsens utseende.
 */
@Composable
private fun ChartLegend(
    entries: List<LegendEntry>,
    indexed: Boolean,
    partial: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                LegendSymbolMark(entry.symbol, entry.color, modifier = Modifier.padding(end = 8.dp))
                if (entry.value == null) {
                    Text(entry.label, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(entry.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(entry.value, style = MonoAmountStyle.merge(MaterialTheme.typography.bodySmall))
                }
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

/** Ritar [LegendSymbol] — linjerna med samma tjocklek och prickavstånd som i diagrammet. */
@Composable
private fun LegendSymbolMark(symbol: LegendSymbol, color: Color, modifier: Modifier = Modifier) {
    when (symbol) {
        LegendSymbol.DOT -> Box(
            modifier = modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        LegendSymbol.LINE, LegendSymbol.DOTTED_LINE -> Canvas(modifier = modifier.size(width = LEGEND_LINE_WIDTH, height = 10.dp)) {
            val y = size.height / 2
            val thickness = if (symbol == LegendSymbol.LINE) LEGEND_PRICE_THICKNESS.toPx() else MOVING_AVERAGE_THICKNESS.toPx()
            val dots = if (symbol == LegendSymbol.DOTTED_LINE) {
                PathEffect.dashPathEffect(floatArrayOf(0f, MOVING_AVERAGE_DOT_PITCH.toPx()))
            } else {
                null
            }
            drawLine(
                color = color,
                start = Offset(thickness / 2, y),
                end = Offset(size.width - thickness / 2, y),
                strokeWidth = thickness,
                cap = StrokeCap.Round,
                pathEffect = dots,
            )
        }
    }
}

/**
 * Diagrammodellen för de serier som ritas, i ordning (kurs, sedan jämförelser eller snitt).
 * Alla serier ligger i samma lager, så y-intervallet ([PriceRangeProvider]) räknas över dem
 * tillsammans — ett snitt utanför kursens min/max får alltså plats (ANA-14).
 */
internal fun chartModel(lines: List<List<Pair<Long, Double>>>): CartesianChartModel =
    CartesianChartModel(
        LineCartesianLayerModel.build {
            lines.forEach { points -> series(x = points.map { it.first }, y = points.map { it.second }) }
        },
    )

/** Etikett för en diagramperiod — delad mellan chipparna och diagrammets semantik. */
private fun periodLabelRes(period: ChartPeriodFilter.Period): Int = when (period) {
    ChartPeriodFilter.Period.EN_MANAD -> R.string.fond_chart_period_1man
    ChartPeriodFilter.Period.TRE_MANADER -> R.string.fond_chart_period_3man
    ChartPeriodFilter.Period.ETT_AR -> R.string.fond_chart_period_1ar
    ChartPeriodFilter.Period.TRE_AR -> R.string.fond_chart_period_3ar
    ChartPeriodFilter.Period.FEM_AR -> R.string.fond_chart_period_5ar
    ChartPeriodFilter.Period.ALLT -> R.string.fond_chart_period_allt
}

/** Täckningen för den tonade ytan under en ensam kurva — tonad, inte fylld. */
private const val AREA_ALPHA = 0.5f

/**
 * Persistent markör (issue #55) för ett köptillfälle — en vertikal linje och en etikett med
 * köpdagens datum, i appens kopparaccent (`secondary`) så den syns tydligt mot kurslinjen
 * utan att kunna förväxlas med den.
 */
@Composable
private fun rememberPurchaseMarker(): CartesianMarker {
    val label = rememberTextComponent(
        style = TextStyle(color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 11.sp),
        padding = Insets(horizontal = 6.dp, vertical = 3.dp),
        background = rememberShapeComponent(
            fill = Fill(MaterialTheme.colorScheme.secondaryContainer),
            shape = RoundedCornerShape(4.dp),
        ),
    )
    val guideline = rememberLineComponent(
        fill = Fill(MaterialTheme.colorScheme.secondary),
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

/** Y-axelns etiketter i procentläge: 0.123 → "+12,3 %" (samma formatering som resten av appens avkastningstal, UI-3). */
private object PercentValueFormatter : CartesianValueFormatter {
    override fun format(
        context: CartesianMeasuringContext,
        value: Double,
        verticalAxisPosition: Axis.Position.Vertical?,
    ): CharSequence = MoneyFormat.percentSigned(value)
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

private val COMPARISON_DASH = 6.dp
private val COMPARISON_GAP = 4.dp

/** Snittlinjen (ANA-14): runda prickar ~2 dp med ~5 dp luft emellan (avståndet räknas mellan prickarnas mitt). */
private val MOVING_AVERAGE_THICKNESS = 2.dp
private val MOVING_AVERAGE_DOT_PITCH = 7.dp

/** Teckenförklaringens linjesymboler: längd och kurslinjens tjocklek (Vicos förvalda linje). */
private val LEGEND_LINE_WIDTH = 18.dp
private val LEGEND_PRICE_THICKNESS = 2.dp
