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
     * Nollställer **avkastningsserier** (värden i andel, 0.12 = +12 %) mot periodens första
     * gemensamma dag, så alla kurvor startar på 0 % där (HEM-9/HEM-10).
     *
     * Motsvarigheten till [index] för det andra måttet appen ritar. Utan den jämför diagrammet
     * två *ackumulerade* avkastningar sedan respektive start: en portfölj på +45 % och en
     * indexfond på +75 % ritas som två parallella band på olika höjd, och frågan man faktiskt
     * ställer till en enmånadsvy — vilken av dem växte mest den här månaden? — går inte att
     * läsa ur bilden. Efter nollställningen är avståndet mellan kurvorna periodens skillnad,
     * inte historiens.
     *
     * Räknas som kvot mellan indexvärden, inte som skillnad i procentenheter:
     * `(1 + r) / (1 + r₀) − 1`. En portfölj som gick från +100 % till +110 % har stigit 5 % under
     * perioden, inte 10 procentenheter — subtraktion hade svarat fel på just den fråga
     * nollställningen ställer.
     *
     * Med "Allt" vald är basdagen kedjans start, där avkastningen per definition är 0 %, så kurvan
     * är oförändrad. Serierna är tidsviktade index (issue #116), och kvoten mellan två punkter i en
     * sådan kedja *är* produkten av de mellanliggande dagsfaktorerna — det är därför varje period
     * svarar på sin egen fråga i stället för att bero på var basdagen råkar hamna (HEM-9).
     *
     * Basdagen väljs som i [index]: första dag **alla** serier har data, och en serie som
     * börjar senare gör resultatet [Result.partial]. Går basen inte att dividera med
     * (`1 + r₀ ≤ 0` — en position som förlorat allt) lämnas serierna orörda och markeras
     * partial, i stället för att nollställas mot en omöjlig bas.
     */
    fun rebaseReturns(series: List<List<Pair<Long, Double>>>): Result {
        val sorted = series.map { points -> points.sortedBy { it.first } }
        val nonEmpty = sorted.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return Result(series = sorted, indexed = false, partial = false, baseEpochDay = null)

        val baseEpochDay = nonEmpty.maxOf { it.first().first }
        val trimmed = sorted.map { points -> points.filter { it.first >= baseEpochDay } }
        // En serie som tar slut före den gemensamma basdagen (t.ex. en referensfond vars
        // historik upphört) kan inte nollställas mot de övriga — då är det ingen jämförelse.
        val lostSeries = sorted.zip(trimmed).any { (original, cut) -> original.isNotEmpty() && cut.isEmpty() }
        val unusableBase = trimmed.any { it.isNotEmpty() && 1.0 + it.first().second <= 0.0 }
        if (lostSeries || unusableBase) {
            return Result(series = sorted, indexed = false, partial = true, baseEpochDay = null)
        }

        val rebased = trimmed.map { points ->
            if (points.isEmpty()) return@map points
            val base = 1.0 + points.first().second
            points.map { (epochDay, value) -> epochDay to (1.0 + value) / base - 1.0 }
        }
        val partial = nonEmpty.any { it.first().first < baseEpochDay }
        return Result(series = rebased, indexed = false, partial = partial, baseEpochDay = baseEpochDay)
    }

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
