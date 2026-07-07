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

    /**
     * Räknar ut nettoinnehav per fond. Fonder utan transaktioner utelämnas.
     *
     * `netInvested` är det kvarvarande (ej sålda) andelarnas verkliga anskaffningsvärde,
     * matchat med FIFO ([FifoResultCalc]) — inte kassaflödet (köp minus säljintäkter). Det
     * gör orealiserat resultat korrekt även efter en delförsäljning (issue #10).
     */
    fun computeHoldings(funds: List<Fund>, transactions: List<Transaction>): List<Holding> {
        val byFundId = funds.associateBy { it.fundId }
        return transactions
            .groupBy { it.fundId }
            .mapNotNull { (fundId, txs) ->
                val fund = byFundId[fundId] ?: return@mapNotNull null
                val netShares = txs.sumOf { if (it.type == TransactionType.KOP) it.shares else -it.shares }
                val fifo = FifoResultCalc.remainingCostBasis(txs)
                Holding(fund = fund, netShares = netShares, netInvested = fifo.remainingCostBasis)
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
