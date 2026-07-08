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

    /** Förändring för ett enskilt innehav under en period. */
    data class Change(val amount: Double, val fraction: Double?)

    /**
     * Förändring för hela portföljen under en period.
     * [partial] = sant om något innehav med känd kurs saknade tillräcklig historik för
     * perioden och därför uteslöts ur summan (se [totalChange]).
     */
    data class TotalChange(val amount: Double, val fraction: Double?, val partial: Boolean)

    /** Dag/vecka/månad-förändring för ett enskilt innehav, se [holdingChange]. */
    data class HoldingPerformance(val day: Change?, val week: Change?, val month: Change?)

    /** Dag/vecka/månad-förändring för hela portföljen, se [totalChange]. */
    data class PortfolioPerformance(val day: TotalChange?, val week: TotalChange?, val month: TotalChange?)

    /**
     * Förändring för ett innehav sedan periodens start, eller null om historiken inte räcker
     * tillbaka till det datumet (t.ex. nytillagd fond) eller om innehavet saknar känd kurs —
     * markeras som otillräcklig data i UI:t i stället för att gissa (samma princip som
     * POR-3/SLD-2/IMP-2).
     */
    fun holdingChange(holding: Holding, period: Period, today: LocalDate, history: List<FundPrice>): Change? {
        val currentValue = holding.currentValue ?: return null
        val targetDay = today.minusDays(period.days).toEpochDay()
        val historicalNav = history.filter { it.epochDay <= targetDay }.maxByOrNull { it.epochDay } ?: return null
        val historicalValue = holding.netShares * historicalNav.nav
        if (historicalValue == 0.0) return null
        val amount = currentValue - historicalValue
        return Change(amount = amount, fraction = amount / historicalValue)
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
     * för perioden utesluts ur summan men gör att [TotalChange.partial] blir sant, i stället
     * för att antingen exkludera hela totalen eller låtsas att alla fonder är med. Innehav
     * utan känd kurs alls hoppas tyst över precis som i [PortfolioCalc] (POR-3 har redan en
     * egen "kurs saknas"-markering för dem — perioden ska inte dubblera den varningen).
     * Null returneras bara om *inget* innehav med känd kurs kunde beräknas för perioden.
     */
    fun totalChange(
        holdings: List<Holding>,
        period: Period,
        today: LocalDate,
        historyByFundId: Map<String, List<FundPrice>>,
    ): TotalChange? {
        var sumAmount = 0.0
        var sumHistoricalValue = 0.0
        var partial = false
        var anyComputed = false

        for (holding in holdings) {
            if (holding.currentValue == null) continue
            val history = historyByFundId[holding.fund.fundId].orEmpty()
            val change = holdingChange(holding, period, today, history)
            if (change == null) {
                partial = true
                continue
            }
            anyComputed = true
            sumAmount += change.amount
            sumHistoricalValue += holding.currentValue - change.amount
        }

        if (!anyComputed) return null
        val fraction = if (sumHistoricalValue != 0.0) sumAmount / sumHistoricalValue else null
        return TotalChange(amount = sumAmount, fraction = fraction, partial = partial)
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
