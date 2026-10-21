package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/**
 * Värdeförändringen i kronor på de andelar som **faktiskt ägts**, dag för dag (issue #193).
 *
 * Mellan två på varandra följande kurspunkter räknas rörelsen `andelar × (NAV_i − NAV_{i−1})`
 * på de andelar som ägdes före punktens dag: ett köp på dagen betalar dagens NAV och får
 * rörelsen fram till dagen först från nästa punkt, ett sälj på dagen får dagens NAV och har
 * därför rörelsen med. Summan är det belopp som faktiskt ändrats på kontot under perioden –
 * samma kedja som [PortfolioReturnSeriesCalc], fast i kronor i stället för i procent.
 *
 * Tidigare räknades periodraderna som `(NAV nu − NAV vid periodstart) × dagens andelar`, vilket
 * gav andelar köpta mitt i perioden "avkastning" från dagar innan de köptes och sålda andelar
 * ingen alls. Procenten på samma rad är fondens kursutveckling och påverkas inte av detta.
 */
object PositionValueChangeCalc {

    /**
     * Värdeförändringen från kurspunkten på eller före [fromEpochDay] till och med den sista
     * kurspunkten på eller före [toEpochDay]. Null om ingen kurs finns på eller före
     * [fromEpochDay] – då finns ingen startpunkt att mäta från (samma princip som
     * [PortfolioPerformanceCalc.PeriodResult.InsufficientHistory]).
     *
     * Ägs inga andelar under hela perioden blir beloppet 0: ingenting har ändrats i kronor,
     * oavsett hur fondens kurs rört sig. Ett sälj kan inte ta innehavet under noll – andelar
     * utöver innehavet saknar känt köp och ignoreras, som i [RealizedGainCalculator].
     */
    fun valueChange(
        transactions: List<Transaction>,
        history: List<FundPrice>,
        fromEpochDay: Long,
        toEpochDay: Long
    ): Double? {
        val points = history.filter { it.epochDay <= toEpochDay }.sortedBy { it.epochDay }
        val startIndex = points.indexOfLast { it.epochDay <= fromEpochDay }
        if (startIndex < 0) return null

        val sortedTransactions = transactions.sortedBy { it.epochDay }
        var nextTransaction = 0
        var shares = 0.0
        var previousNav = points[startIndex].nav
        var change = 0.0
        for (index in startIndex + 1 until points.size) {
            val point = points[index]
            while (nextTransaction < sortedTransactions.size && sortedTransactions[nextTransaction].epochDay < point.epochDay) {
                val transaction = sortedTransactions[nextTransaction++]
                shares = when (transaction.type) {
                    TransactionType.KOP -> shares + transaction.shares
                    TransactionType.SALJ -> maxOf(0.0, shares - transaction.shares)
                }
            }
            change += shares * (point.nav - previousNav)
            previousNav = point.nav
        }
        return change
    }
}
