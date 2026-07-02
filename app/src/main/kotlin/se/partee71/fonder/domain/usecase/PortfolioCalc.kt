package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/**
 * Ren, testbar sammanräkning av innehav ur fonder + transaktioner.
 * Kursberoende värdering tillkommer när kurskällan finns (spike-issue #2).
 */
object PortfolioCalc {

    /** Räknar ut nettoinnehav per fond. Fonder utan transaktioner utelämnas. */
    fun computeHoldings(funds: List<Fund>, transactions: List<Transaction>): List<Holding> {
        val byIsin = funds.associateBy { it.isin }
        return transactions
            .groupBy { it.fundIsin }
            .mapNotNull { (isin, txs) ->
                val fund = byIsin[isin] ?: return@mapNotNull null
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

    /** Summa nettoinvesterat över alla innehav. */
    fun totalInvested(holdings: List<Holding>): Double =
        holdings.sumOf { it.netInvested }
}
