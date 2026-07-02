package se.partee71.fonder.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.PortfolioCalc

class PortfolioCalcTest {

    private val fondA = Fund(fundId = "SHB0000442", name = "Fond A")
    private val fondB = Fund(fundId = "SHB0000627", name = "Fond B")

    @Test
    fun `net shares och invested subtraherar salj fran kop`() {
        val txs = listOf(
            Transaction(fundId = fondA.fundId, type = TransactionType.KOP, epochDay = 1, shares = 10.0, pricePerShare = 100.0),
            Transaction(fundId = fondA.fundId, type = TransactionType.SALJ, epochDay = 2, shares = 4.0, pricePerShare = 120.0),
        )

        val holdings = PortfolioCalc.computeHoldings(listOf(fondA), txs)

        assertEquals(1, holdings.size)
        assertEquals(6.0, holdings.first().netShares, 1e-9)
        assertEquals(1000.0 - 480.0, holdings.first().netInvested, 1e-9)
    }

    @Test
    fun `holdings sorteras pa namn och totalen summeras`() {
        val txs = listOf(
            Transaction(fundId = fondB.fundId, type = TransactionType.KOP, epochDay = 1, shares = 1.0, pricePerShare = 200.0),
            Transaction(fundId = fondA.fundId, type = TransactionType.KOP, epochDay = 1, shares = 1.0, pricePerShare = 300.0),
        )

        val holdings = PortfolioCalc.computeHoldings(listOf(fondA, fondB), txs)

        assertEquals(listOf("Fond A", "Fond B"), holdings.map { it.fund.name })
        assertEquals(500.0, PortfolioCalc.totalInvested(holdings), 1e-9)
    }

    @Test
    fun `transaktion utan matchande fond ignoreras`() {
        val txs = listOf(
            Transaction(fundId = "OKAND", type = TransactionType.KOP, epochDay = 1, shares = 1.0, pricePerShare = 10.0),
        )
        assertEquals(0, PortfolioCalc.computeHoldings(listOf(fondA), txs).size)
    }
}
