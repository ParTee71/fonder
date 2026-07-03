package se.partee71.fonder.domain.model

/**
 * Ett sammanräknat innehav i en fond, härlett ur transaktioner.
 *
 * @param netShares antal andelar netto (köp − sälj).
 * @param netInvested nettoinvesterat belopp (köp − sälj), i fondens valuta.
 * @param currentValue nuvarande värde (netShares × senaste NAV), eller null om ingen
 *   kurs finns cachad än (se issue #6 — visa "kurs saknas" i UI, krascha aldrig).
 */
data class Holding(
    val fund: Fund,
    val netShares: Double,
    val netInvested: Double,
    val currentValue: Double? = null,
) {
    /** Vinst/förlust i kr, eller null om [currentValue] är okänt. */
    val gainLoss: Double? get() = currentValue?.minus(netInvested)

    /** Vinst/förlust som andel av nettoinvesterat, eller null om okänt/ingenting investerat. */
    val gainLossFraction: Double? get() {
        val value = currentValue ?: return null
        if (netInvested == 0.0) return null
        return (value - netInvested) / netInvested
    }
}
