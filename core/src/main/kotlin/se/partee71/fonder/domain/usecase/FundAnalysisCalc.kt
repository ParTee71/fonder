package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.Transaction

/**
 * Nyckeltal och säljsignaler per innehav (issue #16) — hjälper användaren följa en fond över
 * längre tid och avgöra om det kan vara läge att se över den. Ren, testbar domänlogik utan
 * repository-beroende, samma "senaste kända NAV på/före periodens start"-princip som
 * [PortfolioPerformanceCalc] och samma kvarvarande anskaffningsvärde enligt genomsnittsmetoden ([Holding.netInvested])
 * som [PortfolioCalc] — ingen ny persisterad data eller egen anskaffningsmotor.
 *
 * Domänlagret känner inte till Android/strängresurser (regel: UI-text i `strings.xml`) — ett
 * icke-null [Analysis.status]/signal betyder "tillräcklig data", detaljer (t.ex.
 * [DistanceFromHighSignal.distanceFraction]) formateras till svensk text i UI-lagret, samma
 * princip som `RealizedSale.uncoveredShares`/POR-3/HEM-2.
 */
object FundAnalysisCalc {

    /** Fasta trösklar för v1 (ANA-2) — inte konfigurerbara, se issue #16 "Risks". */
    private const val HIGH_DISTANCE_YELLOW = -0.10
    private const val HIGH_DISTANCE_RED = -0.20
    private const val MOMENTUM_YELLOW_PP = -5.0

    /** ~52 veckor. */
    private const val HIGH_WINDOW_DAYS = 364L
    private const val SMA_WINDOW_DAYS = MovingAverageCalc.WINDOW_DAYS
    private const val CAGR_MIN_DAYS = 365L

    /** Kalenderdagar per år för årstakt (skill `fondmatematik`) – delas med facits årstakt ([SwitchOutcomeCalc]). */
    const val DAYS_PER_YEAR = 365.2425

    /** Riskmått (ANA-7, issue #24) — fasta konstanter, ej konfigurerbara i v1. */
    private const val TRADING_DAYS_PER_YEAR = 252.0
    private const val RISK_FREE_RATE = 0.0

    /**
     * Minsta täckning av fönstret (issue #195): andelen av de förväntade handelsdagarna
     * (≈ [TRADING_DAYS_PER_YEAR] × år) som måste finnas som giltiga dagsavkastningar (kurspar högst
     * [MAX_RETURN_GAP_DAYS] isär) för att fönstret ska väljas — 454 för 3 år, 152 för 1 år. Är
     * treårsfönstret mest glest (nedsamplad eller delvis backfillad historik, issue #35) prövas
     * ettårsfönstret, annars otillräcklig data, så att etiketten "(3 år)" aldrig påstår mer än
     * kurserna bär.
     */
    private const val MIN_WINDOW_COVERAGE = 0.6

    /**
     * Längsta lucka i kalenderdagar mellan två kurspunkter för att paret ska räknas som en
     * dagsavkastning, och längsta avstånd mellan fönstrets ankare och startdag (issue #195):
     * [NavCalendar.MAX_NAV_GAP_DAYS], helg plus helgdag. Längre luckor hoppas över i stället för
     * att räknas som en enda, för stor "dag".
     */
    private const val MAX_RETURN_GAP_DAYS = NavCalendar.MAX_NAV_GAP_DAYS

    /**
     * Största tillåtna glapp i kalenderdagar mellan två på varandra följande kurspunkter i
     * riskmåttens fönster (issue #195). Täckningskravet ([MIN_WINDOW_COVERAGE]) räknar bara
     * antalet avkastningar; utan glappgränsen kunde ett ankare, ett halvårs hål och sju täta
     * månader heta "1 år". En månad tål enstaka saknade veckor i kurscachen.
     */
    private const val MAX_WINDOW_GAP_DAYS = 31L

    /**
     * Riskmåttens fasta fönster i år bakåt från senaste kurs, längsta först (issue #195): 3 år
     * om historiken når så långt, annars 1 år. Oberoende av hur länge fonden ägts och av hur
     * lång historik anroparen skickar in, så att måtten går att jämföra mellan fonder.
     */
    private val RISK_WINDOW_YEARS = listOf(3L, 1L)

