package se.partee71.fonder.domain.model

/**
 * Ett sammanräknat innehav i en fond, härlett ur transaktioner.
 *
 * @param netShares antal andelar netto (köp − sälj).
 * @param netInvested de kvarvarande (ej sålda) andelarnas verkliga anskaffningsvärde,
 *   matchat med FIFO ([se.partee71.fonder.domain.usecase.RealizedGainCalculator.remainingPositions])
 *   — inte kassaflödet (köp minus säljintäkter), se POR-1.
 * @param currentValue nuvarande värde (netShares × senaste NAV), eller null om ingen
 *   kurs finns cachad än (se issue #6 — visa "kurs saknas" i UI, krascha aldrig).
 * @param firstPurchaseEpochDay dagen för fondens tidigaste (ännu kvarvarande) köp, som
 *   epoch-day — visas tillsammans med [netInvested] i Portfölj/Fonddetalj (POR-6, issue #18).
 * @param navEpochDay epoch-day för den NAV [currentValue] är räknad på, eller null om
 *   [currentValue] är okänt — visas som "per <datum>" bredvid värdet (POR-7, issue #27) så
 *   en normal endagsförskjutning mot en extern källa (t.ex. banken) blir begriplig i stället
 *   för att se ut som ett fel.
 */
data class Holding(
    val fund: Fund,
    val netShares: Double,
    val netInvested: Double,
    val currentValue: Double? = null,
    val firstPurchaseEpochDay: Long? = null,
    val navEpochDay: Long? = null,
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
