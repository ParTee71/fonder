package se.partee71.fonder.domain.model

/**
 * Ackumulerat realiserat resultat för en fond som haft minst en säljtransaktion, beräknat
 * med FIFO (äldsta köp matchas mot sälj i tidsordning) — se
 * [se.partee71.fonder.domain.usecase.FifoResultCalc] (issue #10).
 *
 * @param costBasis anskaffningskostnad för de sålda andelarna, eller null om köphistoriken
 *   inte räcker för att matcha alla sälj (visas som okänt resultat i UI:t, aldrig ett
 *   felaktigt tal).
 * @param realizedGainLoss [proceeds] minus [costBasis], eller null i samma fall som ovan.
 */
data class SoldFundResult(
    val fund: Fund,
    val sharesSold: Double,
    val proceeds: Double,
    val costBasis: Double?,
    val realizedGainLoss: Double?,
) {
    /** Realiserad avkastning som andel av anskaffningskostnaden, eller null om okänt/ingen kostnad. */
    val realizedGainLossFraction: Double?
        get() {
            val cost = costBasis ?: return null
            val gain = realizedGainLoss ?: return null
            if (cost == 0.0) return null
            return gain / cost
        }
}
