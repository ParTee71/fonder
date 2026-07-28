package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.FundPrice
import java.time.LocalDate

class CurrencyConverterTest {

    private val day1 = LocalDate.of(2026, 7, 23).toEpochDay()
    private val day2 = LocalDate.of(2026, 7, 24).toEpochDay()

    @Test
    fun `kurser i kronor passerar oforandrade utan behov av vaxelkurs`() {
        val prices = listOf(FundPrice(fundId = "F", epochDay = day1, nav = 100.0, currency = "SEK"))

        val result = CurrencyConverter.toValueCurrency(prices, ratesByEpochDay = emptyMap())

        assertEquals(prices, result)
    }

    @Test
    fun `USD-kurs rakas om till kronor med samma dags vaxelkurs`() {
        // Verkligt exempel 2026-07-23: CPR Invest Global Gold Mines 193,48 USD, USD/SEK 9,73973
        // ur Riksbankens SEKUSDPMI — ger 1884,44 kr, nära Avanzas 1878,75 samma dag (KRAVLISTA TP-20).
        val prices = listOf(FundPrice(fundId = "0P0001KRE7", epochDay = day1, nav = 193.48, currency = "USD"))

        val result = CurrencyConverter.toValueCurrency(prices, mapOf(day1 to 9.73973))

        assertEquals(1, result.size)
        assertEquals("SEK", result.first().currency)
        assertEquals(1884.44, result.first().nav, 0.01)
    }

    @Test
    fun `saknad vaxelkurs anvander narmaste foregaende inom marginalen`() {
        // Fondens NAV kan vara daterad en dag valutamarknaden inte noterade (t.ex. en
        // amerikansk helgdag) — då återanvänds senaste kända kurs, inte en gissning.
        val threeDaysLater = day1 + 3
        val prices = listOf(FundPrice(fundId = "F", epochDay = threeDaysLater, nav = 10.0, currency = "USD"))

        val result = CurrencyConverter.toValueCurrency(prices, mapOf(day1 to 10.0))

        assertEquals(100.0, result.first().nav, 1e-9)
    }

    @Test
    fun `kurs utan vaxelkurs inom marginalen utelamnas`() {
        val farAway = day1 + CurrencyConverter.MAX_RATE_AGE_DAYS + 1
        val prices = listOf(FundPrice(fundId = "F", epochDay = farAway, nav = 10.0, currency = "USD"))

        val result = CurrencyConverter.toValueCurrency(prices, mapOf(day1 to 10.0))

        assertTrue("Hellre en lucka än ett gissat värde (POR-3)", result.isEmpty())
    }

    @Test
    fun `blandade kronor och usd-rader hanteras rad for rad`() {
        val prices = listOf(
            FundPrice(fundId = "F", epochDay = day1, nav = 100.0, currency = "SEK"),
            FundPrice(fundId = "F", epochDay = day2, nav = 10.0, currency = "USD"),
        )

        val result = CurrencyConverter.toValueCurrency(prices, mapOf(day2 to 9.5))

        assertEquals(2, result.size)
        assertEquals(100.0, result.first { it.epochDay == day1 }.nav, 1e-9)
        assertEquals(95.0, result.first { it.epochDay == day2 }.nav, 1e-9)
    }

    @Test
    fun `valutajamforelse ar skiftlagesokanslig`() {
        val prices = listOf(FundPrice(fundId = "F", epochDay = day1, nav = 100.0, currency = "sek"))

        val result = CurrencyConverter.toValueCurrency(prices, emptyMap())

        assertEquals(prices, result)
    }
}
