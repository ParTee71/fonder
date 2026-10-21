package se.partee71.fonder.domain.usecase

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/**
 * [TaxCalc] (issue #199). De förväntade värdena kommer ur `tools/fondmatte` – kommandot står vid
 * varje test – eller är handräknade i kommentaren, aldrig ur koden som testas.
 */
class TaxCalcTest {

    private fun day(year: Int, month: Int, dayOfMonth: Int) = LocalDate.of(year, month, dayOfMonth).toEpochDay()

    private fun kop(fundId: String, epochDay: Long, shares: Double, price: Double, fee: Double = 0.0) = Transaction(fundId = fundId, type = TransactionType.KOP, epochDay = epochDay, shares = shares, pricePerShare = price, fee = fee)

    private fun salj(fundId: String, epochDay: Long, shares: Double, price: Double, fee: Double = 0.0) = Transaction(fundId = fundId, type = TransactionType.SALJ, epochDay = epochDay, shares = shares, pricePerShare = price, fee = fee)

    private fun price(fundId: String, epochDay: Long, nav: Double) = FundPrice(fundId = fundId, epochDay = epochDay, nav = nav)

    private fun sale(epochDay: Long, gain: Double) = RealizedSale(transactionId = 0, fundId = "A", epochDay = epochDay, shares = 1.0, proceeds = 1_000.0 + gain, fee = 0.0, costBasis = 1_000.0, uncoveredShares = 0.0)

    // --- ISK/KF ---

    @Test
    fun `iskTax matchar mockupen och fondmatte`() {
        // python tools/fondmatte/fondmatte.py isk 2026 480000 495000 510000 515000 --precise → 2129.9999999999995
        val tax = TaxCalc.iskTax(2026, listOf(480_000.0, 495_000.0, 510_000.0, 515_000.0), 0.0)!!
        assertEquals(500_000.0, tax.averageBase, 1e-9)
        assertEquals(2129.9999999999995, tax.tax, 1e-9)
        assertEquals(0.0355, tax.parameters.standardRate, 1e-12)
        assertEquals(300_000.0, tax.parameters.taxFreeAmount, 0.0)
    }

    @Test
    fun `iskTax med insattningar och 2025 ars parametrar`() {
        // python tools/fondmatte/fondmatte.py isk 2026 400000 420000 450000 470000 --deposits 60000 --precise → 1597.4999999999998
        assertEquals(1597.4999999999998, TaxCalc.iskTax(2026, listOf(400_000.0, 420_000.0, 450_000.0, 470_000.0), 60_000.0)!!.tax, 1e-9)
        // python tools/fondmatte/fondmatte.py isk 2025 400000 410000 420000 430000 --deposits 40000 --precise → 2442.0
        assertEquals(2442.0, TaxCalc.iskTax(2025, listOf(400_000.0, 410_000.0, 420_000.0, 430_000.0), 40_000.0)!!.tax, 1e-9)
    }

    @Test
    fun `iskTax under skattefri niva ger noll men visar underlaget`() {
        // python tools/fondmatte/fondmatte.py isk 2026 100000 100000 100000 100000 --precise → 0.0
        val tax = TaxCalc.iskTax(2026, List(4) { 100_000.0 }, 0.0)!!
        assertEquals(0.0, tax.tax, 0.0)
        assertEquals(100_000.0, tax.averageBase, 1e-9)
    }

    @Test
    fun `iskTax for ar utan parametrar ger null`() {
        assertNull(TaxCalc.iskTax(2027, List(4) { 500_000.0 }, 0.0))
        assertNull(TaxCalc.iskTaxForYear(2024, LocalDate.of(2024, 6, 1), emptyList(), emptyMap()))
    }

    private val iskToday = LocalDate.of(2026, 9, 15)

    /** 1 000 andelar köpta 2025, 100 à 520 kr köpta 2026-05-04 (en insättning på 52 000 kr). */
    private val iskTransactions = listOf(
        kop("A", day(2025, 3, 3), 1_000.0, 400.0),
        kop("A", day(2026, 5, 4), 100.0, 520.0)
    )