    /** Vinstsignalens tröskel (ANA-8, issue #26) — fast för v1, samma princip som övriga trösklar. */
    private const val PROFIT_TAKE_THRESHOLD = 0.50

    /**
     * Hur långt bakåt analysens kurshistorik når (issue #214): tre år ([Period.TRE_AR] och
     * treårsfönstret för volatilitet/Sharpe, #195) plus 45 dagars marginal. Treårsfönstret räknas
     * bakåt från **senaste** NAV, inte från idag, och kräver en kurs högst [MAX_RETURN_GAP_DAYS]
     * före sin startdag: marginalen täcker skottår, helger och röda dagar vid ankaret och en
     * senaste NAV som är upp till ~5 veckor gammal (källan släpar, appen har inte uppdaterats),
     * så att en nyss köpt fond inte tyst får "(1 år)". Täcker med god marginal 200-dagarssnittet
     * ([MovingAverageCalc.WINDOW_DAYS] = 290 dagar).
     */
    const val ANALYSIS_LOOKBACK_DAYS = 3L * 365L + 45L

    /**
     * Första dagen i analysens kurshistorik (issue #214) — den **enda** regeln för hur långt
     * bakåt Hem, Portfölj och Fonddetalj läser **och** hämtar (backfill i Fonddetalj, Portfölj
     * och bakgrundsjobbet), så att nyckeltal och signaler blir identiska på alla tre skärmarna.
     *
     * Den tidigaste av fondens första transaktion och `today − `[ANALYSIS_LOOKBACK_DAYS]: S1/S2,
     * snittlinjen och riskmåtten handlar om **fondens** kurs, inte om innehavet, och ska inte
     * bli "otillräcklig data" bara för att fonden köptes nyligen. Utan transaktion (bevakad
     * fond) gäller lookbacken ensam. Det som ska utgå från köpet — "sedan köp",
     * periodradernas kronor ([PositionValueChangeCalc]), GAV, CAGR och diagrammens "Allt" —
     * klipps mot köpet där det räknas, inte här.
     */
    fun analysisHistoryStart(firstTransaction: LocalDate?, today: LocalDate): LocalDate {
        val lookbackStart = today.minusDays(ANALYSIS_LOOKBACK_DAYS)
        return if (firstTransaction == null) lookbackStart else minOf(firstTransaction, lookbackStart)
    }

    enum class Period { YTD, TRE_MANADER, ETT_AR, TRE_AR, SEDAN_KOP }

    /**
     * [fraction] = fondens kursutveckling under perioden (NAV mot NAV). [amount] = värdeförändringen
     * i kr på de andelar som faktiskt ägts under perioden, dag för dag ([PositionValueChangeCalc],
     * issue #193); för [Period.SEDAN_KOP] positionens vinst mot anskaffningsvärdet (samma tal som
     * GAV-raden och portföljkortet, [Holding.gainLoss]). Null = otillräcklig kurshistorik för
     * perioden (ANA-1/ANA-4), aldrig gissat.
     */
    data class PeriodReturn(val period: Period, val amount: Double?, val fraction: Double?)

    enum class SignalLevel { GRON, GUL, ROD }

    /** [distanceFraction] är negativ eller 0 (t.ex. -0.17 = 17 % under toppen). */
    data class DistanceFromHighSignal(val level: SignalLevel, val distanceFraction: Double)
    data class TrendSignal(val level: SignalLevel)

    /** [differencePp] i procentenheter — negativ betyder sämre än portföljsnittet. */
    data class MomentumSignal(val level: SignalLevel, val differencePp: Double)

    /**
     * Vinstsignal (S4, ANA-8) — flaggar att innehavets orealiserade vinst mot GAV är stor nog
     * att en vinsthemtagning kan vara värd att överväga. Till skillnad från S1–S3 är det
     * ingen risksignal (fonden går inte dåligt) utan en möjlighet — deltar därför **inte** i
     * [combineStatus]/[Analysis.status] och ska visas med en egen markering, inte samma
     * grön/gul/röd-trafikljus som risksignalerna (se `ui/components/ProfitTakeBadge.kt`).
     */
    data class ProfitTakeSignal(val triggered: Boolean, val gainFraction: Double)

