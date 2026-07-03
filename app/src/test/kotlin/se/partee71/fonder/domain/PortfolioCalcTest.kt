package se.partee71.fonder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
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

    @Test
    fun `withCurrentValue berakar varde for innehav med kand kurs`() {
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0)
        val prices = mapOf(fondA.fundId to FundPrice(fundId = fondA.fundId, epochDay = 5, nav = 120.0))

        val result = PortfolioCalc.withCurrentValue(listOf(holding), prices)

        assertEquals(1200.0, result.first().currentValue ?: -1.0, 1e-9)
        assertEquals(200.0, result.first().gainLoss ?: -1.0, 1e-9)
    }

    @Test
    fun `withCurrentValue lamnar currentValue null utan kand kurs`() {
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0)

        val result = PortfolioCalc.withCurrentValue(listOf(holding), emptyMap())

        assertNull(result.first().currentValue)
        assertNull(result.first().gainLoss)
    }

    @Test
    fun `totalValue och totalGainLoss ignorerar innehav utan kurs`() {
        val medKurs = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1200.0)
        val utanKurs = Holding(fund = fondB, netShares = 5.0, netInvested = 500.0)

        val holdings = listOf(medKurs, utanKurs)

        assertEquals(1200.0, PortfolioCalc.totalValue(holdings), 1e-9)
        assertEquals(200.0, PortfolioCalc.totalGainLoss(holdings), 1e-9)
        assertEquals(1500.0, PortfolioCalc.totalInvested(holdings), 1e-9)
    }

    @Test
    fun `totalGainLossFraction baseras endast pa innehav med kand kurs`() {
        val medKurs = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1100.0)
        val utanKurs = Holding(fund = fondB, netShares = 5.0, netInvested = 500.0)

        val fraction = PortfolioCalc.totalGainLossFraction(listOf(medKurs, utanKurs))

        assertEquals(0.1, fraction ?: -1.0, 1e-9)
    }

    @Test
    fun `totalGainLossFraction ar null utan nagot innehav med kand kurs`() {
        val utanKurs = Holding(fund = fondA, netShares = 5.0, netInvested = 500.0)

        assertNull(PortfolioCalc.totalGainLossFraction(listOf(utanKurs)))
    }
}
