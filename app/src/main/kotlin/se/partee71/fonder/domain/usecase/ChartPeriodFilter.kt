package se.partee71.fonder.domain.usecase

/**
 * Filtrerar en fonds kurshistorik till ett valt fönster för [se.partee71.fonder.ui.diagram.FundLineChart]
 * (issue #51). Ersätter den tidigare fasta standardzoomen (#47) — i stället för att zooma in
 * Vico-diagrammet på hela historiken (vilket gjorde att y-axeln alltid räknades ut från *hela*
 * historikens min/max, se #49) skickas bara den valda periodens punkter in i diagrammet. Både
 * x- och y-axeln följer då automatiskt den period användaren faktiskt tittar på.
 */
object ChartPeriodFilter {

    enum class Period(val days: Long?) {
        EN_MANAD(30L),
        TRE_MANADER(90L),
        ETT_AR(365L),
        ALLT(null),
    }

    /**
     * [points] (epochDay, NAV) i valfri ordning, avgränsade till [period] räknat bakåt från den
     * SENASTE punkten i datan — inte dagens datum. En fond utan färsk data (worker inte kört
     * än, källan nere) ska visa sitt senaste kända fönster, inte ett kortare eller tomt sådant
     * bara för att den inte hunnit uppdateras.
     *
     * Kortare historik än perioden ger hela historiken i stället för ett delvis tomt fönster —
     * samma princip som `Zoom.max(Zoom.Content, …)` i den ersatta zoomlösningen (#47).
     */
    fun apply(points: List<Pair<Long, Double>>, period: Period): List<Pair<Long, Double>> {
        val days = period.days ?: return points
        val latest = points.maxOfOrNull { it.first } ?: return points
        val cutoff = latest - days
        return points.filter { it.first >= cutoff }
    }
}
