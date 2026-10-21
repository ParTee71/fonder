package se.partee71.fonder.domain.usecase

import kotlin.math.pow
import kotlin.math.sign
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.SuggestionRecord

/**
 * Facit för bytesplanen (SET-5, issue #80) — vad ett inspelat bytesförslag ([SuggestionRecord],
 * HEM-8) gav jämfört med att ha behållit innehavet.
 *
 * Måttet är **meravkastningen**: köpfondens kursutveckling sedan förslaget minus säljfondens. Det
 * är medvetet en jämförelse av två NAV-serier, inte av användarens faktiska resultat — den senare
 * beror på vilken dag bytet verkligen gjordes, på courtage och (i depå/AF, som HEM-8 ändå aldrig
 * ger förslag i) på skatt. Samma precisionsprincip som [PortfolioRiskCalc] använder när den säger
 * att ett värdeviktat snitt inte är portföljens risk.
 *
 * **Båda fonderna mäts alltid på samma dag** (issue #197), både vid förslaget
 * ([SuggestionRecord.navEpochDay]) och vid mätningen ([latestCommonNav]): två kurser från olika
 * dagar mäter två olika perioder, och i en volatil vecka får skillnaden då fel tecken.
 *
 * Summeringen är **träffandel, median och kronor** — aldrig ett snitt över förslag med olika
 * ålder (issue #197): ett par dagars utfall (±0,3 %) och ett års (±15 %) i samma snitt domineras
 * av de äldsta raderna och säger inget om hur ofta rådet var rätt. Procent jämförs bara inom ett
 * horisontband ([Horizon]), och över sex månader i årstakt.
 *
 * Ren och testbar, utan repository-beroenden: anroparen läser kurshistoriken ur cachen och skickar in den.
 */
object SwitchOutcomeCalc {

    /** Sista dagen (förslag → mätdag) i bandet "under 1 månad". */
    const val SHORT_HORIZON_MAX_DAYS = 29L

    /** Sista dagen i bandet "1–6 månader"; längre utfall räknas i årstakt. */
    const val MEDIUM_HORIZON_MAX_DAYS = 182L

    /**
     * Kurserna för både säljfonden och köpfonden på **en och samma** dag [epochDay] — den enda
     * form ett NAV-par får ha i facit, vid inspelningen såväl som vid mätningen.
     */
    data class SameDayNav(val epochDay: Long, val sellNav: Double, val buyNav: Double)

    /** Hur gammalt utfallet är, räknat från förslagets NAV-dag till mätdagen. */
    enum class Horizon {
        UNDER_ONE_MONTH,
        ONE_TO_SIX_MONTHS,

        /** Medianen i det här bandet är den relativa utvecklingen i **årstakt** ([annualize]). */
        OVER_SIX_MONTHS;

        companion object {
            fun of(days: Long): Horizon = when {
                days <= SHORT_HORIZON_MAX_DAYS -> UNDER_ONE_MONTH
                days <= MEDIUM_HORIZON_MAX_DAYS -> ONE_TO_SIX_MONTHS
                else -> OVER_SIX_MONTHS
            }
        }
    }

    /**
     * Utfallet för ett enskilt förslag. Finns ingen dag efter förslaget där båda fonderna har en
     * kurs är [excessReturn] null och raden är **ej utvärderad** — aldrig 0, som skulle läsas som
     * "bytet var en nollsumma" (samma princip som ANA-4:s otillräckliga data).
     *
     * @param excessKr [excessReturn] omräknat till kronor på det belopp förslaget avsåg. Null
     *   även för en utvärderad rad om [SuggestionRecord.switchValueKr] saknas — rader inspelade
     *   före issue #75 vet inte vilket belopp bytet gällde, och ett påhittat belopp vore värre
     *   än inget.
     * @param measuredAtEpochDay mätdagen: den senaste dag båda fonderna har en kurs.
     */
    data class Outcome(
        val record: SuggestionRecord,
        val sellReturn: Double? = null,
        val buyReturn: Double? = null,
        val excessReturn: Double? = null,
        val excessKr: Double? = null,
        val measuredAtEpochDay: Long? = null
    ) {
        val isEvaluated: Boolean get() = excessReturn != null

        /**
         * Har förslaget ett utgångsläge att mäta från — båda NAV vid förslaget > 0? Är det falskt
         * blir raden **aldrig** utvärderad, hur många kurser som än hämtas (jfr [evaluate]); då är
         * "väntar på kurs" fel besked.
         */
        val hasBaseNav: Boolean get() = record.sellNavAtSuggestion > 0.0 && record.buyNavAtSuggestion > 0.0

        /** Dagar från förslagets NAV-dag till mätdagen; null för en ej utvärderad rad. */
        val days: Long? get() = measuredAtEpochDay?.takeIf { isEvaluated }?.let { it - record.navBaseEpochDay }

        val horizon: Horizon? get() = days?.let(Horizon::of)

        /**
         * Köpfondens utveckling **relativt** säljfonden: `(1 + köp) / (1 + sälj) − 1`. Har samma tecken
         * som [excessReturn] men är den storhet som går att kompoundera — en aritmetisk skillnad
         * mellan två avkastningar är ingen avkastning och kan inte räknas om till årstakt.
         */
        val relativeReturn: Double? get() = if (sellReturn == null || buyReturn == null) null else (1.0 + buyReturn) / (1.0 + sellReturn) - 1.0

        /** Den relativa utvecklingen ([relativeReturn]) i årstakt — se [annualize]. */
        val annualizedRelativeReturn: Double? get() = relativeReturn?.let { r -> days?.let { annualize(r, it) } }

        /** Bättre än att ha behållit: meravkastningen är positiv. Exakt noll är inte bättre. */
        val isBetter: Boolean get() = (excessReturn ?: 0.0) > 0.0
    }

