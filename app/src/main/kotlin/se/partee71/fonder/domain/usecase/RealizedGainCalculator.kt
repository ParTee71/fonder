package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/**
 * Realiserat resultat för en enskild säljtransaktion (issue #10).
 *
 * @param costBasis FIFO-anskaffningsvärde för de sålda andelarna — äldsta köp-lott
 *   konsumeras först. En delförsäljning kan konsumera flera lotter; [costBasis] är då
 *   summan över dem.
 * @param uncoveredShares antal sålda andelar som inte kunde matchas mot någon känd
 *   köp-lott (t.ex. om en tidigare köptransaktion saknas i historiken). [costBasis]
 *   täcker bara de andelar som faktiskt matchades — ett värde > 0 här betyder att
 *   [realizedGain] är en underskattning av kostnaden (överskattning av vinsten) och bör
 *   visas som osäkert i UI, aldrig tystas ner.
 */
data class RealizedSale(
    val transactionId: Long,
    val fundId: String,
    val epochDay: Long,
    val shares: Double,
    val proceeds: Double,
    val fee: Double,
    val costBasis: Double,
    val uncoveredShares: Double,
) {
    /** Realiserat resultat i kr = försäljningsbelopp − avgift − FIFO-anskaffningsvärde. */
    val realizedGain: Double get() = proceeds - fee - costBasis

    /** Realiserat resultat som andel av anskaffningsvärdet, eller null om det är 0 (inget att jämföra mot). */
    val realizedGainFraction: Double? get() = if (costBasis == 0.0) null else realizedGain / costBasis
}

/**
 * Kvarvarande (ej sålda) andelar för en fond och deras FIFO-anskaffningsvärde — vad som
 * blir kvar i respektive köp-lott efter att alla kända sälj konsumerat äldsta köpen
 * först. Underlag för [PortfolioCalc] (POR-1), skiljt från [RealizedSale] som gäller de
 * sålda andelarna.
 */
data class RemainingPosition(val shares: Double, val costBasis: Double)

/**
 * FIFO-motor (äldsta köp-lott konsumeras först) som ger både realiserat resultat per
 * säljtransaktion och kvarvarande anskaffningsvärde för ej sålda andelar — oavsett om
 * transaktionerna kommer från manuell registrering, Excel-import eller PDF-import
 * (issue #10). Ren, testbar domänlogik utan repository-beroende. Delar en enda
 * lot-konsumering mellan de två vyerna så att [PortfolioCalc]s orealiserade resultat och
 * "Sålda fonder"-vyns realiserade resultat alltid bygger på samma sanning.
 */
object RealizedGainCalculator {

    private data class Lot(var shares: Double, val pricePerShare: Double)

    private class FundFifoResult(val sales: List<RealizedSale>, val remainingLots: List<Lot>)

    /** Toleransgräns för flyttalsjämförelser av andelar (undviker att avrundningsfel lämnar en försumbar rest-lott). */
    private const val SHARE_EPSILON = 1e-9

    private fun computeForFund(fundId: String, txsForFund: List<Transaction>): FundFifoResult {
        val lots = ArrayDeque<Lot>()
        val sales = mutableListOf<RealizedSale>()
        val sorted = txsForFund.sortedWith(compareBy({ it.epochDay }, { it.id }))

        for (tx in sorted) {
            when (tx.type) {
                TransactionType.KOP -> lots.addLast(Lot(tx.shares, tx.pricePerShare))
                TransactionType.SALJ -> {
                    var remaining = tx.shares
                    var costBasis = 0.0
                    while (remaining > SHARE_EPSILON && lots.isNotEmpty()) {
                        val lot = lots.first()
                        val consumed = minOf(remaining, lot.shares)
                        costBasis += consumed * lot.pricePerShare
                        remaining -= consumed
                        lot.shares -= consumed
                        if (lot.shares <= SHARE_EPSILON) lots.removeFirst()
                    }
                    sales.add(
                        RealizedSale(
                            transactionId = tx.id,
                            fundId = fundId,
                            epochDay = tx.epochDay,
                            shares = tx.shares,
                            proceeds = tx.amount,
                            fee = tx.fee,
                            costBasis = costBasis,
                            uncoveredShares = remaining.coerceAtLeast(0.0),
                        ),
                    )
                }
            }
        }

        return FundFifoResult(sales, lots.toList())
    }

    /** Realiserade sälj, en post per säljtransaktion, sorterat senaste först. */
    fun compute(transactions: List<Transaction>): List<RealizedSale> =
        transactions
            .groupBy { it.fundId }
            .flatMap { (fundId, txsForFund) -> computeForFund(fundId, txsForFund).sales }
            .sortedWith(compareByDescending<RealizedSale> { it.epochDay }.thenByDescending { it.transactionId })

    /**
     * Kvarvarande andelar och FIFO-anskaffningsvärde per fond, efter att alla kända sälj
     * konsumerat äldsta köpen först. Fonder utan kvarvarande andelar (helt avsålda) finns
     * inte med i resultatet — se [PortfolioCalc.computeHoldings] (POR-1).
     */
    fun remainingPositions(transactions: List<Transaction>): Map<String, RemainingPosition> =
        transactions
            .groupBy { it.fundId }
            .mapValues { (fundId, txsForFund) -> computeForFund(fundId, txsForFund).remainingLots }
            .mapValues { (_, lots) -> RemainingPosition(lots.sumOf { it.shares }, lots.sumOf { it.shares * it.pricePerShare }) }
            .filterValues { it.shares > SHARE_EPSILON }
}
