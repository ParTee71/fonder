package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

private fun kop(fundId: String, day: Long, shares: Double, price: Double, id: Long = 0) =
    Transaction(id = id, fundId = fundId, type = TransactionType.KOP, epochDay = day, shares = shares, pricePerShare = price)

private fun salj(fundId: String, day: Long, shares: Double, price: Double, fee: Double = 0.0, id: Long = 0) =
    Transaction(id = id, fundId = fundId, type = TransactionType.SALJ, epochDay = day, shares = shares, pricePerShare = price, fee = fee)

class RealizedGainCalculatorTest {

    @Test
    fun `enkel forsaljning av en enda lott ger korrekt realiserat resultat`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 10.0, price = 100.0, id = 1),
            salj("F1", day = 200, shares = 10.0, price = 120.0, id = 2),
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
    fun `delforsaljning konsumerar aldsta koplotten forst (FIFO)`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 6.0, price = 100.0, id = 1),
            kop("F1", day = 150, shares = 8.0, price = 110.0, id = 2),
            salj("F1", day = 200, shares = 10.0, price = 130.0, id = 3),
        )

        val sales = RealizedGainCalculator.compute(transactions)

        assertEquals(1, sales.size)
        val sale = sales.first()
        // 6 andelar från den äldre lotten (à 100) + 4 från den yngre (à 110) = 600 + 440.
        assertEquals(1040.0, sale.costBasis, 1e-9)
        assertEquals(1300.0, sale.proceeds, 1e-9)
        assertEquals(260.0, sale.realizedGain, 1e-9)
        assertEquals(0.0, sale.uncoveredShares, 1e-9)
    }

    @Test
    fun `flera delforsaljningar over tid konsumerar resterande andelar av samma lott`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 10.0, price = 100.0, id = 1),
            salj("F1", day = 200, shares = 4.0, price = 120.0, id = 2),
            salj("F1", day = 300, shares = 6.0, price = 130.0, id = 3),
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
            salj("F1", day = 200, shares = 10.0, price = 150.0, fee = 50.0, id = 2),
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
            salj("F1", day = 100, shares = 5.0, price = 100.0, id = 1),
        )

        val sale = RealizedGainCalculator.compute(transactions).first()

        assertEquals(0.0, sale.costBasis, 1e-9)
        assertEquals(5.0, sale.uncoveredShares, 1e-9)
        assertTrue(sale.uncoveredShares > 0.0)
    }

    @Test
    fun `olika fonder paverkar inte varandras lotter`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 10.0, price = 100.0, id = 1),
            kop("F2", day = 100, shares = 5.0, price = 50.0, id = 2),
            salj("F2", day = 200, shares = 5.0, price = 60.0, id = 3),
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
            salj("F1", day = 300, shares = 5.0, price = 120.0, id = 3),
        )

        val sales = RealizedGainCalculator.compute(transactions)

        assertEquals(listOf(300L, 200L), sales.map { it.epochDay })
    }

    @Test
    fun `remainingPositions ger FIFO-anskaffningsvarde for kvarvarande andelar`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 5.0, price = 100.0, id = 1),
            kop("F1", day = 150, shares = 5.0, price = 200.0, id = 2),
            salj("F1", day = 200, shares = 6.0, price = 150.0, id = 3),
        )

        val positions = RealizedGainCalculator.remainingPositions(transactions)

        assertEquals(4.0, positions.getValue("F1").shares, 1e-9)
        // 4 kvarvarande andelar ur den yngre lotten à 200 kr.
        assertEquals(800.0, positions.getValue("F1").costBasis, 1e-9)
    }

    @Test
    fun `remainingPositions utelamnar helt avsalda fonder`() {
        val transactions = listOf(
            kop("F1", day = 100, shares = 10.0, price = 100.0, id = 1),
            salj("F1", day = 200, shares = 10.0, price = 120.0, id = 2),
        )

        assertFalse(RealizedGainCalculator.remainingPositions(transactions).containsKey("F1"))
    }

    @Test
    fun `remainingPositions paverkas inte av avgift pa sald andel`() {
        // Avgiften hör till det realiserade resultatet för säljtransaktionen, inte de
        // kvarvarande andelarnas anskaffningsvärde.
        val transactions = listOf(
            kop("F1", day = 100, shares = 10.0, price = 100.0, id = 1),
            salj("F1", day = 200, shares = 4.0, price = 120.0, fee = 20.0, id = 2),
        )

        val position = RealizedGainCalculator.remainingPositions(transactions).getValue("F1")
        assertEquals(6.0, position.shares, 1e-9)
        assertEquals(600.0, position.costBasis, 1e-9)
    }
}
