package se.partee71.fonder.domain.usecase

/**
 * Glidande medelvärde av NAV — **en** implementering som både säljsignalen S2 (ANA-2,
 * [FundAnalysisCalc]) och snittlinjen i Fonddetaljs kursdiagram (ANA-14) använder, så att
 * diagrammet aldrig kan visa kursen över snittet samtidigt som S2 säger att den ligger under.
 *
 * Regeln för en dag `d` (fönster `w` = [WINDOW_DAYS] kalenderdagar):
 * - historiken måste nå minst `w` dagar bakåt, dvs. det finns en kurs på eller före `d − w`
 *   — annars är värdet `null` (otillräcklig data, ANA-4: aldrig gissat);
 * - snittet är medelvärdet av alla kurser med datum i `[d − w, d]`.
 *
 * Ren Kotlin utan Android-beroenden, testas i `MovingAverageCalcTest`.
 */
object MovingAverageCalc {

    /** 200-dagarsfönstret (S2 och diagrammets snittlinje). */
    const val WINDOW_DAYS = 200L

    /**
     * Snittet för dag [epochDay] ur [points] (`epochDay` → NAV, valfri ordning). Null om
     * historiken inte når [windowDays] dagar tillbaka eller fönstret saknar kurser.
     */
    fun averageAt(points: List<Pair<Long, Double>>, epochDay: Long, windowDays: Long = WINDOW_DAYS): Double? =
        evaluate(points.sortedBy { it.first }, listOf(epochDay), windowDays).single()

    /**
     * Snittserien för varje kurspunkt i [points] (`epochDay` → NAV), i stigande datumordning.
     * Dagar där historiken inte räcker utelämnas — serien börjar alltså först när den första
     * dagen har ett helt fönster bakom sig, och är tom om historiken aldrig räcker.
     */
    fun series(points: List<Pair<Long, Double>>, windowDays: Long = WINDOW_DAYS): List<Pair<Long, Double>> {
        val sorted = points.sortedBy { it.first }
        val days = sorted.map { it.first }
        return days.zip(evaluate(sorted, days, windowDays))
            .mapNotNull { (day, average) -> average?.let { day to it } }
    }

    /**
     * Kärnan: snittet för varje dag i [days] (stigande) ur den stigande [sorted]. Fönstrets
     * kurser summeras i datumordning för varje dag (ingen löpande summa som kan glida isär från
     * ett direkt medelvärde) — med ~140 kurser per fönster är det billigt även för lång historik.
     */
    private fun evaluate(sorted: List<Pair<Long, Double>>, days: List<Long>, windowDays: Long): List<Double?> {
        val firstDay = sorted.firstOrNull()?.first ?: return days.map { null }
        var start = 0
        var end = 0
        return days.map { day ->
            val from = day - windowDays
            while (end < sorted.size && sorted[end].first <= day) end++
            while (start < end && sorted[start].first < from) start++
            if (firstDay > from || start >= end) {
                null
            } else {
                var sum = 0.0
                for (i in start until end) sum += sorted[i].second
                sum / (end - start)
            }
        }
    }
}
