package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/**
 * Realiserat resultat för en enskild säljtransaktion (issue #10).
 *
 * @param costBasis anskaffningsvärde för de sålda andelarna enligt **genomsnittsmetoden**
 *   (Skatteverket, issue #192): sålda andelar × fondens genomsnittliga anskaffningsvärde per
 *   andel vid säljtillfället, köpavgifter inräknade.
 * @param uncoveredShares antal sålda andelar som inte täcktes av något känt innehav (t.ex. om en
 *   tidigare köptransaktion saknas i historiken). [costBasis] täcker bara de andelar som
 *   faktiskt täcktes — ett värde > 0 här betyder att [realizedGain] är en underskattning av
 *   kostnaden (överskattning av vinsten) och bör visas som osäkert i UI, aldrig tystas ner.
 */
data class RealizedSale(
    val transactionId: Long,
    val fundId: String,
    val epochDay: Long,
    val shares: Double,
    val proceeds: Double,
    val fee: Double,
    val costBasis: Double,
    val uncoveredShares: Double
) {
    /** Realiserat resultat i kr = försäljningsbelopp − avgift − anskaffningsvärde (genomsnittsmetoden). */
    val realizedGain: Double get() = proceeds - fee - costBasis

    /** Realiserat resultat som andel av anskaffningsvärdet, eller null om det är 0 (inget att jämföra mot). */
    val realizedGainFraction: Double? get() = if (costBasis == 0.0) null else realizedGain / costBasis
}

/**
 * Summerat realiserat resultat över en mängd sälj (SLD-3, TRX-4) — det Händelsers kort visar för
 * året och, i säljfiltret, sedan start.
 *
 * @param uncertain sant om något av de ingående säljen har `uncoveredShares` (SLD-2): summan
 *   överskattar då vinsten och ska visas som osäker, aldrig tystas ner.
 */
data class RealizedTotal(
    val realizedGain: Double,
    val costBasis: Double,
    val saleCount: Int,
    val uncertain: Boolean
) {
    /** [realizedGain] som andel av summerat anskaffningsvärde, eller null om den summan är 0. */
    val realizedGainFraction: Double? get() = if (costBasis == 0.0) null else realizedGain / costBasis
}

/**
 * Kvarvarande (ej sålda) andelar för en fond och deras anskaffningsvärde enligt
 * genomsnittsmetoden — det som är kvar av fondens samlade anskaffningsvärde (köpbelopp +
 * köpavgifter) efter att varje sälj dragit sin andel. Underlag för [PortfolioCalc] (POR-1),
 * skiljt från [RealizedSale] som gäller de sålda andelarna.
 */
data class RemainingPosition(
    val shares: Double,
    val costBasis: Double,
    /**
     * Köpdatum för **positionens** första köp: det första köpet efter att innehavet senast var
     * noll — inte fondens äldsta transaktion. En position som sålts av helt och köpts igen ska
     * räknas från det nya köpet: både [costBasis] och "sedan köp"-avkastningen (ANA-1) beskriver
     * annars två olika positioner. Genomsnittsmetoden har inga lotter, så ett delsälj flyttar
     * aldrig datumet (issue #192). Null bara om positionen saknar köp helt.
     */
    val firstPurchaseEpochDay: Long?
)

/**
 * Anskaffningsmotor enligt **genomsnittsmetoden** (Skatteverket, K4; skill `fondmatematik`,
 * issue #192 — tidigare FIFO) som ger både realiserat resultat per säljtransaktion och
 * kvarvarande anskaffningsvärde för ej sålda andelar — oavsett om transaktionerna kommer från
 * manuell registrering, Excel-import eller PDF-import (issue #10). Per fond: ett genomsnittligt
 * anskaffningsvärde per andel = (summa köpbelopp + köpavgifter) / andelar, omräknat vid varje
 * köp; ett sälj konsumerar `sålda andelar × snitt` och ändrar inte snittet; säljavgiften dras
 * från likviden, inte från anskaffningsvärdet. Ren, testbar domänlogik utan repository-beroende.
 * Delar en enda genomgång mellan de två vyerna så att [PortfolioCalc]s orealiserade resultat och
 * Händelsers realiserade resultat (SLD-1) alltid bygger på samma sanning. Golden:
 * `realized_average_cost` i `tools/fondmatte`.
 */
