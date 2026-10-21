package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/**
 * Värdeförändringen i kronor på de andelar som **faktiskt ägts**, dag för dag (issue #193).
 *
 * Mellan två på varandra följande kurspunkter räknas rörelsen `andelar × (NAV_i − NAV_{i−1})`
 * på de andelar som ägdes vid förra kurspunkten: ett köp på en kursdag betalar dagens NAV och får
 * rörelsen först från nästa punkt, ett sälj på en kursdag får dagens NAV och har därför rörelsen
 * med. En transaktion på en dag utan kurs (helg, lucka) räknas som handlad till nästa kurs. Summan är det belopp som faktiskt ändrats på kontot under perioden –
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
        var previous = points[startIndex]
        var change = 0.0
        for (index in startIndex + 1 until points.size) {
            val point = points[index]
            // Andelarna under steget är de som ägdes vid förra kurspunkten: en transaktion på en dag
            // utan kurs (helg, lucka) handlas till nästa kurs och får inte stegets rörelse före sig.
            while (nextTransaction < sortedTransactions.size && sortedTransactions[nextTransaction].epochDay <= previous.epochDay) {
                val transaction = sortedTransactions[nextTransaction++]
                shares = when (transaction.type) {
                    TransactionType.KOP -> shares + transaction.shares
                    TransactionType.SALJ -> maxOf(0.0, shares - transaction.shares)
                }
            }
            change += shares * (point.nav - previous.nav)
            previous = point
        }
        return change
    }
}