    data class KeyFigures(
        /** Fast ordning: YTD, TRE_MANADER, ETT_AR, TRE_AR, SEDAN_KOP. */
        val periodReturns: List<PeriodReturn>,
        /** Null om innehavet är yngre än ett år, eller "sedan köp"-perioden saknar data. */
        val cagr: Double?,
        val currentNav: Double,
        val gavPerShare: Double,
        /** Null bara om [gavPerShare] är 0 (bör inte kunna hända för ett verkligt innehav). */
        val gavFraction: Double?,
        /** Null om portföljens totala värde är 0/okänt. */
        val portfolioShareFraction: Double?,
        /** Annualiserad standardavvikelse på dagsavkastningar i [riskWindowYears] (ANA-7). Null = för kort historik (ANA-4). */
        val annualizedVolatility: Double?,
        /** Riskjusterad avkastning, [annualizedVolatility] i nämnaren (ANA-7). Null = för kort historik eller nollvolatilitet. */
        val sharpeRatio: Double?,
        /** Fönstret i år (3 eller 1) som volatilitet och Sharpe räknats på; null när [annualizedVolatility] saknas. */
        val riskWindowYears: Int?
    ) {
        /**
         * Sharpe-kvoten är odefinierad för att kursen inte rört sig alls i fönstret (volatilitet
         * 0), inte för att historiken saknas (ANA-7) — UI:t säger då det i stället för
         * "otillräcklig data".
         */
        val sharpeUndefinedWithoutMovement: Boolean
            get() = riskWindowYears != null && annualizedVolatility == 0.0
    }

    /**
     * [distanceFromHigh]/[trend]/[momentum] null = otillräcklig data för just den signalen
     * (ANA-4) — exkluderas ur [status]. [status] null bara om *ingen* signal kunde beräknas.
     */
    data class Analysis(
        val keyFigures: KeyFigures,
        val distanceFromHigh: DistanceFromHighSignal?,
        val trend: TrendSignal?,
        val momentum: MomentumSignal?,
        val status: SignalLevel?,
        /** Null = otillräcklig data (samma [KeyFigures.gavFraction]-gate, ANA-4). Deltar inte i [status] (ANA-8). */
        val profitTake: ProfitTakeSignal?
    )