    /**
     * Summering över en uppsättning utfall. Träffandelen ([betterCount] av [evaluatedCount]),
     * medianen och kronorna är **tre olika svar**: hur ofta rådet var rätt, hur stort ett typiskt
     * utfall var, och vad det var värt. Kronorna summeras över dem som dessutom har ett känt belopp,
     * så urvalen skiljer sig när historiken innehåller rader från före issue #75.
     *
     * [medianExcessReturn] blandar horisonter och visas därför bara där det är jämförbart (per plats
     * i planen, där alla rader är lika gamla i snitt); summeringskortet visar träffandel och kronor.
     */
    data class Summary(
        val betterCount: Int = 0,
        val evaluatedCount: Int = 0,
        val totalCount: Int = 0,
        val medianExcessReturn: Double? = null,
        val totalExcessKr: Double? = null
    ) {
        /**
         * Träffandelens riktning: 1 när fler än hälften av de utvärderade var bättre, −1 när färre än
         * hälften var det (ett utfall på exakt noll räknas som inte bättre, [Outcome.isBetter]), 0 vid
         * exakt hälften och när inget är utvärderat — då färgas den neutralt, varken vinst eller förlust.
         */
        val hitRateSign: Int get() = (betterCount * 2 - evaluatedCount).sign
    }

    /** Utfallet för en given plats i planen — se [byPlanIndex]. */
    data class PlanIndexSummary(val planIndex: Int, val summary: Summary)

    /**
     * Utfallen inom ett horisontband. [medianExcessReturn] är medianen av meravkastningen, för
     * [Horizon.OVER_SIX_MONTHS] av den relativa utvecklingen **i årstakt**
     * ([Outcome.annualizedRelativeReturn], [isAnnualized]).
     */
    data class HorizonSummary(
        val horizon: Horizon,
        val count: Int,
        val betterCount: Int,
        val medianExcessReturn: Double
    ) {
        val isAnnualized: Boolean get() = horizon == Horizon.OVER_SIX_MONTHS
    }

    /**
     * Den senaste dagen ≥ [fromEpochDay] där **både** [sell] och [buy] har en kurs, med de två
     * kurserna — null om ingen sådan dag finns. Kurser på noll eller mindre räknas inte: de kan
     * inte ge någon avkastning.
     */
    fun latestCommonNav(sell: List<FundPrice>, buy: List<FundPrice>, fromEpochDay: Long = Long.MIN_VALUE): SameDayNav? {
        val buyByDay = buy.filter { it.nav > 0.0 && it.epochDay >= fromEpochDay }.associate { it.epochDay to it.nav }
        return sell.filter { it.nav > 0.0 && it.epochDay >= fromEpochDay && it.epochDay in buyByDay }
            .maxByOrNull { it.epochDay }
            ?.let { SameDayNav(epochDay = it.epochDay, sellNav = it.nav, buyNav = buyByDay.getValue(it.epochDay)) }
    }

