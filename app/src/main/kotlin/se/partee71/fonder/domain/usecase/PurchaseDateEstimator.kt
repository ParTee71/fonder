package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.FundPrice
import kotlin.math.abs

/**
 * Uppskattar inköpsdatumet för en importerad innehavsrad (issue #8), som bara anger
 * snittkurs vid anskaffning — inte det verkliga köptillfället. Hittar dagen i kurshistoriken
 * vars NAV ligger närmast snittkursen.
 *
 * **Begränsning:** [FundPriceRepository][se.partee71.fonder.data.repository.FundPriceRepository]
 * cachar bara ungefär ett års historik (`refresh()` hämtar senaste året). Köp äldre än så
 * kan aldrig hittas exakt — [Estimate.confident] blir `false` när den närmaste träffen ändå
 * avviker för mycket, så att UI:t kan be användaren välja datum manuellt i stället.
 */
object PurchaseDateEstimator {

    /** Relativ avvikelse (mot snittkursen) inom vilken en träff räknas som tillförlitlig. */
    private const val CONFIDENCE_THRESHOLD = 0.07

    data class Estimate(val epochDay: Long, val confident: Boolean)

    /** Null om ingen kurshistorik finns alls — då kan inget datum uppskattas. */
    fun estimate(averageCostPerShare: Double, priceHistory: List<FundPrice>): Estimate? {
        if (priceHistory.isEmpty() || averageCostPerShare <= 0.0) return null

        val closest = priceHistory.minByOrNull { abs(it.nav - averageCostPerShare) } ?: return null
        val deviation = abs(closest.nav - averageCostPerShare) / averageCostPerShare
        return Estimate(epochDay = closest.epochDay, confident = deviation <= CONFIDENCE_THRESHOLD)
    }
}