    /**
     * Analyserar ett innehav. Null om fonden inte har några kvarvarande andelar eller helt
     * saknar kurshistorik — det finns då inget att analysera (samma princip som
     * [PortfolioCalc.computeHoldings], som redan utelämnar sådana fonder ur portföljen).
     *
     * @param transactions fondens egna transaktioner — avgör hur många andelar som ägts varje
     *   dag i periodraderna ([PositionValueChangeCalc]), så periodradernas kronor bara räknar
     *   tid andelarna faktiskt ägts även när historiken når före köpet; procenten och
     *   signalerna behöver dem inte.
     * @param priceHistory fondens analyshistorik från [analysisHistoryStart] (issue #214) —
     *   periodprocenten, signalerna (S1–S3) och riskmåtten räknas på hela listan; ju kortare
     *   historik, desto fler perioder/signaler blir otillräcklig data i stället för gissade.
     * @param portfolioTotalValue portföljens totala nuvarande värde, för portföljandelen.
     * @param otherHoldingsAverageThreeMonthReturn snittet av övriga innehavs tremånaders
     *   NAV-utveckling, för momentum-signalen (S3) — se [averageThreeMonthReturn].
     */
    fun analyze(
        today: LocalDate,
        holding: Holding,
        transactions: List<Transaction>,
        priceHistory: List<FundPrice>,
        firstPurchaseDate: LocalDate,
        portfolioTotalValue: Double,
        otherHoldingsAverageThreeMonthReturn: Double?
    ): Analysis? {
        if (holding.netShares <= 0.0) return null
        val latest = priceHistory.maxByOrNull { it.epochDay } ?: return null
        val currentNav = latest.nav

        val periodReturns = listOf(
            Period.YTD to LocalDate.of(today.year, 1, 1),
            Period.TRE_MANADER to today.minusMonths(3),
            Period.ETT_AR to today.minusYears(1),
            Period.TRE_AR to today.minusYears(3),
            Period.SEDAN_KOP to firstPurchaseDate
        ).map { (period, start) ->
            val fraction = priceReturn(start, priceHistory, currentNav)
            val amount = when {
                // Positionens faktiska vinst — samma tal som GAV-raden, så skärmen inte visar två "vinster".
                period == Period.SEDAN_KOP -> holding.gainLoss ?: (holding.netShares * currentNav - holding.netInvested)

                fraction == null -> null

                else -> PositionValueChangeCalc.valueChange(transactions, priceHistory, start.toEpochDay(), latest.epochDay)
            }
            PeriodReturn(period, amount, fraction)
        }

        val sincePurchaseFraction = periodReturns.first { it.period == Period.SEDAN_KOP }.fraction
        val holdingDays = ChronoUnit.DAYS.between(firstPurchaseDate, today)
        val cagr = computeCagr(holdingDays, sincePurchaseFraction)

        val gavPerShare = holding.netInvested / holding.netShares
        val gavFraction = if (gavPerShare > 0.0) (currentNav - gavPerShare) / gavPerShare else null
        val portfolioShareFraction = if (portfolioTotalValue > 0.0) {
            (holding.netShares * currentNav) / portfolioTotalValue
        } else {
            null
        }

        val distanceFromHigh = distanceFromHighSignal(today, priceHistory, currentNav)
        val trend = trendSignal(today, priceHistory, currentNav)
        val threeMonthReturn = periodReturns.first { it.period == Period.TRE_MANADER }.fraction
        val momentum = momentumSignal(threeMonthReturn, otherHoldingsAverageThreeMonthReturn)

        val riskWindow = riskWindow(priceHistory)
        val annualizedVolatility = riskWindow?.annualizedVolatility
        val sharpeRatio = riskWindow?.let { sharpeRatio(it) }

        return Analysis(
            keyFigures = KeyFigures(
                periodReturns = periodReturns,
                cagr = cagr,
                currentNav = currentNav,
                gavPerShare = gavPerShare,
                gavFraction = gavFraction,
                portfolioShareFraction = portfolioShareFraction,
                annualizedVolatility = annualizedVolatility,
                sharpeRatio = sharpeRatio,
                riskWindowYears = riskWindow?.years
            ),
            distanceFromHigh = distanceFromHigh,
            trend = trend,
            momentum = momentum,
            // Deltar medvetet inte i combineStatus nedan (ANA-8) — se ProfitTakeSignal-docen.
            status = combineStatus(listOfNotNull(distanceFromHigh?.level, trend?.level, momentum?.level)),
            profitTake = profitTakeSignal(gavFraction)
        )
    }

    /**
     * Tremånaders NAV-utveckling för en enskild fonds kurshistorik — used för att räkna ut
     * [averageThreeMonthReturn] (portföljsnittet för momentum-signalen, S3). Fristående från
     * [analyze] eftersom den ska köras för *övriga* innehav, inte det analyserade.
     */
    fun threeMonthReturn(today: LocalDate, priceHistory: List<FundPrice>): Double? {
        val currentNav = priceHistory.maxByOrNull { it.epochDay }?.nav ?: return null
        val historicalNav = priceHistory
            .filter { it.epochDay <= today.minusMonths(3).toEpochDay() }
            .maxByOrNull { it.epochDay }
            ?.nav
            ?: return null
        if (historicalNav <= 0.0) return null
        return (currentNav - historicalNav) / historicalNav
    }

