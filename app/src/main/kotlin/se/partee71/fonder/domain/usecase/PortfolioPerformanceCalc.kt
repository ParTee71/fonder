package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import java.time.LocalDate

/**
 * Beräknar hur portföljens värde förändrats över korta perioder (dag/vecka/månad), utöver
 * det statiska nuläget i [PortfolioCalc] (issue #14). Måttet är förankrat i den **senaste
 * kända NAV-dagen** (referensdagen), inte väggklockans "idag": en period jämför NAV på
 * referensdagen mot NAV [Period.days] dagar dessförinnan. Det gör "En dag" (och vecka/månad)
 * beräkningsbar även innan dagens NAV publicerats eller för fonder vars NAV släpar (t.ex.
 * utländska fonder som rapporterar med några dagars fördröjning) — då visas den senaste
 * faktiska dagsrörelsen i stället för en tom "Kurs ej uppdaterad"-rad. Hur färsk referensdagen
 * är kommuniceras separat via "Värde per \<datum\>" (POR-7). Det är ett enkelt marknadsvärde-mått,
 * inte en kassaflödesjusterad avkastning (TWR/MWR) — köp/sälj inom perioden påverkar inte
 * beräkningen, bara prisrörelsen gör.
 */
object PortfolioPerformanceCalc {

    enum class Period(val days: Long) { DAG(1), VECKA(7), MANAD(30) }

    /** Hur långt tillbaka kurshistorik bör hämtas för att täcka [Period.MANAD] bakåt från referensdagen, plus buffert för helger/röda dagar och en något eftersläpande senaste kurs. */
    const val HISTORY_LOOKBACK_DAYS = 60L

    /**
     * Resultatet av [holdingChange] för ett enskilt innehav (issue #18):
     * - [Available] — referensdagens NAV och en NAV [Period.days] dagar dessförinnan finns, kr + % beräknat.
     * - [InsufficientHistory] — historiken når inte tillbaka [Period.days] dagar före referensdagen
     *   (t.ex. en nyligen tillagd fond), eller ingen kurs finns alls. Vi gissar då aldrig ett `0`.
     */
    sealed interface PeriodResult {
        data class Available(val amount: Double, val fraction: Double?) : PeriodResult
        data object InsufficientHistory : PeriodResult
    }

    /** Som [PeriodResult], men för hela portföljens summerade förändring (issue #18). [Available.partial] = se [totalChange]. */
    sealed interface PortfolioPeriodResult {
        data class Available(val amount: Double, val fraction: Double?, val partial: Boolean) : PortfolioPeriodResult
        data object InsufficientHistory : PortfolioPeriodResult
    }

    /** Dag/vecka/månad-förändring för ett enskilt innehav, se [holdingChange]. Null = innehavet saknar känd kurs helt (POR-3 äger den markeringen). */
    data class HoldingPerformance(val day: PeriodResult?, val week: PeriodResult?, val month: PeriodResult?)

    /** Dag/vecka/månad-förändring för hela portföljen, se [totalChange]. */
    data class PortfolioPerformance(val day: PortfolioPeriodResult, val week: PortfolioPeriodResult, val month: PortfolioPeriodResult)

    /**
     * Förändring för ett innehav över perioden, förankrad i den senaste kända NAV-dagen.
     *
     * Null bara om innehavet helt saknar känd kurs ([Holding.currentValue] null) — samma
     * "kurs saknas ännu"-markering äger den vyn redan (POR-3), perioden ska inte dubblera den.
     *
     * Referensdagen är den senaste NAV-dagen på eller före [today] i [history] (så en hypotetisk
     * framtida punkt aldrig råkar bli referens). "En dag" blir då den senaste faktiska
     * dagsrörelsen (referensdagen mot dagen före), oberoende av klockslag eller om fondens NAV
     * släpar — inte tomt tills dagens NAV publicerats (tidigare krävdes en kurs daterad *idag*,
     * vilket gjorde raden tom hela dagen och för eftersläpande utländska fonder). [PeriodResult.InsufficientHistory]
     * om historiken inte når [Period.days] dagar före referensdagen, eller om ingen kurs finns alls
     * — aldrig ett gissat `0` (issue #18).
     */
    fun holdingChange(holding: Holding, period: Period, today: LocalDate, history: List<FundPrice>): PeriodResult? {
        val currentValue = holding.currentValue ?: return null
        val todayEpochDay = today.toEpochDay()

        // Senaste kända NAV-dag på eller före idag — periodens slutpunkt (referensdag).
        val referenceDay = history.filter { it.epochDay <= todayEpochDay }.maxOfOrNull { it.epochDay }
            ?: return PeriodResult.InsufficientHistory
        val targetDay = referenceDay - period.days

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
     * för perioden utesluts ur summan men gör att den (om minst ett innehav ändå kunde beräknas)
     * markeras som [PortfolioPeriodResult.Available.partial], i stället för att antingen exkludera
     * hela totalen eller låtsas att alla fonder är med. Innehav utan känd kurs alls hoppas tyst
     * över precis som i [PortfolioCalc] (POR-3 har redan en egen "kurs saknas"-markering för dem).
     *
     * Om *inget* innehav kunde beräknas returneras [PortfolioPeriodResult.InsufficientHistory].
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

        for (holding in holdings) {
            val currentValue = holding.currentValue ?: continue
            val history = historyByFundId[holding.fund.fundId].orEmpty()
            when (val change = holdingChange(holding, period, today, history)) {
                is PeriodResult.Available -> {
                    anyComputed = true
                    sumAmount += change.amount
                    sumHistoricalValue += currentValue - change.amount
                }
                PeriodResult.InsufficientHistory -> partial = true
                null -> Unit // ovnåbart — currentValue != null redan säkerställt ovan.
            }
        }

        if (!anyComputed) return PortfolioPeriodResult.InsufficientHistory
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
