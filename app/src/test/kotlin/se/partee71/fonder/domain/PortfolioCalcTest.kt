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
import se.partee71.fonder.domain.usecase.RealizedGainCalculator

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
        // netInvested är FIFO-kvarvarande anskaffningsvärde (6 kvarvarande andelar à
        // ursprungliga 100 kr), inte kassaflödet (1000 − 480) — issue #10.
        assertEquals(600.0, holdings.first().netInvested, 1e-9)
    }

    @Test
    fun `netInvested anvander FIFO aven med flera inkopspriser`() {
        val txs = listOf(
            Transaction(fundId = fondA.fundId, type = TransactionType.KOP, epochDay = 1, shares = 5.0, pricePerShare = 100.0),
            Transaction(fundId = fondA.fundId, type = TransactionType.KOP, epochDay = 2, shares = 5.0, pricePerShare = 200.0),
            // Säljer 6 andelar — äldsta lotten (5 à 100) konsumeras helt, plus 1 av den nya (à 200).
            Transaction(fundId = fondA.fundId, type = TransactionType.SALJ, epochDay = 3, shares = 6.0, pricePerShare = 150.0),
        )

        val holdings = PortfolioCalc.computeHoldings(listOf(fondA), txs)

        assertEquals(4.0, holdings.first().netShares, 1e-9)
        // Kvarvarande 4 andelar ur den andra lotten à 200 kr = 800 kr.
        assertEquals(800.0, holdings.first().netInvested, 1e-9)
    }

    @Test
    fun `avgift pa en delforsaljning paverkar inte kvarvarande nettoinvesterat`() {
        // Avgiften hör till det realiserade resultatet för själva säljtransaktionen
        // (RealizedGainCalculator) — den ändrar inte anskaffningsvärdet för andelarna
        // som fortfarande innehas.
        val txs = listOf(
            Transaction(id = 1, fundId = fondA.fundId, type = TransactionType.KOP, epochDay = 1, shares = 10.0, pricePerShare = 100.0),
            Transaction(id = 2, fundId = fondA.fundId, type = TransactionType.SALJ, epochDay = 2, shares = 4.0, pricePerShare = 120.0, fee = 20.0),
        )

        val holding = PortfolioCalc.computeHoldings(listOf(fondA), txs).first()
        assertEquals(600.0, holding.netInvested, 1e-9)

        val sale = RealizedGainCalculator.compute(txs).first()
        assertEquals(400.0, sale.costBasis, 1e-9)
        assertEquals(60.0, sale.realizedGain, 1e-9) // 480 sålt - 20 avgift - 400 anskaffning.
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
    fun `helt avsald fond (netto noll andelar) utelamnas ur portfoljen`() {
        val txs = listOf(
            Transaction(fundId = fondA.fundId, type = TransactionType.KOP, epochDay = 1, shares = 10.0, pricePerShare = 100.0),
            Transaction(fundId = fondA.fundId, type = TransactionType.SALJ, epochDay = 2, shares = 10.0, pricePerShare = 120.0, fee = 20.0),
        )
        // Fees dras av från det realiserade resultatet (RealizedGainCalculator), inte från
        // portföljens kvarvarande anskaffningsvärde — här är fonden helt avsåld oavsett.
        assertEquals(0, PortfolioCalc.computeHoldings(listOf(fondA), txs).size)
    }

    @Test
    fun `firstPurchaseEpochDay ar den tidigaste transaktionens epochDay, oavsett ordning (POR-6)`() {
        val txs = listOf(
            Transaction(fundId = fondA.fundId, type = TransactionType.KOP, epochDay = 50, shares = 1.0, pricePerShare = 100.0),
            Transaction(fundId = fondA.fundId, type = TransactionType.KOP, epochDay = 10, shares = 1.0, pricePerShare = 90.0),
            Transaction(fundId = fondA.fundId, type = TransactionType.SALJ, epochDay = 60, shares = 1.0, pricePerShare = 110.0),
        )

        val holding = PortfolioCalc.computeHoldings(listOf(fondA), txs).first()

        assertEquals(10L, holding.firstPurchaseEpochDay)
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

    @Test
    fun `withCurrentValue satter navEpochDay till prisets datum (POR-7)`() {
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0)
        val prices = mapOf(fondA.fundId to FundPrice(fundId = fondA.fundId, epochDay = 42, nav = 120.0))

        val result = PortfolioCalc.withCurrentValue(listOf(holding), prices)

        assertEquals(42L, result.first().navEpochDay)
    }

    @Test
    fun `withCurrentValue lamnar navEpochDay null utan kand kurs`() {
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0)

        val result = PortfolioCalc.withCurrentValue(listOf(holding), emptyMap())

        assertNull(result.first().navEpochDay)
    }

    @Test
    fun `oldestKnownNavEpochDay ar den aldsta bland innehav med kant varde (POR-7)`() {
        val nyare = Holding(fund = fondA, netShares = 1.0, netInvested = 100.0, currentValue = 110.0, navEpochDay = 20)
        val aldre = Holding(fund = fondB, netShares = 1.0, netInvested = 100.0, currentValue = 105.0, navEpochDay = 10)

        assertEquals(10L, PortfolioCalc.oldestKnownNavEpochDay(listOf(nyare, aldre)))
    }

    @Test
    fun `oldestKnownNavEpochDay ignorerar innehav utan kant varde`() {
        val medVarde = Holding(fund = fondA, netShares = 1.0, netInvested = 100.0, currentValue = 110.0, navEpochDay = 20)
        // navEpochDay null trots currentValue-lika-null — ska inte kunna hända i praktiken, men
        // det är själva currentValue == null som avgör om innehavet räknas med (inte navEpochDay).
        val utanVarde = Holding(fund = fondB, netShares = 1.0, netInvested = 100.0, navEpochDay = 5)

        assertEquals(20L, PortfolioCalc.oldestKnownNavEpochDay(listOf(medVarde, utanVarde)))
    }

    @Test
    fun `oldestKnownNavEpochDay ar null utan nagot innehav med kant varde`() {
        val utanVarde = Holding(fund = fondA, netShares = 5.0, netInvested = 500.0)

        assertNull(PortfolioCalc.oldestKnownNavEpochDay(listOf(utanVarde)))
    }
}