    /**
     * Analyserar varje innehav i [holdings] (ANA-3/ANA-8) — den gemensamma slingan för Hem och
     * Portfölj. Momentum-signalen (S3) jämför varje fond mot snittet av **övriga** fonders
     * tremånadersutveckling, och portföljandelen räknas mot hela [holdings].
     *
     * "Sedan köp" och CAGR utgår från **positionens** startdatum, [Holding.firstPurchaseEpochDay]
     * (första köpet efter att innehavet senast var noll, POR-6) — samma datum som Fonddetalj
     * använder (issue #194; tidigare räknade Hem och Portfölj från fondens första transaktion
     * någonsin). Signalerna S1/S2 räknas på hela [historyByFundId], volatilitet och Sharpe i sitt
     * fasta fönster ur den (ANA-7), och S3 mot de övriga fondernas; är fönstren desamma som i
     * Fonddetalj ([analysisHistoryStart]) blir nyckeltal och signalnivå identiska på alla tre skärmar.
     *
     * @param transactionsByFundId transaktionerna per fond (periodradernas kronor, se [analyze]).
     * @param historyByFundId kurshistorik per fond från [analysisHistoryStart] — samma fönster
     *   som Fonddetalj.
     * @return analysen per fundId i [holdings] ordning; innehav utan [Holding.firstPurchaseEpochDay]
     *   och fonder som [analyze] inte kan analysera utelämnas.
     */
    fun analyzeAll(
        today: LocalDate,
        holdings: List<Holding>,
        transactionsByFundId: Map<String, List<Transaction>>,
        historyByFundId: Map<String, List<FundPrice>>
    ): Map<String, Analysis> {
        val portfolioTotalValue = PortfolioCalc.totalValue(holdings)
        return holdings.mapNotNull { holding ->
            val fundId = holding.fund.fundId
            val firstPurchase = holding.firstPurchaseEpochDay?.let(LocalDate::ofEpochDay) ?: return@mapNotNull null
            val analysis = analyze(
                today = today,
                holding = holding,
                transactions = transactionsByFundId[fundId].orEmpty(),
                priceHistory = historyByFundId[fundId].orEmpty(),
                firstPurchaseDate = firstPurchase,
                portfolioTotalValue = portfolioTotalValue,
                otherHoldingsAverageThreeMonthReturn = averageThreeMonthReturn(today, historyByFundId.filterKeys { it != fundId })
            ) ?: return@mapNotNull null
            fundId to analysis
        }.toMap()
    }

    /** Snittet av [threeMonthReturn] över flera fonders kurshistorik — null om ingen hade tillräcklig historik. */
    fun averageThreeMonthReturn(today: LocalDate, historiesByFundId: Map<String, List<FundPrice>>): Double? {
        val fractions = historiesByFundId.values.mapNotNull { threeMonthReturn(today, it) }
        return if (fractions.isEmpty()) null else fractions.average()
    }

    /** Fondens kursutveckling från senaste kända NAV på eller före [periodStart] till [currentNav]; null utan sådan kurs. */
    private fun priceReturn(periodStart: LocalDate, priceHistory: List<FundPrice>, currentNav: Double): Double? {
        val historicalNav = priceHistory
            .filter { it.epochDay <= periodStart.toEpochDay() }
            .maxByOrNull { it.epochDay }
            ?.nav
        if (historicalNav == null || historicalNav <= 0.0) return null
        return (currentNav - historicalNav) / historicalNav
    }

    private fun computeCagr(holdingDays: Long, sincePurchaseFraction: Double?): Double? {
        if (holdingDays < CAGR_MIN_DAYS || sincePurchaseFraction == null) return null
        val years = holdingDays / DAYS_PER_YEAR
        return (1.0 + sincePurchaseFraction).pow(1.0 / years) - 1.0
    }

    /**
     * Riskmåttens fönster: längd i år, kurspunkterna (tidsordnade, NAV > 0), deras giltiga
     * dagsavkastningar och volatiliteten på dem — finns fönstret finns alltid volatiliteten.
     */
    private class RiskWindow(val years: Int, val points: List<FundPrice>, val dailyReturns: List<Double>) {
        val annualizedVolatility: Double = annualizedVolatility(dailyReturns)
    }

    /** Minsta antal giltiga dagsavkastningar för ett fönster på [years] år ([MIN_WINDOW_COVERAGE]). */
    internal fun minWindowReturns(years: Long): Int = kotlin.math.ceil(MIN_WINDOW_COVERAGE * TRADING_DAYS_PER_YEAR * years).toInt()

