package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import java.time.LocalDate

/**
 * Beräknar hur portföljens värde förändrats över korta perioder (dag/vecka/månad), utöver
 * det statiska nuläget i [PortfolioCalc] (issue #14). Måttet jämför nuvarande värde mot vad
 * *dagens* antal andelar var värda vid periodens start (senaste kända NAV på eller före det
 * datumet) — ett enkelt marknadsvärde-mått, inte en kassaflödesjusterad avkastning (TWR/MWR).
 * Köp/sälj som skett inom perioden påverkar alltså inte beräkningen, bara prisrörelsen gör.
 */
object PortfolioPerformanceCalc {

    enum class Period(val days: Long) { DAG(1), VECKA(7), MANAD(30) }

    /** Hur långt tillbaka kurshistorik bör hämtas för att täcka [Period.MANAD] plus en buffert för helger/röda dagar utan NAV. */
    const val HISTORY_LOOKBACK_DAYS = 45L

    /**
     * Resultatet av [holdingChange] för ett enskilt innehav (issue #18):
     * - [Available] — tillräckligt färsk och djup historik, kr + % beräknat.
     * - [StalePrice] — vår senast kända kurs för fonden är äldre än periodens start; vi vet
     *   alltså inte vad som hänt i perioden och gissar därför aldrig ett `0`.
     * - [InsufficientHistory] — kursen är färsk nog, men historiken når inte tillbaka till
     *   periodens start (t.ex. en nyligen tillagd fond).
     */
    sealed interface PeriodResult {
        data class Available(val amount: Double, val fraction: Double?) : PeriodResult
        data object StalePrice : PeriodResult
        data object InsufficientHistory : PeriodResult
    }

    /** Som [PeriodResult], men för hela portföljens summerade förändring (issue #18). [Available.partial] = se [totalChange]. */
    sealed interface PortfolioPeriodResult {
        data class Available(val amount: Double, val fraction: Double?, val partial: Boolean) : PortfolioPeriodResult
        data object StalePrice : PortfolioPeriodResult
        data object InsufficientHistory : PortfolioPeriodResult
    }

    /** Dag/vecka/månad-förändring för ett enskilt innehav, se [holdingChange]. Null = innehavet saknar känd kurs helt (POR-3 äger den markeringen). */
    data class HoldingPerformance(val day: PeriodResult?, val week: PeriodResult?, val month: PeriodResult?)

    /** Dag/vecka/månad-förändring för hela portföljen, se [totalChange]. */
    data class PortfolioPerformance(val day: PortfolioPeriodResult, val week: PortfolioPeriodResult, val month: PortfolioPeriodResult)

    /**
     * Förändring för ett innehav sedan periodens start.
     *
     * Null bara om innehavet helt saknar känd kurs ([Holding.currentValue] null) — samma
     * "kurs saknas ännu"-markering äger den vyn redan (POR-3), perioden ska inte dubblera den.
     *
     * Annars [PeriodResult.StalePrice] om vår senast kända kurs (det yngsta priset i
     * [history]) är **äldre än periodens start** — vi kan då inte veta om/hur mycket priset
     * rört sig under perioden, och ska inte gissa ett missvisande `0` (issue #18: detta var
     * tidigare bakvänt — `currentValue` och periodstartens pris blev av misstag samma,
     * inaktuella, prisrad så `amount`/`fraction` alltid blev exakt 0). [PeriodResult.InsufficientHistory]
     * om kursen är färsk nog men historiken inte når periodens start (t.ex. en nytillagd fond).
     */
    fun holdingChange(holding: Holding, period: Period, today: LocalDate, history: List<FundPrice>): PeriodResult? {
        val currentValue = holding.currentValue ?: return null
        val targetDay = today.minusDays(period.days).toEpochDay()

        // Vårt senast kända pris för fonden — om det redan är äldre än periodens start vet vi
        // inte vad som hänt sedan dess, och ska inte låtsas att inget hänt.
        val latestKnownDay = history.maxOfOrNull { it.epochDay }
        if (latestKnownDay == null || latestKnownDay <= targetDay) return PeriodResult.StalePrice

        val historicalNav = history.filter { it.epochDay <= targetDay }.maxByOrNull { it.epochDay }?.nav
            ?: return PeriodResult.InsufficientHistory
        val historicalValue = holding.netShares * historicalNav
        if (historicalValue == 0.0) return PeriodResult.InsufficientHistory
        val amount = currentValue - historicalValue
        return PeriodResult.Available(amount = amount, fraction = amount / historicalValue)
    }

