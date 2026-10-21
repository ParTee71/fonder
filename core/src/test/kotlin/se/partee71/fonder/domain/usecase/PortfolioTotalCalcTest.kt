package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/**
 * [PortfolioTotalCalc] — totalresultat och pengaviktad avkastning (issue #191). De förväntade
 * värdena kommer ur `python tools/fondmatte/fondmatte.py golden <fixtur>` (skill `fondmatematik`),
 * inte ur koden som testas: `total_gain`, `total_gain_fraction`, `net_deposits` och `xirr`
 * (pyxirr, ACT/365F). Fixturerna är påhittade portföljer.
 */
class PortfolioTotalCalcTest {

    private val global = Fund(fundId = "G", name = "Påhittad Global Index")
    private val sverige = Fund(fundId = "S", name = "Påhittad Sverige Aktiv")

    private fun kop(fund: Fund, day: Long, shares: Double, price: Double, fee: Double = 0.0) = Transaction(fundId = fund.fundId, type = TransactionType.KOP, epochDay = day, shares = shares, pricePerShare = price, fee = fee)

    private fun salj(fund: Fund, day: Long, shares: Double, price: Double, fee: Double = 0.0) = Transaction(fundId = fund.fundId, type = TransactionType.SALJ, epochDay = day, shares = shares, pricePerShare = price, fee = fee)

    /** Innehaven värderade till fixturens slut-NAV, som [PortfolioCalc.withCurrentValue] gör i appen. */
    private fun holdings(funds: List<Fund>, transactions: List<Transaction>, endDay: Long, navByFundId: Map<String, Double>) = PortfolioCalc.withCurrentValue(
        PortfolioCalc.computeHoldings(funds, transactions),
        navByFundId.mapValues { (fundId, nav) -> FundPrice(fundId = fundId, epochDay = endDay, nav = nav) }
    )

    /** tools/fondmatte/exempel.json: två fonder, fyra köp, två sälj med förlust, avgifter. Värderingsdag 2025-11-28. */
    private val exempelTransactions = listOf(
        kop(global, 19968, 500.0, 100.0),
        kop(sverige, 19968, 120.0, 250.0, fee = 39.0),
        kop(global, 20103, 189.1714, 105.7242),
        salj(sverige, 20185, 70.4461, 212.9286, fee = 39.0),
        kop(sverige, 20241, 45.0402, 222.0237, fee = 39.0),
        salj(sverige, 20332, 62.2464, 192.7823, fee = 39.0)
    )
    private val exempelEndDay = 20420L
    private val exempelNav = mapOf("G" to 121.4917, "S" to 207.1641)

    @Test
    fun `totalresultat ar varde plus uttag minus insattningar med avgifter (golden exempel)`() {
        val result = PortfolioTotalCalc.compute(exempelTransactions, holdings(listOf(global, sverige), exempelTransactions, exempelEndDay, exempelNav), exempelEndDay)

        // fondmatte: net_deposits, total_gain, total_gain_fraction
        assertEquals(83155.99317344, result.netDepositsKr, 1e-9)
        assertEquals(7273.893961509981, result.totalGainKr!!, 1e-9)
        assertEquals(0.08747287698601212, result.totalGainFraction!!, 1e-9)
        assertFalse(result.partial)
    }

    @Test
    fun `totalresultatet skiljer sig fran orealiserat pa kvarvarande lotter`() {
        // Dagens totalkort (PortfolioCalc) ser bara de kvarvarande andelarna: +13 248 kr. De två
        // realiserade förlusterna och avgifterna gör det verkliga resultatet 5 974 kr lägre.
        val enriched = holdings(listOf(global, sverige), exempelTransactions, exempelEndDay, exempelNav)

        val unrealized = PortfolioCalc.totalGainLoss(enriched)
        val total = PortfolioTotalCalc.compute(exempelTransactions, enriched, exempelEndDay).totalGainKr!!

        assertEquals(13247.93616657998, unrealized, 1e-9) // fondmatte: unrealized_fifo_as_app
        assertTrue(total < unrealized)
    }

    @Test
    fun `pengaviktad avkastning ar XIRR med ACT 365F (golden exempel)`() {
        val result = PortfolioTotalCalc.compute(exempelTransactions, holdings(listOf(global, sverige), exempelTransactions, exempelEndDay, exempelNav), exempelEndDay)

        assertEquals(0.06631311304385464, result.moneyWeightedAnnualReturn!!, 1e-9) // fondmatte: xirr
    }

