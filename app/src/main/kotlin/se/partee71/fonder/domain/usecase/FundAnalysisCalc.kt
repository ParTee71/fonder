package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow

/**
 * Nyckeltal och säljsignaler per innehav (issue #16) — hjälper användaren följa en fond över
 * längre tid och avgöra om det kan vara läge att se över den. Ren, testbar domänlogik utan
 * repository-beroende, samma "senaste kända NAV på/före periodens start"-princip som
 * [PortfolioPerformanceCalc] och samma FIFO-kvarvarande anskaffningsvärde ([Holding.netInvested])
 * som [PortfolioCalc] — ingen ny persisterad data eller egen FIFO-motor.
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
    private const val SMA_WINDOW_DAYS = 200L
    private const val CAGR_MIN_DAYS = 365L
    private const val DAYS_PER_YEAR = 365.2425

    enum class Period { YTD, TRE_MANADER, ETT_AR, TRE_AR, SEDAN_KOP }

    /** [amount]/[fraction] null = otillräcklig kurshistorik för perioden (ANA-1/ANA-4), aldrig gissat. */
    data class PeriodReturn(val period: Period, val amount: Double?, val fraction: Double?)

    enum class SignalLevel { GRON, GUL, ROD }

    /** [distanceFraction] är negativ eller 0 (t.ex. -0.17 = 17 % under toppen). */
    data class DistanceFromHighSignal(val level: SignalLevel, val distanceFraction: Double)
    data class TrendSignal(val level: SignalLevel)

    /** [differencePp] i procentenheter — negativ betyder sämre än portföljsnittet. */
    data class MomentumSignal(val level: SignalLevel, val differencePp: Double)

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
    )

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
    )

    /**
     * Analyserar ett innehav. Null om fonden inte har några kvarvarande andelar eller helt
     * saknar kurshistorik — det finns då inget att analysera (samma princip som
     * [PortfolioCalc.computeHoldings], som redan utelämnar sådana fonder ur portföljen).
     *
     * @param priceHistory fondens kurshistorik, gärna sedan första köpet (redan reaktivt
     *   laddad i Fonddetalj, se [se.partee71.fonder.ui.fond.FondDetaljViewModel]) — ju kortare
     *   historik, desto fler perioder/signaler blir otillräcklig data i stället för gissade.
     * @param portfolioTotalValue portföljens totala nuvarande värde, för portföljandelen.
     * @param otherHoldingsAverageThreeMonthReturn snittet av övriga innehavs tremånaders
     *   NAV-utveckling, för momentum-signalen (S3) — se [averageThreeMonthReturn].
     */
    fun analyze(
        today: LocalDate,
        holding: Holding,
        priceHistory: List<FundPrice>,
        firstPurchaseDate: LocalDate,
        portfolioTotalValue: Double,
        otherHoldingsAverageThreeMonthReturn: Double?,
    ): Analysis? {
        if (holding.netShares <= 0.0) return null
        val currentNav = priceHistory.maxByOrNull { it.epochDay }?.nav ?: return null

        val periodReturns = listOf(
            Period.YTD to LocalDate.of(today.year, 1, 1),
            Period.TRE_MANADER to today.minusMonths(3),
            Period.ETT_AR to today.minusYears(1),
            Period.TRE_AR to today.minusYears(3),
            Period.SEDAN_KOP to firstPurchaseDate,
        ).map { (period, start) -> periodReturn(period, start, priceHistory, currentNav, holding.netShares) }

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

        return Analysis(
            keyFigures = KeyFigures(
                periodReturns = periodReturns,
                cagr = cagr,
                currentNav = currentNav,
                gavPerShare = gavPerShare,
                gavFraction = gavFraction,
                portfolioShareFraction = portfolioShareFraction,
            ),
            distanceFromHigh = distanceFromHigh,
            trend = trend,
            momentum = momentum,
            status = combineStatus(listOfNotNull(distanceFromHigh?.level, trend?.level, momentum?.level)),
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

    /** Snittet av [threeMonthReturn] över flera fonders kurshistorik — null om ingen hade tillräcklig historik. */
    fun averageThreeMonthReturn(today: LocalDate, historiesByFundId: Map<String, List<FundPrice>>): Double? {
        val fractions = historiesByFundId.values.mapNotNull { threeMonthReturn(today, it) }
        return if (fractions.isEmpty()) null else fractions.average()
    }

    private fun periodReturn(
        period: Period,
        periodStart: LocalDate,
        priceHistory: List<FundPrice>,
        currentNav: Double,
        shares: Double,
    ): PeriodReturn {
        val historicalNav = priceHistory
            .filter { it.epochDay <= periodStart.toEpochDay() }
            .maxByOrNull { it.epochDay }
            ?.nav
        if (historicalNav == null || historicalNav <= 0.0) return PeriodReturn(period, amount = null, fraction = null)
        val fraction = (currentNav - historicalNav) / historicalNav
        val amount = (currentNav - historicalNav) * shares
        return PeriodReturn(period, amount, fraction)
    }

    private fun computeCagr(holdingDays: Long, sincePurchaseFraction: Double?): Double? {
        if (holdingDays < CAGR_MIN_DAYS || sincePurchaseFraction == null) return null
        val years = holdingDays / DAYS_PER_YEAR
        return (1.0 + sincePurchaseFraction).pow(1.0 / years) - 1.0
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

    /** S2 — NAV under 200-dagars glidande medelvärde. Null om historiken inte når 200 dagar tillbaka. */
    private fun trendSignal(today: LocalDate, priceHistory: List<FundPrice>, currentNav: Double): TrendSignal? {
        val cutoffEpochDay = today.minusDays(SMA_WINDOW_DAYS).toEpochDay()
        if (priceHistory.none { it.epochDay <= cutoffEpochDay }) return null
        val windowPrices = priceHistory.filter { it.epochDay in cutoffEpochDay..today.toEpochDay() }
        if (windowPrices.isEmpty()) return null

        val sma = windowPrices.map { it.nav }.average()
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
