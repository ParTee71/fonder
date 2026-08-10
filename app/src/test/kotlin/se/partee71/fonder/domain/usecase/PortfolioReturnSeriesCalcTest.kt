package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/**
 * [PortfolioReturnSeriesCalc] — kurvan bakom totalkortets procentsiffra (HEM-9) och
 * skuggportföljen den jämförs med (HEM-10).
 */
class PortfolioReturnSeriesCalcTest {

    private val fondA = Fund(fundId = "A", name = "Fond A")
    private val fondB = Fund(fundId = "B", name = "Fond B")

    private fun kop(fundId: String, day: Long, shares: Double, price: Double) =
        Transaction(fundId = fundId, type = TransactionType.KOP, epochDay = day, shares = shares, pricePerShare = price)

    private fun salj(fundId: String, day: Long, shares: Double, price: Double) =
        Transaction(fundId = fundId, type = TransactionType.SALJ, epochDay = day, shares = shares, pricePerShare = price)

    private fun priser(fundId: String, vararg dagOchNav: Pair<Long, Double>) =
        dagOchNav.map { (day, nav) -> FundPrice(fundId = fundId, epochDay = day, nav = nav) }

    @Test
    fun `avkastningen per dag ar vardet mot anskaffningsvardet`() {
        val transaktioner = listOf(kop("A", day = 10, shares = 10.0, price = 100.0))
        val historik = mapOf("A" to priser("A", 10L to 100.0, 11L to 110.0, 12L to 90.0))

        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA), transaktioner, historik)

        assertEquals(listOf(10L, 11L, 12L), result.points.map { it.first })
        assertEquals(0.0, result.points[0].second, 1e-9)
        assertEquals(0.10, result.points[1].second, 1e-9)
        assertEquals(-0.10, result.points[2].second, 1e-9)
        assertFalse(result.partial)
    }

    @Test
    fun `sista punkten ar samma tal som totalkortet visar`() {
        // Regressionsvakt: kurvan och PortfolioCalc.totalGainLossFraction (totalkortets siffra)
        // måste svara identiskt på samma fråga — glider de isär visar Hem två sanningar.
        val transaktioner = listOf(
            kop("A", day = 10, shares = 10.0, price = 100.0),
            kop("B", day = 12, shares = 5.0, price = 200.0),
        )
        val historik = mapOf(
            "A" to priser("A", 10L to 100.0, 14L to 130.0),
            "B" to priser("B", 12L to 200.0, 14L to 190.0),
        )
        val funds = listOf(fondA, fondB)

        val kurva = PortfolioReturnSeriesCalc.compute(funds, transaktioner, historik)
        val holdings = PortfolioCalc.withCurrentValue(
            PortfolioCalc.computeHoldings(funds, transaktioner),
            mapOf("A" to FundPrice("A", 14L, 130.0), "B" to FundPrice("B", 14L, 190.0)),
        )

        assertEquals(14L, kurva.points.last().first)
        assertEquals(PortfolioCalc.totalGainLossFraction(holdings)!!, kurva.points.last().second, 1e-12)
    }

    @Test
    fun `saknad kurs framatfylls over en helg i stallet for att skapa ett hal`() {
        val transaktioner = listOf(kop("A", day = 10, shares = 10.0, price = 100.0))
        // Fond B har ingen kurs dag 11, men dag 10 — då gäller dag 10:s kurs, inte "okänt".
        val transaktionerBada = transaktioner + kop("B", day = 10, shares = 1.0, price = 50.0)
        val historik = mapOf(
            "A" to priser("A", 10L to 100.0, 11L to 100.0),
            "B" to priser("B", 10L to 50.0),
        )

        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA, fondB), transaktionerBada, historik)

        assertFalse("framåtfylld kurs är känd data, inte en lucka", result.partial)
        assertEquals(0.0, result.points.last().second, 1e-9)
    }

    @Test
    fun `innehav utan kurs den dagen utesluts ur bade varde och investerat och markerar partial`() {
        val transaktioner = listOf(
            kop("A", day = 10, shares = 10.0, price = 100.0),
            kop("B", day = 10, shares = 10.0, price = 100.0),
        )
        // Fond B:s historik börjar först dag 12 — dag 10 och 11 kan den inte värderas alls.
        val historik = mapOf(
            "A" to priser("A", 10L to 100.0, 11L to 120.0),
            "B" to priser("B", 12L to 100.0),
        )

        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA, fondB), transaktioner, historik)

        assertTrue(result.partial)
        // Dag 11: bara fond A räknas — +20 %, inte +10 % (som en halv portfölj mot hela
        // anskaffningsvärdet hade gett).
        assertEquals(0.20, result.points.first { it.first == 11L }.second, 1e-9)
    }

    @Test
    fun `inga punkter fore forsta kopet`() {
        val transaktioner = listOf(kop("A", day = 20, shares = 1.0, price = 100.0))
        val historik = mapOf("A" to priser("A", 10L to 90.0, 15L to 95.0, 20L to 100.0, 21L to 105.0))

        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA), transaktioner, historik)

        assertEquals(listOf(20L, 21L), result.points.map { it.first })
    }

    @Test
    fun `delforsaljning andrar anskaffningsvardet enligt FIFO`() {
        // Två köp till olika kurs, sedan en sälj av den första lotten: kvarvarande andelar har
        // det dyrare köpets anskaffningsvärde, och kurvan ska följa det — inte kassaflödet.
        val transaktioner = listOf(
            kop("A", day = 10, shares = 10.0, price = 100.0),
            kop("A", day = 11, shares = 10.0, price = 200.0),
            salj("A", day = 12, shares = 10.0, price = 200.0),
        )
        val historik = mapOf("A" to priser("A", 10L to 100.0, 11L to 200.0, 12L to 200.0, 13L to 220.0))

        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA), transaktioner, historik)

        // Dag 12: 10 kvarvarande andelar à 200 kr i värde, anskaffningsvärde 10 × 200 = 2 000 → 0 %.
        assertEquals(0.0, result.points.first { it.first == 12L }.second, 1e-9)
        // Dag 13: värde 2 200 mot anskaffningsvärde 2 000 → +10 %.
        assertEquals(0.10, result.points.first { it.first == 13L }.second, 1e-9)
    }

    @Test
    fun `tom transaktionslista ger tom serie`() {
        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA), emptyList(), mapOf("A" to priser("A", 10L to 100.0)))

        assertTrue(result.isEmpty)
        assertFalse(result.partial)
    }

    @Test
    fun `helt avsald portfolj ger inga punkter efter forsaljningen`() {
        // netInvested = 0 → ingen kvot att visa. Aldrig ett påhittat 0 %.
        val transaktioner = listOf(
            kop("A", day = 10, shares = 10.0, price = 100.0),
            salj("A", day = 11, shares = 10.0, price = 150.0),
        )
        val historik = mapOf("A" to priser("A", 10L to 100.0, 11L to 150.0, 12L to 160.0))

        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA), transaktioner, historik)

        assertEquals(listOf(10L), result.points.map { it.first })
    }

    @Test
    fun `en sedan avsald fond raknas med for de dagar den faktiskt agdes`() {
        // Fonden finns inte kvar i dagens innehav, men fanns i portföljen dag 10–11. Utan hela
        // fondlistan hade kurvan ritat om historien som om den aldrig ägts.
        val transaktioner = listOf(
            kop("A", day = 10, shares = 10.0, price = 100.0),
            salj("A", day = 12, shares = 10.0, price = 100.0),
            kop("B", day = 10, shares = 10.0, price = 100.0),
        )
        val historik = mapOf(
            "A" to priser("A", 10L to 100.0, 11L to 50.0, 12L to 100.0),
            "B" to priser("B", 10L to 100.0, 11L to 100.0, 12L to 100.0),
        )

        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA, fondB), transaktioner, historik)

        // Dag 11: A halverad (500 kr) + B oförändrad (1 000 kr) mot 2 000 kr investerat → −25 %.
        assertEquals(-0.25, result.points.first { it.first == 11L }.second, 1e-9)
    }

    @Test
    fun `skuggportfoljen ar identisk nar referensfonden ror sig som portfoljen`() {
        val transaktioner = listOf(kop("A", day = 10, shares = 10.0, price = 100.0))
        val historik = mapOf("A" to priser("A", 10L to 100.0, 11L to 110.0))
        val referens = priser("IDX", 10L to 50.0, 11L to 55.0) // samma +10 %, annan kursnivå

        val portfolj = PortfolioReturnSeriesCalc.compute(listOf(fondA), transaktioner, historik)
        val benchmark = PortfolioReturnSeriesCalc.benchmark(transaktioner, referens)

        assertNotNull(benchmark)
        assertEquals(portfolj.points.map { it.first }, benchmark!!.points.map { it.first })
        portfolj.points.zip(benchmark.points).forEach { (egen, index) ->
            assertEquals(egen.second, index.second, 1e-9)
        }
    }

    @Test
    fun `skuggportfoljen anvander samma belopp samma dag men referensfondens kurs`() {
        // 1 000 kr insatta dag 10 → 1 000 / 250 = 4 andelar i referensfonden. Dag 11 står den i
        // 300 kr → 1 200 kr mot 1 000 kr investerat = +20 %.
        val transaktioner = listOf(kop("A", day = 10, shares = 10.0, price = 100.0))
        val referens = priser("IDX", 10L to 250.0, 11L to 300.0)

        val benchmark = PortfolioReturnSeriesCalc.benchmark(transaktioner, referens)!!

        assertEquals(0.0, benchmark.points.first { it.first == 10L }.second, 1e-9)
        assertEquals(0.20, benchmark.points.first { it.first == 11L }.second, 1e-9)
    }

    @Test
    fun `skuggportfoljen speglar en forsaljning proportionellt`() {
        // Köp 1 000 kr dag 10 (4 andelar à 250), sälj för 500 kr dag 11 (referensen står i 250
        // → 2 andelar bort). Kvar: 2 andelar à 250 = 500 kr mot 500 kr anskaffningsvärde = 0 %.
        val transaktioner = listOf(
            kop("A", day = 10, shares = 10.0, price = 100.0),
            salj("A", day = 11, shares = 5.0, price = 100.0),
        )
        val referens = priser("IDX", 10L to 250.0, 11L to 250.0)

        val benchmark = PortfolioReturnSeriesCalc.benchmark(transaktioner, referens)!!

        assertEquals(0.0, benchmark.points.first { it.first == 11L }.second, 1e-9)
    }

    @Test
    fun `referensfondens kurs framatfylls till en transaktionsdag utan NAV`() {
        // Köpet gjordes en dag referensfonden saknar kurs (helg) — närmast föregående gäller.
        val transaktioner = listOf(kop("A", day = 11, shares = 10.0, price = 100.0))
        val referens = priser("IDX", 10L to 100.0, 12L to 110.0)

        val benchmark = PortfolioReturnSeriesCalc.benchmark(transaktioner, referens)!!

        assertEquals(0.10, benchmark.points.first { it.first == 12L }.second, 1e-9)
    }

    @Test
    fun `ingen skuggportfolj nar referenshistoriken inte nar tillbaka till forsta kopet`() {
        // En jämförelse som tyst hoppar över den första insättningen är inte samma kassaflöden.
        val transaktioner = listOf(kop("A", day = 10, shares = 10.0, price = 100.0))
        val referens = priser("IDX", 15L to 100.0, 16L to 110.0)

        assertNull(PortfolioReturnSeriesCalc.benchmark(transaktioner, referens))
    }

    @Test
    fun `ingen skuggportfolj utan referenshistorik alls`() {
        val transaktioner = listOf(kop("A", day = 10, shares = 10.0, price = 100.0))

        assertNull(PortfolioReturnSeriesCalc.benchmark(transaktioner, emptyList()))
        assertNull(PortfolioReturnSeriesCalc.benchmark(emptyList(), priser("IDX", 10L to 100.0)))
    }
}