    /**
     * Utvärderar ett förslag mot kurshistoriken för de två fonderna: mätdagen är den senaste dag
     * båda har en kurs, tidigast förslagsdagen och senare än förslagets NAV-dag. En mätning på
     * själva NAV-dagen hade varit noll dagar — inget utfall, men räknat som "inte bättre".
     */
    fun evaluate(record: SuggestionRecord, sellHistory: List<FundPrice>, buyHistory: List<FundPrice>): Outcome {
        val from = maxOf(record.suggestedAtEpochDay, record.navBaseEpochDay + 1)
        return evaluate(record, latestCommonNav(sellHistory, buyHistory, from))
    }

    /**
     * Utvärderar ett förslag mot ett NAV-par från samma dag; null = ej utvärderad.
     *
     * Ett NAV-utgångsläge på noll eller mindre kan inte ge någon avkastning och behandlas som
     * saknad data i stället för att divideras med — det vore en oändlighet, inte ett utfall.
     */
    fun evaluate(record: SuggestionRecord, nav: SameDayNav?): Outcome {
        if (nav == null || record.sellNavAtSuggestion <= 0.0 || record.buyNavAtSuggestion <= 0.0) return Outcome(record = record)
        val sellReturn = nav.sellNav / record.sellNavAtSuggestion - 1.0
        val buyReturn = nav.buyNav / record.buyNavAtSuggestion - 1.0
        val excess = buyReturn - sellReturn
        return Outcome(
            record = record,
            sellReturn = sellReturn,
            buyReturn = buyReturn,
            excessReturn = excess,
            excessKr = record.switchValueKr?.let { it * excess },
            measuredAtEpochDay = nav.epochDay
        )
    }

    /**
     * Den relativa utvecklingen [relative] ([Outcome.relativeReturn]) över [days] dagar i årstakt:
     * `(1 + r)^(365,2425 / dagar) − 1` (skill `fondmatematik`). Bara en avkastning går att
     * kompoundera, inte en aritmetisk meravkastning. Används bara över sex månader (issue #197,
     * användarens beslut — ett kortare utfall uppräknat till ett år blir mest brus). −100 % eller
     * sämre har ingen årstakt och ges −100 %.
     */
    fun annualize(relative: Double, days: Long): Double? {
        if (days <= 0) return null
        if (1.0 + relative <= 0.0) return -1.0
        return (1.0 + relative).pow(FundAnalysisCalc.DAYS_PER_YEAR / days) - 1.0
    }

    /** Medianen; vid jämnt antal medel av de två mittersta. Null för en tom lista. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /**
     * Summerar [outcomes]. Ej utvärderade rader räknas i [Summary.totalCount] men aldrig i
     * träffandelen eller medianen — de har inget utfall, och att räkna dem som noll hade tystat
     * skillnaden mellan "bytet gav ingenting" och "vi vet inte än".
     */
    fun summarize(outcomes: List<Outcome>): Summary {
        val evaluated = outcomes.filter { it.isEvaluated }
        val withKr = evaluated.mapNotNull { it.excessKr }
        return Summary(
            betterCount = evaluated.count { it.isBetter },
            evaluatedCount = evaluated.size,
            totalCount = outcomes.size,
            medianExcessReturn = median(evaluated.mapNotNull { it.excessReturn }),
            totalExcessKr = if (withKr.isEmpty()) null else withKr.sum()
        )
    }

    /**
     * Utvärderade utfall per horisontband, kortast först; band utan rader utelämnas. Över sex
     * månader är medianen den relativa utvecklingen i årstakt, så ett halvårs och ett treårs utfall
     * går att jämföra.
     */
    fun byHorizon(outcomes: List<Outcome>): List<HorizonSummary> = outcomes.filter { it.isEvaluated }
        .groupBy { it.horizon!! }
        .toSortedMap()
        .map { (horizon, group) ->
            val values = if (horizon == Horizon.OVER_SIX_MONTHS) group.map { it.annualizedRelativeReturn!! } else group.map { it.excessReturn!! }
            HorizonSummary(horizon = horizon, count = group.size, betterCount = group.count { it.isBetter }, medianExcessReturn = median(values)!!)
        }

    /**
     * Summerar per plats i planen, stigande. [SuggestionRecord.planIndex] sparades uttryckligen
     * för att göra mätbart om lägre rankade byten presterar sämre än det högst rankade — den
     * frågan avgör om `SwitchPlanCalc.MAX_SWITCHES_PER_PLAN` (3) kan höjas, och den går inte
     * att svara på förrän utfallen redovisas per plats.
     */
    fun byPlanIndex(outcomes: List<Outcome>): List<PlanIndexSummary> = outcomes.groupBy { it.record.planIndex }
        .toSortedMap()
        .map { (index, group) -> PlanIndexSummary(planIndex = index, summary = summarize(group)) }
}
