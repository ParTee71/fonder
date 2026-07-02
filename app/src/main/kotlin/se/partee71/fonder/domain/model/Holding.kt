package se.partee71.fonder.domain.model

/**
 * Ett sammanräknat innehav i en fond, härlett ur transaktioner.
 *
 * @param netShares antal andelar netto (köp − sälj).
 * @param netInvested nettoinvesterat belopp (köp − sälj), i fondens valuta.
 */
data class Holding(
    val fund: Fund,
    val netShares: Double,
    val netInvested: Double,
)