    @Test
    fun `helt avsald och aterkopt fond raknas med sina uttag (golden perioder)`() {
        // tools/fondmatte/perioder.json: B köps 2022, säljs helt 2024 med vinst och köps igen 2025.
        val a = Fund(fundId = "A", name = "A")
        val b = Fund(fundId = "B", name = "B")
        val transactions = listOf(
            kop(a, 19024, 300.0, 100.7954),
            kop(a, 20668, 200.0, 140.1456),
            kop(b, 19052, 1000.0, 46.0223, fee = 39.0),
            salj(b, 19877, 1000.0, 60.6364, fee = 39.0),
            kop(b, 20332, 400.0, 57.8357, fee = 39.0)
        )
        val endDay = 20742L

        val result = PortfolioTotalCalc.compute(transactions, holdings(listOf(a, b), transactions, endDay, mapOf("A" to 161.516, "B" to 57.4812)), endDay)

        assertEquals(66904.92, result.netDepositsKr, 1e-9)
        assertEquals(36845.56, result.totalGainKr!!, 1e-9)
        assertEquals(0.550715253825877, result.totalGainFraction!!, 1e-9)
        assertEquals(0.11655946694681728, result.moneyWeightedAnnualReturn!!, 1e-9)
    }

    @Test
    fun `bara kop utan salj ger samma resultat som orealiserat`() {
        val transactions = listOf(kop(global, 19968, 500.0, 100.0), kop(global, 20103, 189.1714, 105.7242))
        val enriched = holdings(listOf(global), transactions, exempelEndDay, exempelNav)

        val result = PortfolioTotalCalc.compute(transactions, enriched, exempelEndDay)

        assertEquals(PortfolioCalc.totalGainLoss(enriched), result.totalGainKr!!, 1e-9)
        assertEquals(PortfolioCalc.totalGainLossFraction(enriched)!!, result.totalGainFraction!!, 1e-9)
    }

    @Test
    fun `innehav utan kurs ger partial och inga tal`() {
        val enriched = holdings(listOf(global, sverige), exempelTransactions, exempelEndDay, mapOf("G" to 121.4917))

        val result = PortfolioTotalCalc.compute(exempelTransactions, enriched, exempelEndDay)

        assertTrue(result.partial)
        assertNull(result.totalGainKr)
        assertNull(result.totalGainFraction)
        assertNull(result.moneyWeightedAnnualReturn)
        assertEquals(83155.99317344, result.netDepositsKr, 1e-9)
    }

    @Test
    fun `ingen arstakt under ett ar`() {
        val transactions = listOf(kop(global, 20103, 189.1714, 105.7242))
        val enriched = holdings(listOf(global), transactions, exempelEndDay, exempelNav)

        val result = PortfolioTotalCalc.compute(transactions, enriched, exempelEndDay)

        assertNull(result.moneyWeightedAnnualReturn) // 317 dagar
        assertEquals((189.1714 * 121.4917) - (189.1714 * 105.7242), result.totalGainKr!!, 1e-9)
    }

    @Test
    fun `allt salt varderas till noll pa sista transaktionsdagen`() {
        val transactions = listOf(kop(global, 19968, 100.0, 100.0), salj(global, 20420, 100.0, 110.0, fee = 10.0))

        val result = PortfolioTotalCalc.compute(transactions, emptyList(), valuationEpochDay = null)

        assertEquals(-990.0, result.netDepositsKr, 1e-9)
        assertEquals(990.0, result.totalGainKr!!, 1e-9)
        assertNull(result.totalGainFraction) // inget insatt netto kvar att mäta mot
        // 10 000 kr in, 10 990 kr ut 452 dagar senare: (1,099)^(365/452) − 1
        assertEquals(Math.pow(1.099, 365.0 / 452.0) - 1.0, result.moneyWeightedAnnualReturn!!, 1e-9)
    }

    @Test
    fun `xirr ar tio procent for 1000 in och 1100 ut efter 365 dagar`() {
        assertEquals(0.10, PortfolioTotalCalc.xirr(listOf(0L to -1000.0, 365L to 1100.0))!!, 1e-9)
    }

    @Test
    fun `xirr saknar losning utan teckenbyte`() {
        assertNull(PortfolioTotalCalc.xirr(listOf(0L to -1000.0, 400L to -100.0)))
        assertNull(PortfolioTotalCalc.xirr(emptyList()))
    }
}
