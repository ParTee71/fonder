package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.Transaction

/**
 * Ren, testbar sammanräkning av innehav ur fonder + transaktioner, samt värdering mot
 * kända kurser (issue #6).
 */
object PortfolioCalc {

    /**
     * Räknar ut nettoinnehav per fond. Fonder utan transaktioner **eller helt avsålda**
     * (nettoandelar ≈ 0) utelämnas — en stängd position är inte längre ett innehav och
     * ska inte synas i portföljen (se [RealizedGainCalculator] för dess realiserade
     * resultat i stället, issue #10).
     *
     * `netInvested` är de kvarvarande (ej sålda) andelarnas verkliga anskaffningsvärde,
     * matchat med FIFO ([RealizedGainCalculator.remainingPositions]) — inte kassaflödet
     * (köp minus säljintäkter). Det gör orealiserat resultat korrekt även efter en
     * delförsäljning, och även när andelarna köpts till olika pris vid olika tillfällen.
     */
    fun computeHoldings(funds: List<Fund>, transactions: List<Transaction>): List<Holding> {
        val byFundId = funds.associateBy { it.fundId }
        val positions = RealizedGainCalculator.remainingPositions(transactions)
        return transactions
            .groupBy { it.fundId }
            .mapNotNull { (fundId, txsForFund) ->
                val fund = byFundId[fundId] ?: return@mapNotNull null
                val position = positions[fundId] ?: return@mapNotNull null
                Holding(
                    fund = fund,
                    netShares = position.shares,
                    netInvested = position.costBasis,
                    firstPurchaseEpochDay = txsForFund.minOfOrNull { it.epochDay },
                )
            }
            .sortedBy { it.fund.name.lowercase() }
    }

    /**
     * Berikar innehav med nuvarande värde (netShares × senaste NAV) utifrån kända kurser.
     * Fonder utan kurs i [prices] behåller `currentValue = null` — visas som "kurs saknas"
     * i UI:t, aldrig en krasch eller ett felaktigt värde. [Holding.navEpochDay] sätts till
     * samma kurs NAV-datum, för "per <datum>"-visning (POR-7, issue #27).
     */
    fun withCurrentValue(holdings: List<Holding>, prices: Map<String, FundPrice>): List<Holding> =
        holdings.map { holding ->
            val price = prices[holding.fund.fundId] ?: return@map holding
            holding.copy(currentValue = holding.netShares * price.nav, navEpochDay = price.epochDay)
        }

    /**
     * Det äldsta NAV-datumet bland innehav med känt värde — totalens värde är aldrig färskare
     * än sin svagaste länk, samma "svagaste länk"-princip som "delvis osäker" (HEM-2). Null om
     * inget innehav har ett känt värde. Visas som "per <datum>" bredvid totalvärdet (POR-7).
     */
    fun oldestKnownNavEpochDay(holdings: List<Holding>): Long? =
        holdings.mapNotNull { if (it.currentValue != null) it.navEpochDay else null }.minOrNull()

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
