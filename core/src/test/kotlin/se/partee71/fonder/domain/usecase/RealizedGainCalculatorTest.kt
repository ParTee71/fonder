package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

private fun kop(fundId: String, day: Long, shares: Double, price: Double, fee: Double = 0.0, id: Long = 0) = Transaction(id = id, fundId = fundId, type = TransactionType.KOP, epochDay = day, shares = shares, pricePerShare = price, fee = fee)

private fun salj(fundId: String, day: Long, shares: Double, price: Double, fee: Double = 0.0, id: Long = 0) = Transaction(id = id, fundId = fundId, type = TransactionType.SALJ, epochDay = day, shares = shares, pricePerShare = price, fee = fee)

/**
 * [RealizedGainCalculator] — genomsnittsmetoden (Skatteverket, issue #192; tidigare FIFO). Golden-
 * värdena kommer ur `python tools/fondmatte/fondmatte.py golden <fixtur>` (`realized_average_cost`,
 * `remaining_cost_average`), inte ur koden som testas.
 */
class RealizedGainCalculatorTest {

    @Test
    fun `enkel forsaljning av ett enda kop ger korrekt realiserat resultat`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 10.0, price = 100.0, id = 1),
            salj("F1", day = 200, shares = 10.0, price = 120.0, id = 2)
        )

        val sales = RealizedGainCalculator.compute(transactions)

        assertEquals(1, sales.size)
        val sale = sales.first()
        assertEquals(1200.0, sale.proceeds, 1e-9)
        assertEquals(1000.0, sale.costBasis, 1e-9)
        assertEquals(0.0, sale.uncoveredShares, 1e-9)
        assertEquals(200.0, sale.realizedGain, 1e-9)
    }

    @Test
    fun `delforsaljning raknas pa genomsnittligt anskaffningsvarde, inte aldsta kop`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 6.0, price = 100.0, id = 1),
            kop("F1", day = 150, shares = 8.0, price = 110.0, id = 2),
            salj("F1", day = 200, shares = 10.0, price = 130.0, id = 3)
        )

        val sales = RealizedGainCalculator.compute(transactions)

        assertEquals(1, sales.size)
        val sale = sales.first()
        // Snitt (600 + 880) / 14 = 105,714… kr per andel; 10 sålda → 1 057,14 kr (FIFO hade gett 1 040).
        assertEquals(10.0 * 1480.0 / 14.0, sale.costBasis, 1e-9)
        assertEquals(1300.0, sale.proceeds, 1e-9)
        assertEquals(1300.0 - 10.0 * 1480.0 / 14.0, sale.realizedGain, 1e-9)
        assertEquals(0.0, sale.uncoveredShares, 1e-9)
    }

    @Test
    fun `kopavgiften ingar i anskaffningsvardet och saljavgiften dras fran likviden (golden exempel)`() {
        // tools/fondmatte/exempel.json, "Påhittad Sverige Aktiv": köp 120 à 250 + 39 kr, sälj 70,4461,
        // köp 45,0402 à 222,0237 + 39 kr, sälj 62,2464. Förväntat: realized_average_cost.
        val transactions = listOf(
            kop("S", day = 19968, shares = 120.0, price = 250.0, fee = 39.0, id = 1),
            salj("S", day = 20185, shares = 70.4461, price = 212.9286, fee = 39.0, id = 2),
            kop("S", day = 20241, shares = 45.0402, price = 222.0237, fee = 39.0, id = 3),
            salj("S", day = 20332, shares = 62.2464, price = 192.7823, fee = 39.0, id = 4)
        )

        val byId = RealizedGainCalculator.compute(transactions).associateBy { it.transactionId }

        assertEquals(17634.419982500003, byId.getValue(2).costBasis, 1e-9)
        assertEquals(-2673.430534040004, byId.getValue(2).realizedGain, 1e-9)
        assertEquals(14768.696483857948, byId.getValue(4).costBasis, 1e-9)
        assertEquals(-2807.6923251379485, byId.getValue(4).realizedGain, 1e-9)
        assertEquals(0.0, byId.getValue(4).uncoveredShares, 1e-9)
    }

    @Test
    fun `ett salj andrar inte snittet per andel for de andelar som ar kvar`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 10.0, price = 100.0, fee = 50.0, id = 1),
            salj("F1", day = 200, shares = 4.0, price = 300.0, id = 2)
        )

        val position = RealizedGainCalculator.remainingPositions(transactions).getValue("F1")

        // Snitt 1 050 / 10 = 105 kr per andel före och efter säljet: 6 kvar à 105.
        assertEquals(6.0, position.shares, 1e-9)
        assertEquals(630.0, position.costBasis, 1e-9)
    }

    @Test
    fun `flera delforsaljningar over tid konsumerar resten av samma position`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 10.0, price = 100.0, id = 1),
            salj("F1", day = 200, shares = 4.0, price = 120.0, id = 2),
            salj("F1", day = 300, shares = 6.0, price = 130.0, id = 3)
        )

        val sales = RealizedGainCalculator.compute(transactions)

        assertEquals(2, sales.size)
        val bySecondSaleId = sales.associateBy { it.transactionId }
        assertEquals(400.0, bySecondSaleId.getValue(2).costBasis, 1e-9)
        assertEquals(600.0, bySecondSaleId.getValue(3).costBasis, 1e-9)
    }

    @Test
    fun `avgift minskar det realiserade resultatet`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 10.0, price = 100.0, id = 1),
            salj("F1", day = 200, shares = 10.0, price = 150.0, fee = 50.0, id = 2)
        )

        val sale = RealizedGainCalculator.compute(transactions).first()

        assertEquals(1500.0, sale.proceeds, 1e-9)
        assertEquals(1000.0, sale.costBasis, 1e-9)
        assertEquals(50.0, sale.fee, 1e-9)
        assertEquals(450.0, sale.realizedGain, 1e-9)
    }

    @Test
    fun `forsaljning utan matchande kop flaggas som otackt`() {
        val transactions = listOf(
            salj("F1", day = 100, shares = 5.0, price = 100.0, id = 1)
        )

        val sale = RealizedGainCalculator.compute(transactions).first()

        assertEquals(0.0, sale.costBasis, 1e-9)
        assertEquals(5.0, sale.uncoveredShares, 1e-9)
        assertTrue(sale.uncoveredShares > 0.0)
    }

    @Test
    fun `olika fonder paverkar inte varandras snitt`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 10.0, price = 100.0, id = 1),
            kop("F2", day = 100, shares = 5.0, price = 50.0, id = 2),
            salj("F2", day = 200, shares = 5.0, price = 60.0, id = 3)
        )

        val sales = RealizedGainCalculator.compute(transactions)

        assertEquals(1, sales.size)
        assertEquals("F2", sales.first().fundId)
        assertEquals(250.0, sales.first().costBasis, 1e-9)
    }

    @Test
    fun `resultat sorteras senaste forst`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 20.0, price = 100.0, id = 1),
            salj("F1", day = 200, shares = 5.0, price = 110.0, id = 2),
            salj("F1", day = 300, shares = 5.0, price = 120.0, id = 3)
        )

        val sales = RealizedGainCalculator.compute(transactions)

        assertEquals(listOf(300L, 200L), sales.map { it.epochDay })
    }

    @Test
    fun `remainingPositions ger genomsnittligt anskaffningsvarde for kvarvarande andelar`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 5.0, price = 100.0, id = 1),
            kop("F1", day = 150, shares = 5.0, price = 200.0, id = 2),
            salj("F1", day = 200, shares = 6.0, price = 150.0, id = 3)
        )

        val positions = RealizedGainCalculator.remainingPositions(transactions)

        assertEquals(4.0, positions.getValue("F1").shares, 1e-9)
        // Snitt 1 500 / 10 = 150 kr per andel: 4 kvar à 150 = 600 kr (FIFO hade gett 800).
        assertEquals(600.0, positions.getValue("F1").costBasis, 1e-9)
        // Ett delsälj flyttar inte positionens startdatum — det finns inga lotter att konsumera (POR-6, issue #192).
        assertEquals(100L, positions.getValue("F1").firstPurchaseEpochDay)
    }

    @Test
    fun `summan av saljens anskaffningsvarden ar exakt summan av kopen nar allt salts`() {
        val transactions = listOf(
            kop("F1", day = 1, shares = 3.0, price = 101.37, fee = 39.0, id = 1),
            kop("F1", day = 2, shares = 7.1, price = 97.11, fee = 39.0, id = 2),
            salj("F1", day = 3, shares = 1.3, price = 110.0, id = 3),
            salj("F1", day = 4, shares = 2.9, price = 105.0, id = 4),
            salj("F1", day = 5, shares = 5.9, price = 99.0, id = 5)
        )

        val sales = RealizedGainCalculator.compute(transactions)

        assertEquals(3.0 * 101.37 + 39.0 + 7.1 * 97.11 + 39.0, sales.sumOf { it.costBasis }, 0.0)
        assertFalse(RealizedGainCalculator.remainingPositions(transactions).containsKey("F1"))
    }

    @Test
    fun `en rad utan andelar varken startar position eller bar kostnad`() {
        val transactions = listOf(
            kop("F1", day = 10, shares = 0.0, price = 100.0, fee = 39.0, id = 1),
            kop("F1", day = 20, shares = 10.0, price = 100.0, id = 2)
        )

        val position = RealizedGainCalculator.remainingPositions(transactions).getValue("F1")

        assertEquals(1000.0, position.costBasis, 1e-9)
        assertEquals(20L, position.firstPurchaseEpochDay)
    }

    @Test
    fun `ny position efter att allt salts raknas fran det nya kopet`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 5.0, price = 100.0, id = 1),
            salj("F1", day = 200, shares = 5.0, price = 150.0, id = 2),
            kop("F1", day = 300, shares = 2.0, price = 120.0, id = 3)
        )

        val position = RealizedGainCalculator.remainingPositions(transactions).getValue("F1")

        assertEquals(2.0, position.shares, 1e-9)
        assertEquals(240.0, position.costBasis, 1e-9) // inget av den gamla positionens värde följer med
        assertEquals(300L, position.firstPurchaseEpochDay)
    }

    @Test
    fun `remainingPositions utelamnar helt avsalda fonder`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 10.0, price = 100.0, id = 1),
            salj("F1", day = 200, shares = 10.0, price = 120.0, id = 2)
        )

        assertFalse(RealizedGainCalculator.remainingPositions(transactions).containsKey("F1"))
    }

    @Test
    fun `remainingPositions paverkas inte av avgift pa sald andel`() {
        // Avgiften hör till det realiserade resultatet för säljtransaktionen, inte de
        // kvarvarande andelarnas anskaffningsvärde.
        val transactions = listOf(
            kop("F1", day = 100, shares = 10.0, price = 100.0, id = 1),
            salj("F1", day = 200, shares = 4.0, price = 120.0, fee = 20.0, id = 2)
        )

        val position = RealizedGainCalculator.remainingPositions(transactions).getValue("F1")
        assertEquals(6.0, position.shares, 1e-9)
        assertEquals(600.0, position.costBasis, 1e-9)
    }

    @Test
    fun `total summerar resultat och anskaffningsvarde over flera fonder (SLD-3)`() {
        val sales = RealizedGainCalculator.compute(
            listOf(
                kop("F1", day = 100, shares = 10.0, price = 100.0, id = 1),
                salj("F1", day = 200, shares = 10.0, price = 150.0, id = 2),
                kop("F2", day = 100, shares = 5.0, price = 200.0, id = 3),
                salj("F2", day = 200, shares = 5.0, price = 190.0, id = 4)
            )
        )

        val total = RealizedGainCalculator.total(sales)

        // 500 − 50 = 450 på 2000 i anskaffningsvärde.
        assertEquals(450.0, total.realizedGain, 1e-9)
        assertEquals(450.0 / 2000.0, total.realizedGainFraction!!, 1e-9)
        assertEquals(2, total.saleCount)
        assertFalse(total.uncertain)
    }

    @Test
    fun `total utan anskaffningsvarde saknar andel och ar osaker nar ett salj saknar kop (SLD-2)`() {
        val total = RealizedGainCalculator.total(
            RealizedGainCalculator.compute(listOf(salj("F1", day = 200, shares = 5.0, price = 150.0, id = 1)))
        )

        assertNull(total.realizedGainFraction)
        assertTrue(total.uncertain)
    }

    @Test
    fun `totalForYear tar bara med salj gjorda under aret (TRX-4)`() {
        val lastYear = LocalDate.of(2025, 12, 31).toEpochDay()
        val thisYear = LocalDate.of(2026, 1, 1).toEpochDay()
        val sales = RealizedGainCalculator.compute(
            listOf(
                kop("F1", day = lastYear - 100, shares = 20.0, price = 100.0, id = 1),
                salj("F1", day = lastYear, shares = 10.0, price = 110.0, id = 2),
                salj("F1", day = thisYear, shares = 10.0, price = 130.0, id = 3)
            )
        )

        val total = RealizedGainCalculator.totalForYear(sales, year = 2026)

        assertEquals(300.0, total.realizedGain, 1e-9)
        assertEquals(1, total.saleCount)
        assertEquals(0, RealizedGainCalculator.totalForYear(sales, year = 2024).saleCount)
    }
}
