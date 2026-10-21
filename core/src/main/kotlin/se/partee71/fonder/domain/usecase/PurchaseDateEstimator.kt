package se.partee71.fonder.domain.usecase

import kotlin.math.abs
import se.partee71.fonder.domain.model.FundPrice

/**
 * Uppskattar inköpsdatumet för en importerad innehavsrad (issue #8), som bara anger
 * snittkurs vid anskaffning — inte det verkliga köptillfället.
 *
 * **Regel (issue #200):** bland alla dagar vars NAV ligger inom [CONFIDENCE_THRESHOLD] från
 * snittkursen väljs den *senaste* dagen. [Estimate.confident] är bara `true` när alla dessa dagar
 * ryms i ett spann (första till sista träffen) på högst [MAX_WINDOW_DAYS] dagar — annars har kursen
 * passerat nivån vid flera skilda tillfällen och användaren ska välja datum själv. Finns ingen
 * dag inom toleransen väljs den närmaste (vid lika nära den senaste) och `confident` är `false`.
 * Resultatet beror inte på historikens sortering.
 *
 * **Begränsning:** datumet söks bara i den kurshistorik importen hämtar från
 * [FundPriceRepository][se.partee71.fonder.data.repository.FundPriceRepository] (TP-13); äldre köp kan inte hittas.
 */
object PurchaseDateEstimator {

    /** Relativ avvikelse (mot snittkursen) inom vilken en dag räknas som träff. */
    private const val CONFIDENCE_THRESHOLD = 0.07

    /** Största spann (dagar) mellan första och sista träffen för en entydig träff: ett köptillfälle som ryms i ungefär en månads kursrörelse. */
    private const val MAX_WINDOW_DAYS = 30L

    data class Estimate(val epochDay: Long, val confident: Boolean)

    /** Null om ingen kurshistorik finns alls — då kan inget datum uppskattas. */
    fun estimate(averageCostPerShare: Double, priceHistory: List<FundPrice>): Estimate? {
        if (priceHistory.isEmpty() || averageCostPerShare <= 0.0) return null

        fun deviation(price: FundPrice) = abs(price.nav - averageCostPerShare) / averageCostPerShare

        val matches = priceHistory.filter { deviation(it) <= CONFIDENCE_THRESHOLD }
        if (matches.isEmpty()) {
            val smallest = priceHistory.minOf { deviation(it) }
            val latestClosest = priceHistory.filter { deviation(it) == smallest }.maxOf { it.epochDay }
            return Estimate(epochDay = latestClosest, confident = false)
        }
        val latest = matches.maxOf { it.epochDay }
        val earliest = matches.minOf { it.epochDay }
        return Estimate(epochDay = latest, confident = latest - earliest <= MAX_WINDOW_DAYS)
    }
}
