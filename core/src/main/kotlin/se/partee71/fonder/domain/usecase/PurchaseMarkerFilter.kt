package se.partee71.fonder.domain.usecase

import kotlin.math.abs

/**
 * Vilka kurspunkter i [se.partee71.fonder.ui.diagram.FundLineChart] som ska få en köpmarkör
 * (issue #55) — köpdagar som ingår i den period diagrammet visar, kan vara flera tillfällen.
 *
 * Vicos persistenta markörer (`CartesianChart.PersistentMarkerScope.at`) kräver ett x-värde
 * som **exakt** matchar en punkt i den ritade serien, utan interpolering — annars visas ingen
 * markör alls. En köpdag råkar inte alltid sammanfalla med en cachad kursdag (helg, röd dag,
 * en lucka i historiken), så varje köpdag snappas till den kurspunkt som ligger närmast i
 * stället för att tystas ner helt.
 */
object PurchaseMarkerFilter {

    /**
     * [points] (epochDay, NAV) — samma fönster som faktiskt skickas till diagrammet (redan
     * avgränsat av [ChartPeriodFilter]). [purchaseEpochDays] filtreras till punkternas
     * datumintervall och snappas var och en till närmaste punkts epochDay.
     */
    fun apply(points: List<Pair<Long, Double>>, purchaseEpochDays: List<Long>): List<Long> {
        if (points.isEmpty() || purchaseEpochDays.isEmpty()) return emptyList()
        val pointDays = points.map { it.first }
        val range = pointDays.min()..pointDays.max()
        return purchaseEpochDays
            .filter { it in range }
            .map { purchaseDay -> pointDays.minBy { abs(it - purchaseDay) } }
            .distinct()
            .sorted()
    }
}
