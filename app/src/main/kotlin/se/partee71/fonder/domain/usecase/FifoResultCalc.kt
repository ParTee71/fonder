package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.SoldFundResult
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/** Toleransen som avgör när en lott/sälj räknas som helt konsumerad (rundningsfel i Double). */
private const val SHARE_EPSILON = 1e-9

/**
 * FIFO-baserad matchning av sälj mot köp (äldsta köpet säljs först). Ren, testbar sammanräkning
 * per fond — grunden för både kvarvarande anskaffningsvärde på ej sålda andelar
 * ([PortfolioCalc]) och realiserat resultat för sålda fonder (issue #10).
 */
object FifoResultCalc {

    /** En kvarvarande post köpta andelar, med sitt ursprungliga inköpspris per andel. */
    private data class Lot(var shares: Double, val costPerShare: Double)

    /** Resultatet av att köra FIFO-motorn över en enda fonds transaktioner. */
    data class FundResult(
        val remainingShares: Double,
        val remainingCostBasis: Double,
        val sharesSold: Double,
        val proceeds: Double,
        val costBasisOfSold: Double,
        val hasUnmatchedSells: Boolean,
    ) {
        /** Realiserat resultat, eller null om köphistoriken inte räckte för att matcha alla sälj. */
        val realizedGainLoss: Double? get() = if (hasUnmatchedSells) null else proceeds - costBasisOfSold
    }

    /**
     * Kör FIFO-motorn för en fonds transaktioner. [transactions] måste vara i tidsordning
     * (äldst→nyast) — annars matchas fel köp mot fel sälj.
     */
    fun run(transactions: List<Transaction>): FundResult {
        val lots = ArrayDeque<Lot>()
        var sharesSold = 0.0
        var proceeds = 0.0
        var costBasisOfSold = 0.0
        var hasUnmatchedSells = false

        for (tx in transactions) {
            when (tx.type) {
                TransactionType.KOP -> lots.addLast(Lot(shares = tx.shares, costPerShare = tx.pricePerShare))
                TransactionType.SALJ -> {
                    var remainingToSell = tx.shares
                    proceeds += tx.amount
                    sharesSold += tx.shares
                    while (remainingToSell > SHARE_EPSILON && lots.isNotEmpty()) {
                        val lot = lots.first()
                        val consumed = minOf(lot.shares, remainingToSell)
                        costBasisOfSold += consumed * lot.costPerShare
                        lot.shares -= consumed
                        remainingToSell -= consumed
                        if (lot.shares <= SHARE_EPSILON) lots.removeFirst()
                    }
                    // Sälj utan tillräcklig köphistorik (t.ex. felregistrering eller import
                    // utan fullständig historik) — flaggas i stället för att gissa ett värde.
                    if (remainingToSell > SHARE_EPSILON) hasUnmatchedSells = true
                }
            }
        }

        return FundResult(
            remainingShares = lots.sumOf { it.shares },
            remainingCostBasis = lots.sumOf { it.shares * it.costPerShare },
            sharesSold = sharesSold,
            proceeds = proceeds,
            costBasisOfSold = costBasisOfSold,
            hasUnmatchedSells = hasUnmatchedSells,
        )
    }

    /** Sorterar en fonds transaktioner i tidsordning (äldst→nyast), oavsett inkommande ordning. */
    private fun chronological(transactions: List<Transaction>): List<Transaction> =
        transactions.sortedWith(compareBy({ it.epochDay }, { it.id }))

    /**
     * Sålda fonder (minst en säljtransaktion, manuell eller importerad) med ackumulerat
     * realiserat resultat — underlag för "Sålda fonder"-vyn (issue #10, KRAVLISTA SLD-1/SLD-2).
     */
    fun computeSoldFundResults(funds: List<Fund>, transactions: List<Transaction>): List<SoldFundResult> {
        val byFundId = funds.associateBy { it.fundId }
        return transactions
            .groupBy { it.fundId }
            .mapNotNull { (fundId, txs) ->
                val fund = byFundId[fundId] ?: return@mapNotNull null
                if (txs.none { it.type == TransactionType.SALJ }) return@mapNotNull null
                val result = run(chronological(txs))
                SoldFundResult(
                    fund = fund,
                    sharesSold = result.sharesSold,
                    proceeds = result.proceeds,
                    costBasis = if (result.hasUnmatchedSells) null else result.costBasisOfSold,
                    realizedGainLoss = result.realizedGainLoss,
                )
            }
            .sortedBy { it.fund.name.lowercase() }
    }

    /** Kvarvarande anskaffningsvärde (FIFO) för en fonds ej sålda andelar. Används av [PortfolioCalc]. */
    fun remainingCostBasis(transactions: List<Transaction>): FundResult = run(chronological(transactions))
}