    /** [holdingChange] för dag/vecka/månad i ett svep. */
    fun holdingPerformance(holding: Holding, today: LocalDate, history: List<FundPrice>): HoldingPerformance =
        HoldingPerformance(
            day = holdingChange(holding, Period.DAG, today, history),
            week = holdingChange(holding, Period.VECKA, today, history),
            month = holdingChange(holding, Period.MANAD, today, history),
        )

    /**
     * Summerar [holdingChange] över hela portföljen. Innehav som saknar tillräcklig historik
     * eller har en inaktuell kurs för perioden utesluts ur summan men gör att den (om minst
     * ett innehav ändå kunde beräknas) markeras som [PortfolioPeriodResult.Available.partial],
     * i stället för att antingen exkludera hela totalen eller låtsas att alla fonder är med.
     * Innehav utan känd kurs alls hoppas tyst över precis som i [PortfolioCalc] (POR-3 har
     * redan en egen "kurs saknas"-markering för dem — perioden ska inte dubblera den varningen).
     *
     * Om *inget* innehav kunde beräknas returneras [PortfolioPeriodResult.StalePrice] om
     * samtliga uteslutna berodde på en inaktuell kurs, annars [PortfolioPeriodResult.InsufficientHistory].
     */
    fun totalChange(
        holdings: List<Holding>,
        period: Period,
        today: LocalDate,
        historyByFundId: Map<String, List<FundPrice>>,
    ): PortfolioPeriodResult {
        var sumAmount = 0.0
        var sumHistoricalValue = 0.0
        var partial = false
        var anyComputed = false
        var anyStale = false
        var anyInsufficient = false

        for (holding in holdings) {
            val currentValue = holding.currentValue ?: continue
            val history = historyByFundId[holding.fund.fundId].orEmpty()
            when (val change = holdingChange(holding, period, today, history)) {
                is PeriodResult.Available -> {
                    anyComputed = true
                    sumAmount += change.amount
                    sumHistoricalValue += currentValue - change.amount
                }
                PeriodResult.StalePrice -> {
                    partial = true
                    anyStale = true
                }
                PeriodResult.InsufficientHistory -> {
                    partial = true
                    anyInsufficient = true
                }
                null -> Unit // ovnåbart — currentValue != null redan säkerställt ovan.
            }
        }

        if (!anyComputed) {
            return if (anyStale && !anyInsufficient) PortfolioPeriodResult.StalePrice else PortfolioPeriodResult.InsufficientHistory
        }
        val fraction = if (sumHistoricalValue != 0.0) sumAmount / sumHistoricalValue else null
        return PortfolioPeriodResult.Available(amount = sumAmount, fraction = fraction, partial = partial)
    }

    /** [totalChange] för dag/vecka/månad i ett svep. */
    fun totalPerformance(
        holdings: List<Holding>,
        today: LocalDate,
        historyByFundId: Map<String, List<FundPrice>>,
    ): PortfolioPerformance =
        PortfolioPerformance(
            day = totalChange(holdings, Period.DAG, today, historyByFundId),
            week = totalChange(holdings, Period.VECKA, today, historyByFundId),
            month = totalChange(holdings, Period.MANAD, today, historyByFundId),
        )
}