    /**
     * Riskmåttens fönster (ANA-7, issue #195): [RISK_WINDOW_YEARS] bakåt från senaste kurs —
     * 3 år om fönstret är giltigt, annars 1 år på samma villkor, annars null (otillräcklig data,
     * ANA-4). Fönstret börjar i ankaret, den senaste kursen på eller före startdagen (samma
     * princip som [priceReturn]), så att första avkastningen täcker startdagen. Giltigt betyder:
     * - ankaret ligger högst [MAX_RETURN_GAP_DAYS] före startdagen,
     * - inget glapp mellan två på varandra följande punkter i fönstret är längre än
     *   [MAX_WINDOW_GAP_DAYS], och
     * - minst [minWindowReturns] giltiga dagsavkastningar finns.
     * Äldre historik än ankaret påverkar aldrig måtten, hur lång lista anroparen än skickar in.
     * Punkter med NAV ≤ 0 hoppas över så att kvoten aldrig blir ogiltig.
     */
    private fun riskWindow(priceHistory: List<FundPrice>): RiskWindow? {
        val points = priceHistory.filter { it.nav > 0.0 }.sortedBy { it.epochDay }
        val latest = points.lastOrNull() ?: return null
        val latestDate = LocalDate.ofEpochDay(latest.epochDay)
        for (years in RISK_WINDOW_YEARS) {
            val startEpochDay = latestDate.minusYears(years).toEpochDay()
            val anchor = points.indexOfLast { it.epochDay <= startEpochDay }
            if (anchor < 0 || startEpochDay - points[anchor].epochDay > MAX_RETURN_GAP_DAYS) continue
            val window = points.subList(anchor, points.size)
            if (window.zipWithNext().any { (prev, next) -> next.epochDay - prev.epochDay > MAX_WINDOW_GAP_DAYS }) continue
            val returns = dailyReturns(window)
            if (returns.size >= minWindowReturns(years)) return RiskWindow(years.toInt(), window, returns)
        }
        return null
    }

    /**
     * Enkla dagsavkastningar (`nav_t / nav_{t-1} − 1`) mellan på varandra följande kurspunkter i
     * [points] (tidsordnade, NAV > 0), ANA-7. Helger och helgdagar saknar kurs, så bara par högst
     * [MAX_RETURN_GAP_DAYS] kalenderdagar isär räknas som en handelsdag; längre luckor (nedsamplad
     * eller delvis backfillad historik) hoppas över i stället för att blåsa upp volatiliteten.
     */
    private fun dailyReturns(points: List<FundPrice>): List<Double> = points.zipWithNext()
        .filter { (prev, next) -> next.epochDay - prev.epochDay <= MAX_RETURN_GAP_DAYS }
        .map { (prev, next) -> (next.nav - prev.nav) / prev.nav }

    /**
     * Annualiserad standardavvikelse (ANA-7) — dagsavkastningarnas spridning × √252, på fönstrets
     * giltiga dagsavkastningar (täckningen är redan kontrollerad i [riskWindow]). Använder
     * urvalsstandardavvikelse (n−1 i nämnaren).
     */
    private fun annualizedVolatility(dailyReturns: List<Double>): Double {
        val mean = dailyReturns.average()
        val variance = dailyReturns.sumOf { (it - mean).pow(2) } / (dailyReturns.size - 1)
        return kotlin.math.sqrt(variance) * kotlin.math.sqrt(TRADING_DAYS_PER_YEAR)
    }

    /**
     * Sharpe-kvot (ANA-7) — (annualiserad avkastning − [RISK_FREE_RATE]) / [annualizedVolatility].
     *
     * Täljaren är fönstrets **totala** kursutveckling i årstakt, `(NAV_slut / NAV_start)^(365,2425 /
     * dagar) − 1` (samma årslängd som [cagr]), inte medlet av dagsavkastningarna × 252: de
     * luckfiltrerade dagsavkastningarna tappar rörelsen över luckor längre än
     * [MAX_RETURN_GAP_DAYS], vilket är rätt för spridningen men skulle ge en fond som föll över en
     * lucka en för hög avkastning (issue #195). Nämnaren är volatiliteten på de luckfiltrerade
     * dagsavkastningarna. Riskfri ränta är 0 % i v1, vilket hjälptexten säger (kvoten blir något
     * högre än fondbolagens). Null om volatiliteten är 0 (ingen risk att dividera med — ett
     * meningslöst/oändligt tal undviks, ANA-4-principen).
     */
    private fun sharpeRatio(window: RiskWindow): Double? {
        val annualizedVolatility = window.annualizedVolatility
        if (annualizedVolatility <= 0.0) return null
        val first = window.points.first()
        val last = window.points.last()
        val days = (last.epochDay - first.epochDay).toDouble()
        val annualizedReturn = (last.nav / first.nav).pow(DAYS_PER_YEAR / days) - 1.0
        return (annualizedReturn - RISK_FREE_RATE) / annualizedVolatility
    }

