package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

class FifoResultCalcTest {

    private val fondA = Fund(fundId = "SHB0000442", name = "Fond A")
    private val fondB = Fund(fundId = "SHB0000627", name = "Fond B")

    private fun kop(epochDay: Long, shares: Double, price: Double, id: Long = 0) =
        Transaction(id = id, fundId = fondA.fundId, type = TransactionType.KOP, epochDay = epochDay, shares = shares, pricePerShare = price)

    private fun salj(epochDay: Long, shares: Double, price: Double, id: Long = 0) =
        Transaction(id = id, fundId = fondA.fundId, type = TransactionType.SALJ, epochDay = epochDay, shares = shares, pricePerShare = price)

    @Test
    fun `enkel lott saljs helt`() {
        val result = FifoResultCalc.run(listOf(kop(1, 10.0, 100.0), salj(2, 10.0, 130.0)))

        assertEquals(0.0, result.remainingShares, 1e-9)
        assertEquals(0.0, result.remainingCostBasis, 1e-9)
        assertEquals(1000.0, result.costBasisOfSold, 1e-9)
        assertEquals(1300.0, result.proceeds, 1e-9)
        assertEquals(300.0, result.realizedGainLoss ?: -1.0, 1e-9)
    }

    @Test
    fun `flera lotter konsumeras aldst forst`() {
        val result = FifoResultCalc.run(
            listOf(
                kop(1, 5.0, 100.0),
                kop(2, 5.0, 200.0),
                // Säljer 6 — hela den äldsta lotten (5 à 100) plus 1 av den nya (à 200).
                salj(3, 6.0, 150.0),
            ),
        )

        assertEquals(4.0, result.remainingShares, 1e-9)
        assertEquals(800.0, result.remainingCostBasis, 1e-9) // 4 kvarvarande à 200
        assertEquals(500.0 + 200.0, result.costBasisOfSold, 1e-9) // 5 à 100 + 1 à 200
        assertEquals(900.0, result.proceeds, 1e-9)
        assertEquals(900.0 - 700.0, result.realizedGainLoss ?: -1.0, 1e-9)
    }

    @Test
    fun `delvis salj av en lott lamnar resten kvar`() {
        val result = FifoResultCalc.run(listOf(kop(1, 10.0, 100.0), salj(2, 3.0, 120.0)))

        assertEquals(7.0, result.remainingShares, 1e-9)
        assertEquals(700.0, result.remainingCostBasis, 1e-9)
        assertEquals(300.0, result.costBasisOfSold, 1e-9)
    }

    @Test
    fun `salj utan tillrackliga kop flaggas som okant resultat`() {
        val result = FifoResultCalc.run(listOf(salj(1, 5.0, 100.0)))

        assertTrue(result.hasUnmatchedSells)
        assertNull(result.realizedGainLoss)
    }

    @Test
    fun `salj utan tillrackliga kop flaggar aven om en del kunde matchas`() {
        val result = FifoResultCalc.run(listOf(kop(1, 2.0, 100.0), salj(2, 5.0, 100.0)))

        assertTrue(result.hasUnmatchedSells)
        assertNull(result.realizedGainLoss)
        assertEquals(0.0, result.remainingShares, 1e-9)
    }

    @Test
    fun `computeSoldFundResults tar bara med fonder som haft salj`() {
        val txs = listOf(
            kop(1, 10.0, 100.0),
            salj(2, 4.0, 120.0),
            Transaction(fundId = fondB.fundId, type = TransactionType.KOP, epochDay = 1, shares = 5.0, pricePerShare = 50.0),
        )

        val results = FifoResultCalc.computeSoldFundResults(listOf(fondA, fondB), txs)

        assertEquals(1, results.size)
        assertEquals(fondA, results.first().fund)
        assertEquals(4.0, results.first().sharesSold, 1e-9)
        assertEquals(480.0, results.first().proceeds, 1e-9)
        assertEquals(400.0, results.first().costBasis ?: -1.0, 1e-9)
        assertEquals(80.0, results.first().realizedGainLoss ?: -1.0, 1e-9)
    }

    @Test
    fun `computeSoldFundResults visar okant resultat vid otillracklig kophistorik`() {
        val txs = listOf(salj(1, 5.0, 100.0))

        val results = FifoResultCalc.computeSoldFundResults(listOf(fondA), txs)

        assertEquals(1, results.size)
        assertNull(results.first().costBasis)
        assertNull(results.first().realizedGainLoss)
    }

    @Test
    fun `computeSoldFundResults sorterar transaktioner kronologiskt oavsett inkommande ordning`() {
        // Transaktionerna kommer i omvänd ordning (t.ex. DAO-sortering DESC) — motorn måste
        // sortera dem själv, annars matchas fel köp mot fel sälj.
        val txs = listOf(
            salj(3, 6.0, 150.0, id = 3),
            kop(2, 5.0, 200.0, id = 2),
            kop(1, 5.0, 100.0, id = 1),
        )

        val results = FifoResultCalc.computeSoldFundResults(listOf(fondA), txs)

        assertEquals(700.0, results.first().costBasis ?: -1.0, 1e-9)
    }
}
