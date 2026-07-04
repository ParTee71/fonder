package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import se.partee71.fonder.domain.model.FundPrice

class PurchaseDateEstimatorTest {

    private fun price(epochDay: Long, nav: Double) =
        FundPrice(fundId = "SHB0000442", epochDay = epochDay, nav = nav, currency = "SEK")

    @Test
    fun `hittar dagen med narmast matchande kurs`() {
        val history = listOf(price(100, 900.0), price(150, 950.0), price(200, 1000.0))

        val estimate = PurchaseDateEstimator.estimate(averageCostPerShare = 950.68, priceHistory = history)

        assertEquals(150L, estimate?.epochDay)
        assertTrue(estimate?.confident ?: false)
    }

    @Test
    fun `markeras som osaker vid stor avvikelse`() {
        val history = listOf(price(100, 500.0), price(200, 600.0))

        val estimate = PurchaseDateEstimator.estimate(averageCostPerShare = 950.0, priceHistory = history)

        assertEquals(200L, estimate?.epochDay)
        assertFalse(estimate?.confident ?: true)
    }

    @Test
    fun `ingen historik ger null`() {
        assertNull(PurchaseDateEstimator.estimate(averageCostPerShare = 950.0, priceHistory = emptyList()))
    }

    @Test
    fun `noll eller negativ snittkurs ger null`() {
        val history = listOf(price(100, 500.0))
        assertNull(PurchaseDateEstimator.estimate(averageCostPerShare = 0.0, priceHistory = history))
        assertNull(PurchaseDateEstimator.estimate(averageCostPerShare = -5.0, priceHistory = history))
    }
}