object RealizedGainCalculator {

    private class FundResult(val sales: List<RealizedSale>, val remaining: RemainingPosition)

    /** Toleransgräns för flyttalsjämförelser av andelar (undviker att avrundningsfel lämnar en försumbar rest). */
    private const val SHARE_EPSILON = 1e-9

    private fun computeForFund(fundId: String, txsForFund: List<Transaction>): FundResult {
        var shares = 0.0
        var cost = 0.0
        var positionStartEpochDay: Long? = null
        val sales = mutableListOf<RealizedSale>()
        val sorted = txsForFund.sortedWith(compareBy({ it.epochDay }, { it.id }))

        for (tx in sorted) {
            // En rad utan andelar (0 andelar, bara avgift) kan varken starta en position eller
            // bära kostnad — den hoppas över i stället för att smitta nästa köps snitt.
            if (tx.shares <= SHARE_EPSILON) continue
            when (tx.type) {
                TransactionType.KOP -> {
                    if (shares <= SHARE_EPSILON) positionStartEpochDay = tx.epochDay
                    shares += tx.shares
                    cost += tx.amount + tx.fee
                }

                TransactionType.SALJ -> {
                    val covered = minOf(tx.shares, shares)
                    // Ett sälj som tömmer innehavet tar exakt resten, så summan av säljens
                    // anskaffningsvärden alltid är summan av köpen (ingen ulp-rest).
                    val costBasis = when {
                        shares <= SHARE_EPSILON -> 0.0
                        covered >= shares -> cost
                        else -> cost * covered / shares
                    }
                    shares -= covered
                    cost -= costBasis
                    if (shares <= SHARE_EPSILON) {
                        shares = 0.0
                        cost = 0.0
                        positionStartEpochDay = null
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
                            uncoveredShares = (tx.shares - covered).coerceAtLeast(0.0)
                        )
                    )
                }
            }
        }

        return FundResult(sales, RemainingPosition(shares = shares, costBasis = cost, firstPurchaseEpochDay = positionStartEpochDay))
    }

    /** Realiserade sälj, en post per säljtransaktion, sorterat senaste först. */
    fun compute(transactions: List<Transaction>): List<RealizedSale> = transactions
        .groupBy { it.fundId }
        .flatMap { (fundId, txsForFund) -> computeForFund(fundId, txsForFund).sales }
        .sortedWith(compareByDescending<RealizedSale> { it.epochDay }.thenByDescending { it.transactionId })

    /** Summerat realiserat resultat över [sales] (SLD-3). */
    fun total(sales: List<RealizedSale>): RealizedTotal = RealizedTotal(
        realizedGain = sales.sumOf { it.realizedGain },
        costBasis = sales.sumOf { it.costBasis },
        saleCount = sales.size,
        uncertain = sales.any { it.uncoveredShares > 0.0 }
    )

    /** Summerat realiserat resultat för sälj gjorda under kalenderåret [year] (TRX-4). */
    fun totalForYear(sales: List<RealizedSale>, year: Int): RealizedTotal = total(sales.filter { LocalDate.ofEpochDay(it.epochDay).year == year })

    /**
     * Kvarvarande andelar och anskaffningsvärde (genomsnittsmetoden) per fond, efter alla kända
     * sälj. Fonder utan kvarvarande andelar (helt avsålda) finns inte med i resultatet — se
     * [PortfolioCalc.computeHoldings] (POR-1).
     */
    fun remainingPositions(transactions: List<Transaction>): Map<String, RemainingPosition> = transactions
        .groupBy { it.fundId }
        .mapValues { (fundId, txsForFund) -> computeForFund(fundId, txsForFund).remaining }
        .filterValues { it.shares > SHARE_EPSILON }
}