    @Test
    fun `iskTaxForYear vardar kvartalen till foregaende bankdags kurs och kvartal som inte borjat med dagens`() {
        // Kurserna dagen före kvartalen: 30 december 470 (31 december saknas), 31 mars 500, 30 juni 505.
        // 1 oktober har inte kommit (idag 15 september) → dagens värde, 1 100 × 515. Kursen 2 januari
        // (480) är efter årsskiftet och används inte.
        val history = mapOf(
            "A" to listOf(
                price("A", day(2025, 12, 30), 470.0),
                price("A", day(2026, 1, 2), 480.0),
                price("A", day(2026, 3, 31), 500.0),
                price("A", day(2026, 6, 30), 505.0),
                price("A", day(2026, 9, 14), 515.0)
            )
        )
        // Kvartalen 470 000, 500 000, 555 500, 566 500; insättningar 52 000.
        // python tools/fondmatte/fondmatte.py isk 2026 470000 500000 555500 566500 --deposits 52000 --precise → 2513.4
        val tax = TaxCalc.iskTaxForYear(2026, iskToday, iskTransactions, history)!!
        assertEquals(536_000.0, tax.averageBase, 1e-9)
        assertEquals(2513.4, tax.tax, 1e-9)
    }

    @Test
    fun `iskTaxForYear ger null nar ett passerat kvartal saknar kurs eller ett agt innehav saknar historik`() {
        // 30 juni saknas och 31 mars är mer än fem dagar före dagen före 1 juli.
        val gap = mapOf("A" to listOf(price("A", day(2025, 12, 30), 470.0), price("A", day(2026, 3, 31), 500.0), price("A", day(2026, 9, 14), 515.0)))
        assertNull(TaxCalc.iskTaxForYear(2026, iskToday, iskTransactions, gap))
        assertNull(TaxCalc.iskTaxForYear(2026, iskToday, listOf(kop("A", day(2025, 3, 3), 10.0, 100.0)), emptyMap()))
    }

    @Test
    fun `yearDeposits raknar kop minus salj under aret och lagst noll`() {
        // Handräknat: köp 100 × 50 + 10 avgift = 5 010; sälj 40 × 60 − 5 = 2 395 → 2 615. Köpet 2025 räknas inte.
        val transactions = listOf(
            kop("A", day(2025, 12, 30), 100.0, 50.0),
            kop("A", day(2026, 2, 2), 100.0, 50.0, fee = 10.0),
            salj("A", day(2026, 6, 1), 40.0, 60.0, fee = 5.0)
        )
        assertEquals(2_615.0, TaxCalc.yearDeposits(transactions, 2026), 1e-9)
        assertEquals(0.0, TaxCalc.yearDeposits(listOf(salj("A", day(2026, 6, 1), 40.0, 60.0)), 2026), 0.0)
    }

    // --- Depå/AF ---

    @Test
    fun `realizedTax kvittar vinster och forluster inom aret`() {
        // python tools/fondmatte/fondmatte.py depa --gains 12400 -2400 → realized: gains 12400.0, losses -2400.0, tax 3000.0
        val tax = TaxCalc.realizedTax(listOf(sale(day(2026, 5, 12), 12_400.0), sale(day(2026, 8, 3), -2_400.0), sale(day(2025, 6, 1), 9_999.0)), 2026)
        assertEquals(12_400.0, tax.gains, 1e-9)
        assertEquals(-2_400.0, tax.losses, 1e-9)
        assertEquals(10_000.0, tax.net, 1e-9)
        assertEquals(3_000.0, tax.tax, 1e-9)
    }

    @Test
    fun `realizedTax ger ingen skatt vid nettoforlust`() {
        // python tools/fondmatte/fondmatte.py depa --gains 1000 -4000 → tax 0.0
        assertEquals(0.0, TaxCalc.realizedTax(listOf(sale(day(2026, 1, 5), 1_000.0), sale(day(2026, 2, 5), -4_000.0)), 2026).tax, 0.0)
    }

    @Test
    fun `schablonintakten ar 0,4 procent av vardet 1 januari beskattat med 30 procent`() {
        // python tools/fondmatte/fondmatte.py depa --jan1 250000 → standard_income_tax 300.0
        assertEquals(300.0, TaxCalc.fundStandardIncomeTax(250_000.0), 1e-9)
    }

    @Test
    fun `fundStandardIncomeTaxForYear vardar innehavet fore arsskiftet till foregaende bankdags kurs`() {
        // Handräknat: 500 andelar före årsskiftet (köpet 2026-01-02 räknas inte), kursen 30 december
        // = 500 kr → 250 000 kr; 250 000 × 0,4 % × 30 % = 300 kr
        // (python tools/fondmatte/fondmatte.py depa --jan1 250000 → standard_income_tax 300.0).
        val transactions = listOf(kop("A", day(2025, 6, 2), 500.0, 420.0), kop("A", day(2026, 1, 2), 50.0, 500.0))
        val history = mapOf("A" to listOf(price("A", day(2025, 12, 30), 500.0), price("A", day(2026, 1, 2), 510.0), price("A", day(2026, 3, 2), 530.0)))
        val result = TaxCalc.fundStandardIncomeTaxForYear(2026, LocalDate.of(2026, 10, 11), transactions, history)!!
        assertEquals(250_000.0, result.valueAtYearStart, 1e-9)
        assertEquals(300.0, result.tax, 1e-9)
    }

