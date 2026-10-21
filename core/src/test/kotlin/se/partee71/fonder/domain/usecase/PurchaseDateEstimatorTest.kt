package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.FundPrice

class PurchaseDateEstimatorTest {

    private fun price(epochDay: Long, nav: Double) = FundPrice(fundId = "SHB0000442", epochDay = epochDay, nav = nav, currency = "SEK")

    @Test
    fun `hittar dagen med narmast matchande kurs`() {
        val history = listOf(price(100, 700.0), price(150, 950.0), price(200, 1200.0))

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
    fun `kursen vid flera skilda tillfallen ger senaste dagen och osaker`() {
        val history = listOf(
            price(19_430, 147.0), // mars 2023
            price(19_431, 153.0),
            price(19_996, 151.0), // oktober 2024
            price(20_500, 148.0), // februari 2026
            price(20_501, 100.0)
        )

        val estimate = PurchaseDateEstimator.estimate(averageCostPerShare = 150.0, priceHistory = history)

        assertEquals(20_500L, estimate?.epochDay)
        assertFalse(estimate?.confident ?: true)
    }

    @Test
    fun `unik traff ger sakert resultat`() {
        val history = listOf(price(100, 100.0), price(200, 150.5), price(300, 300.0))

        val estimate = PurchaseDateEstimator.estimate(averageCostPerShare = 150.0, priceHistory = history)

        assertEquals(200L, estimate?.epochDay)
        assertTrue(estimate?.confident ?: false)
    }

    @Test
    fun `traffar inom ett manadsfonster ar entydiga och ger senaste dagen`() {
        val history = listOf(price(100, 149.0), price(110, 151.0), price(120, 150.0))

        val estimate = PurchaseDateEstimator.estimate(averageCostPerShare = 150.0, priceHistory = history)

        assertEquals(120L, estimate?.epochDay)
        assertTrue(estimate?.confident ?: false)
    }

    @Test
    fun `omvand sortering ger samma resultat`() {
        val history = listOf(price(19_430, 147.0), price(19_996, 151.0), price(20_500, 148.0), price(20_600, 90.0))

        val forward = PurchaseDateEstimator.estimate(150.0, history)
        val reversed = PurchaseDateEstimator.estimate(150.0, history.reversed())

        assertEquals(forward, reversed)
    }

    @Test
    fun `lika nara dagar utan traff ger senaste dagen och osaker`() {
        val history = listOf(price(100, 500.0), price(200, 500.0), price(150, 400.0))

        val estimate = PurchaseDateEstimator.estimate(averageCostPerShare = 950.0, priceHistory = history)

        assertEquals(200L, estimate?.epochDay)
        assertFalse(estimate?.confident ?: true)
        assertEquals(estimate, PurchaseDateEstimator.estimate(950.0, history.reversed()))
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
