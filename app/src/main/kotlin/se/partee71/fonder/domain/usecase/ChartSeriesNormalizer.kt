package se.partee71.fonder.domain.usecase

/**
 * Indexerar flera kursserier till samma startvärde ([BASE] = 100) så att de går att jämföra i
 * ett och samma diagram (ANA-11, issue #85) — underlaget till jämförelsediagrammet mellan ett
 * innehav och en föreslagen fond i Fonddetalj.
 *
 * Rå NAV går inte att jämföra: en fond som står i 12 kr och en som står i 1 400 kr säger
 * ingenting om varandra på en gemensam y-axel — den ena kurvan blir en platt linje längst ned.
 * Indexeringen svarar i stället på den fråga förslaget faktiskt väcker: *hur mycket* har var och
 * en rört sig under den visade perioden?
 *
 * Basdagen är den **första dag alla serier har data** (senaste av seriernas startdagar), inte
 * den tidigaste dagen någon av dem har: en kandidat som startade mitt i perioden får annars sin
 * uppgång mätt från en annan dag än innehavet, vilket gör jämförelsen missvisande. Har någon
 * serie beskurits av det markeras resultatet som [Result.partial] så vyn kan säga det rakt ut i
 * stället för att låta en kortare historik se ut som en fullständig jämförelse (samma princip
 * som HEM-2/ANA-4: hellre markerat än gissat).
 *
 * Rent domänlager utan Compose-beroende, samma mönster som [ChartPeriodFilter] och
 * [PurchaseMarkerFilter] — [se.partee71.fonder.ui.diagram.FundLineChart] anropar det efter att
 * perioden filtrerats, så indexeringen alltid sker mot den period användaren tittar på.
 */
object ChartSeriesNormalizer {

    /** Startvärdet varje serie indexeras till på basdagen. */
    const val BASE = 100.0

    /**
     * @param series serierna i samma ordning som de skickades in, sorterade på datum. Indexerade
     *   bara om [indexed] är sant — annars orörda (utöver sorteringen).
     * @param indexed sant om serierna faktiskt indexerats, dvs. y-värdena är index och inte kronor.
     * @param partial sant om minst en serie börjar senare än en annan, så jämförelsen bara täcker
     *   den gemensamma delen av perioden.
     * @param baseEpochDay dagen serierna indexerats från, eller null om ingen indexering skedde.
     */
    data class Result(
        val series: List<List<Pair<Long, Double>>>,
        val indexed: Boolean,
        val partial: Boolean,
        val baseEpochDay: Long?,
    )

    /**
     * Indexerar [series] till [BASE] på deras första gemensamma dag.
     *
     * Färre än två icke-tomma serier lämnas oindexerade — en ensam kurva ska visa fondens
     * verkliga kurs i kronor, inte ett index (det är fortfarande [se.partee71.fonder.ui.diagram.FundLineChart]s
     * normalfall). Detsamma gäller om någon serie saknar punkter från och med den gemensamma
     * basdagen, eller har ett basvärde som inte går att dividera med: då finns ingen ärlig
     * gemensam utgångspunkt, och resultatet markeras som [Result.partial] i stället för att
     * indexeras mot en påhittad bas.
     */
    fun index(series: List<List<Pair<Long, Double>>>): Result {
        val sorted = series.map { points -> points.sortedBy { it.first } }
        val nonEmpty = sorted.filter { it.isNotEmpty() }
        if (nonEmpty.size < 2) return Result(series = sorted, indexed = false, partial = false, baseEpochDay = null)

        val baseEpochDay = nonEmpty.maxOf { it.first().first }
        val trimmed = sorted.map { points -> points.filter { it.first >= baseEpochDay } }
        // En serie som tar slut före den gemensamma basdagen (t.ex. en fond vars historik
        // upphört) kan inte indexeras mot de övriga — då är det ingen jämförelse längre.
        if (trimmed.any { it.isEmpty() } || trimmed.any { it.first().second <= 0.0 }) {
            return Result(series = sorted, indexed = false, partial = true, baseEpochDay = null)
        }

        val indexedSeries = trimmed.map { points ->
            val base = points.first().second
            points.map { (epochDay, value) -> epochDay to value / base * BASE }
        }
        val partial = nonEmpty.any { it.first().first < baseEpochDay }
        return Result(series = indexedSeries, indexed = true, partial = partial, baseEpochDay = baseEpochDay)
    }
}