    @Test
    fun `fundStandardIncomeTaxForYear ger null utan innehav vid arets borjan eller utan kurs strax fore`() {
        val today = LocalDate.of(2026, 10, 11)
        assertNull(TaxCalc.fundStandardIncomeTaxForYear(2026, today, listOf(kop("A", day(2026, 2, 2), 10.0, 100.0)), emptyMap()))
        // Senaste kurs före årsskiftet är 20 december, elva dagar före 31 december – okänt, inte gissat.
        val gap = mapOf("A" to listOf(price("A", day(2025, 12, 20), 100.0), price("A", day(2026, 1, 2), 101.0)))
        assertNull(TaxCalc.fundStandardIncomeTaxForYear(2026, today, listOf(kop("A", day(2025, 2, 2), 10.0, 100.0)), gap))
    }

    @Test
    fun `switchTax tar 30 procent pa orealiserad vinst och noll vid forlust`() {
        // python tools/fondmatte/fondmatte.py depa --value 65500 --cost 50000
        //   → switch.tax_all 4650.0, switch.per_amount 709.9236641221373
        val tax = TaxCalc.switchTax(value = 65_500.0, costBasis = 50_000.0)
        assertEquals(15_500.0, tax.gain, 1e-9)
        assertEquals(4_650.0, tax.taxAll, 1e-9)
        assertEquals(709.9236641221373, tax.taxPerExampleAmount, 1e-9)
        val loss = TaxCalc.switchTax(value = 40_000.0, costBasis = 50_000.0)
        assertEquals(0.0, loss.taxAll, 0.0)
        assertEquals(0.0, loss.taxPerExampleAmount, 0.0)
    }

    // --- Parametrarna på ett ställe ---

    /** Arbetskatalogen för `:core:test` är modulens katalog; filerna ligger i repots rot. */
    private fun repoFile(path: String) = File("..", path)

    @Test
    fun `ISK_PARAMETERS ar samma som i fondmatte`() {
        val source = repoFile("tools/fondmatte/fondmatte.py").readText()
        val python = Regex("""(\d{4}): \{"government_bond_rate": ([\d.]+), "tax_free_amount": ([\d_]+)\}""")
            .findAll(source)
            .associate { m -> m.groupValues[1].toInt() to TaxCalc.IskParameters(m.groupValues[2].toDouble(), m.groupValues[3].replace("_", "").toDouble()) }
        assertEquals(TaxCalc.ISK_PARAMETERS, python)
    }

    @Test
    fun `ISK_PARAMETERS ar samma som tabellen i skill fondmatematik`() {
        val skill = repoFile(".claude/skills/fondmatematik/SKILL.md").readText()
        fun number(text: String) = text.replace(" ", "").replace(' '.toString(), "").replace(',', '.').toDouble()
        val table = Regex("""\|\s*(\d{4})\s*\|\s*([\d,]+) %\s*\|\s*([\d,]+) %\s*\|\s*([\d,]+) %\s*\|\s*([\d  ]+) kr\s*\|""")
            .findAll(skill)
            .associate { m ->
                val year = m.groupValues[1].toInt()
                val parameters = TaxCalc.IskParameters(number(m.groupValues[2]) / 100.0, number(m.groupValues[5]))
                // Tabellens schablonränta och effektiva skatt ska också stämma med parametrarna.
                assertEquals("schablonränta $year", parameters.standardRate, number(m.groupValues[3]) / 100.0, 1e-12)
                assertEquals("effektiv skatt $year", parameters.standardRate * TaxCalc.CAPITAL_INCOME_TAX, number(m.groupValues[4]) / 100.0, 1e-12)
                year to parameters
            }
        assertEquals(TaxCalc.ISK_PARAMETERS.keys, table.keys)
        TaxCalc.ISK_PARAMETERS.forEach { (year, parameters) ->
            assertEquals(parameters.governmentBondRate, table.getValue(year).governmentBondRate, 1e-12)
            assertEquals(parameters.taxFreeAmount, table.getValue(year).taxFreeAmount, 0.0)
        }
    }
}