    /** S1 — avstånd från högsta NAV senaste 52 veckorna. Null om historiken inte når 52 veckor tillbaka. */
    private fun distanceFromHighSignal(today: LocalDate, priceHistory: List<FundPrice>, currentNav: Double): DistanceFromHighSignal? {
        val cutoffEpochDay = today.minusDays(HIGH_WINDOW_DAYS).toEpochDay()
        if (priceHistory.none { it.epochDay <= cutoffEpochDay }) return null
        val windowHigh = priceHistory
            .filter { it.epochDay in cutoffEpochDay..today.toEpochDay() }
            .maxOfOrNull { it.nav }
            ?: return null
        if (windowHigh <= 0.0) return null

        val distanceFraction = (currentNav - windowHigh) / windowHigh
        val level = when {
            distanceFraction <= HIGH_DISTANCE_RED -> SignalLevel.ROD
            distanceFraction <= HIGH_DISTANCE_YELLOW -> SignalLevel.GUL
            else -> SignalLevel.GRON
        }
        return DistanceFromHighSignal(level, distanceFraction)
    }

    /**
     * S2 — NAV under 200-dagars glidande medelvärde (200 handelsdagar = 290 kalenderdagar).
     * Null om historiken inte når 290 dagar tillbaka. Snittet räknas av [MovingAverageCalc] — samma implementering som snittlinjen i
     * kursdiagrammet (ANA-14), så signalen och linjen aldrig kan ge olika besked.
     */
    private fun trendSignal(today: LocalDate, priceHistory: List<FundPrice>, currentNav: Double): TrendSignal? {
        val sma = MovingAverageCalc.averageAt(
            points = priceHistory.map { it.epochDay to it.nav },
            epochDay = today.toEpochDay(),
            windowDays = SMA_WINDOW_DAYS
        ) ?: return null
        val level = if (currentNav < sma) SignalLevel.GUL else SignalLevel.GRON
        return TrendSignal(level)
    }

    /** S3 — 3-månadersutveckling minst 5 procentenheter sämre än portföljsnittet. Null om någondera saknas. */
    private fun momentumSignal(thisReturn: Double?, othersAverage: Double?): MomentumSignal? {
        if (thisReturn == null || othersAverage == null) return null
        val differencePp = (thisReturn - othersAverage) * 100.0
        val level = if (differencePp <= MOMENTUM_YELLOW_PP) SignalLevel.GUL else SignalLevel.GRON
        return MomentumSignal(level, differencePp)
    }

    /** S4 — vinstsignal (ANA-8). Null om [gavFraction] är null (otillräcklig data, samma gate som [KeyFigures.gavFraction]). */
    private fun profitTakeSignal(gavFraction: Double?): ProfitTakeSignal? {
        if (gavFraction == null) return null
        return ProfitTakeSignal(triggered = gavFraction >= PROFIT_TAKE_THRESHOLD, gainFraction = gavFraction)
    }

    /** Röd om någon röd signal eller minst två gula; gul om minst en gul; annars grön. Tom lista = null (ingen data alls). */
    private fun combineStatus(levels: List<SignalLevel>): SignalLevel? {
        if (levels.isEmpty()) return null
        val redCount = levels.count { it == SignalLevel.ROD }
        val yellowCount = levels.count { it == SignalLevel.GUL }
        return when {
            redCount > 0 || yellowCount >= 2 -> SignalLevel.ROD
            yellowCount >= 1 -> SignalLevel.GUL
            else -> SignalLevel.GRON
        }
    }
}
