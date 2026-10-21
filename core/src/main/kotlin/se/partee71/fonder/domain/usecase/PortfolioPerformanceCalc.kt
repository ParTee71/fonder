package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.Transaction

/**
 * Beräknar hur portföljens värde förändrats över korta perioder (dag/vecka/månad), utöver
 * det statiska nuläget i [PortfolioCalc] (issue #14). Måttet är förankrat i den **senaste
 * kända NAV-dagen** (referensdagen), inte väggklockans "idag": en period jämför NAV på
 * referensdagen mot NAV [Period.days] dagar dessförinnan. Det gör "En dag" (och vecka/månad)
 * beräkningsbar även innan dagens NAV publicerats eller för fonder vars NAV släpar (t.ex.
 * utländska fonder som rapporterar med några dagars fördröjning) — då visas den senaste
 * faktiska dagsrörelsen i stället för en tom "Kurs ej uppdaterad"-rad. Hur färsk referensdagen
 * är kommuniceras separat via "Värde per \<datum\>" (POR-7).
 *
 * Procenten är fondens kursutveckling under perioden (NAV mot NAV) och påverkas inte av köp/sälj.
 * Kronorna är värdeförändringen på de andelar som faktiskt ägts, dag för dag
 * ([PositionValueChangeCalc], issue #193) — tidigare räknades de som kursrörelsen × dagens
 * andelar, så andelar köpta mitt i perioden fick "avkastning" från dagar innan de köptes.
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
        /** [amount] = värdeförändring på de andelar som ägts (kr), [fraction] = fondens kursutveckling. */
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

    /** Periodens fönster: referensdagen (senaste NAV på eller före idag) och kursen vid periodens start. */
    private data class Window(val referenceDay: Long, val historicalPrice: FundPrice)

    /**
     * Referensdagen är den senaste NAV-dagen på eller före [today] i [history] (så en hypotetisk
     * framtida punkt aldrig råkar bli referens). Null om historiken inte når [Period.days] dagar
     * före referensdagen, om närmaste kurs ligger mer än [NavCalendar.MAX_NAV_GAP_DAYS] (issue #35) före måldagen, eller
     * om ingen kurs finns alls.
     */
    private fun window(period: Period, today: LocalDate, history: List<FundPrice>): Window? {
        val todayEpochDay = today.toEpochDay()
        val referenceDay = NavCalendar.latestOnOrBefore(history, todayEpochDay)?.epochDay ?: return null
        val historicalPrice = NavCalendar.startPriceOnOrBefore(history, referenceDay - period.days) ?: return null
        return Window(referenceDay, historicalPrice)
    }

    /**
     * Förändring för ett innehav över perioden, förankrad i den senaste kända NAV-dagen.
     *
     * Null bara om innehavet helt saknar känd kurs ([Holding.currentValue] null) — samma
     * "kurs saknas ännu"-markering äger den vyn redan (POR-3), perioden ska inte dubblera den.
     *
     * "En dag" blir den senaste faktiska dagsrörelsen (referensdagen mot dagen före), oberoende
     * av klockslag eller om fondens NAV släpar — inte tomt tills dagens NAV publicerats (tidigare
     * krävdes en kurs daterad *idag*, vilket gjorde raden tom hela dagen och för eftersläpande
     * utländska fonder). [PeriodResult.InsufficientHistory] om historiken inte når [Period.days]
     * dagar före referensdagen, eller om ingen kurs finns alls — aldrig ett gissat `0` (issue #18).
     *
     * @param transactions fondens transaktioner — avgör hur många andelar som ägts varje dag i
     *   perioden ([PositionValueChangeCalc]); procenten behöver dem inte.
     */
    fun holdingChange(holding: Holding, transactions: List<Transaction>, period: Period, today: LocalDate, history: List<FundPrice>): PeriodResult? {
        val currentValue = holding.currentValue ?: return null
        val computed = compute(currentValue, holding, transactions, period, today, history) ?: return PeriodResult.InsufficientHistory
        return PeriodResult.Available(amount = computed.amount, fraction = computed.fraction)
    }

    /** Ett innehavs periodsiffror: [amount] i kr på ägda andelar, kursutvecklingen som [priceChange] mot [historicalValue] (dagens andelar × startkurs). */
    private class Computed(val amount: Double, val priceChange: Double, val historicalValue: Double) {
        val fraction: Double get() = priceChange / historicalValue
    }

    /** Null = otillräcklig historik för perioden (se [window]), eller ett startvärde på 0 som inget går att mäta mot. */
    private fun compute(
        currentValue: Double,
        holding: Holding,
        transactions: List<Transaction>,
        period: Period,
        today: LocalDate,
        history: List<FundPrice>
    ): Computed? {
        val window = window(period, today, history) ?: return null
        val historicalValue = holding.netShares * window.historicalPrice.nav
        if (historicalValue == 0.0) return null
        val amount = PositionValueChangeCalc.valueChange(transactions, history, window.historicalPrice.epochDay, window.referenceDay) ?: return null
        return Computed(amount = amount, priceChange = currentValue - historicalValue, historicalValue = historicalValue)
    }

    /** [holdingChange] för dag/vecka/månad i ett svep. */
    fun holdingPerformance(holding: Holding, transactions: List<Transaction>, today: LocalDate, history: List<FundPrice>): HoldingPerformance = HoldingPerformance(
        day = holdingChange(holding, transactions, Period.DAG, today, history),
        week = holdingChange(holding, transactions, Period.VECKA, today, history),
        month = holdingChange(holding, transactions, Period.MANAD, today, history)
    )

    /**
     * Summerar [holdingChange] över hela portföljen. Innehav som saknar tillräcklig historik
     * för perioden utesluts ur summan men gör att den (om minst ett innehav ändå kunde beräknas)
     * markeras som [PortfolioPeriodResult.Available.partial], i stället för att antingen exkludera
     * hela totalen eller låtsas att alla fonder är med. Innehav utan känd kurs alls hoppas tyst
     * över precis som i [PortfolioCalc] (POR-3 har redan en egen "kurs saknas"-markering för dem).
     *
     * Kronorna är summan av innehavens värdeförändring på ägda andelar; procenten är fondernas
     * kursutveckling vägd med värdet vid periodens start (dagens andelar × startkurs) — ett köp
     * inom perioden ändrar alltså kronorna men inte procenten.
     *
     * Om *inget* innehav kunde beräknas returneras [PortfolioPeriodResult.InsufficientHistory].
     */
    fun totalChange(
        holdings: List<Holding>,
        transactionsByFundId: Map<String, List<Transaction>>,
        period: Period,
        today: LocalDate,
        historyByFundId: Map<String, List<FundPrice>>
    ): PortfolioPeriodResult {
        var sumAmount = 0.0
        var sumPriceChange = 0.0
        var sumHistoricalValue = 0.0
        var partial = false
        var anyComputed = false

        for (holding in holdings) {
            val currentValue = holding.currentValue ?: continue
            val fundId = holding.fund.fundId
            val computed = compute(currentValue, holding, transactionsByFundId[fundId].orEmpty(), period, today, historyByFundId[fundId].orEmpty())
            if (computed == null) {
                partial = true
                continue
            }
            anyComputed = true
            sumAmount += computed.amount
            sumPriceChange += computed.priceChange
            sumHistoricalValue += computed.historicalValue
        }

        if (!anyComputed) return PortfolioPeriodResult.InsufficientHistory
        val fraction = if (sumHistoricalValue != 0.0) sumPriceChange / sumHistoricalValue else null
        return PortfolioPeriodResult.Available(amount = sumAmount, fraction = fraction, partial = partial)
    }

    /** [totalChange] för dag/vecka/månad i ett svep. */
    fun totalPerformance(
        holdings: List<Holding>,
        transactionsByFundId: Map<String, List<Transaction>>,
        today: LocalDate,
        historyByFundId: Map<String, List<FundPrice>>
    ): PortfolioPerformance = PortfolioPerformance(
        day = totalChange(holdings, transactionsByFundId, Period.DAG, today, historyByFundId),
        week = totalChange(holdings, transactionsByFundId, Period.VECKA, today, historyByFundId),
        month = totalChange(holdings, transactionsByFundId, Period.MANAD, today, historyByFundId)
    )
}
