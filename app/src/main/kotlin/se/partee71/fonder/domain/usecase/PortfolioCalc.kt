package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/**
 * Ren, testbar sammanräkning av innehav ur fonder + transaktioner, samt värdering mot
 * kända kurser (issue #6).
 */
object PortfolioCalc {

    /** Räknar ut nettoinnehav per fond. Fonder utan transaktioner utelämnas. */
    fun computeHoldings(funds: List<Fund>, transactions: List<Transaction>): List<Holding> {
        val byFundId = funds.associateBy { it.fundId }
        return transactions
            .groupBy { it.fundId }
            .mapNotNull { (fundId, txs) ->
                val fund = byFundId[fundId] ?: return@mapNotNull null
                var netShares = 0.0
                var netInvested = 0.0
                for (tx in txs) {
                    val sign = if (tx.type == TransactionType.KOP) 1.0 else -1.0
                    netShares += sign * tx.shares
                    netInvested += sign * tx.amount
                }
                Holding(fund = fund, netShares = netShares, netInvested = netInvested)
            }
            .sortedBy { it.fund.name.lowercase() }
    }

    /**
     * Berikar innehav med nuvarande värde (netShares × senaste NAV) utifrån kända kurser.
     * Fonder utan kurs i [prices] behåller `currentValue = null` — visas som "kurs saknas"
     * i UI:t, aldrig en krasch eller ett felaktigt värde.
     */
    fun withCurrentValue(holdings: List<Holding>, prices: Map<String, FundPrice>): List<Holding> =
        holdings.map { holding ->
            val nav = prices[holding.fund.fundId]?.nav ?: return@map holding
            holding.copy(currentValue = holding.netShares * nav)
        }

    /** Summa nettoinvesterat över alla innehav. */
    fun totalInvested(holdings: List<Holding>): Double =
        holdings.sumOf { it.netInvested }

    /** Summa nuvarande värde över innehav med känd kurs (innehav utan kurs uteblir ur summan). */
    fun totalValue(holdings: List<Holding>): Double =
        holdings.sumOf { it.currentValue ?: 0.0 }

    /**
     * Total vinst/förlust i kr, endast baserat på innehav med känd kurs (så ett innehav
     * utan kurs inte tycks vara en förlust på hela dess nettoinvestering).
     */
    fun totalGainLoss(holdings: List<Holding>): Double =
        holdings.sumOf { it.gainLoss ?: 0.0 }

    /** Total avkastning i andel, baserat på nettoinvesterat för innehav med känd kurs. */
    fun totalGainLossFraction(holdings: List<Holding>): Double? {
        val known = holdings.filter { it.currentValue != null }
        val invested = known.sumOf { it.netInvested }
        if (invested == 0.0) return null
        val gain = known.sumOf { it.gainLoss ?: 0.0 }
        return gain / invested
    }
}
