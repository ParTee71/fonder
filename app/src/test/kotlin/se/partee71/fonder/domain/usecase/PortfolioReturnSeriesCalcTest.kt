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
 * [PortfolioReturnSeriesCalc] — portföljens tidsviktade avkastningskurva (HEM-9) och
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

    /** En enkomponentsreferens — motsvarar hela insättningen i en enda fond. */
    private fun enFond(history: List<FundPrice>) =
        listOf(PortfolioReturnSeriesCalc.BenchmarkComponent(weight = 1.0, history = history))

    /** Avrundar bort flyttalsbruset så en hel kurva kan jämföras som lista i stället för punkt för punkt. */
    private fun Double.round9(): Double = kotlin.math.round(this * 1e9) / 1e9

    @Test
    fun `kurvan ar en kedja av dagsavkastningar`() {
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
    fun `kedjan ar multiplikativ inte additiv`() {
        // Två dagar à +10 % är +21 %, inte +20 %. Det är skillnaden mellan att kedja
        // dagsavkastningar och att lägga ihop dem.
        val transaktioner = listOf(kop("A", day = 10, shares = 10.0, price = 100.0))
        val historik = mapOf("A" to priser("A", 10L to 100.0, 11L to 110.0, 12L to 121.0))

        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA), transaktioner, historik)

        assertEquals(0.21, result.points.last().second, 1e-9)
    }

    @Test
    fun `en insattning mitt i perioden flyttar inte kurvan`() {
        // Kärnregressionen för issue #116: samma fonder och samma kursutveckling ska ge exakt
        // samma kurva oavsett hur mycket nytt kapital som sköts in på vägen. Med det gamla
        // måttet (värde mot anskaffningsvärde) späddes den upparbetade procenten ut av
        // insättningen, vilket gjorde långa perioder systematiskt sämre än korta.
        val historik = mapOf("A" to priser("A", 10L to 100.0, 11L to 110.0, 12L to 121.0))
        val utanPafyllning = listOf(kop("A", day = 10, shares = 10.0, price = 100.0))
        val medPafyllning = utanPafyllning + kop("A", day = 11, shares = 100.0, price = 110.0)

        val utan = PortfolioReturnSeriesCalc.compute(listOf(fondA), utanPafyllning, historik)
        val med = PortfolioReturnSeriesCalc.compute(listOf(fondA), medPafyllning, historik)

        assertEquals(listOf(0.0, 0.10, 0.21), utan.points.map { it.second.round9() })
        assertEquals(
            utan.points.map { (dag, avkastning) -> dag to avkastning.round9() },
            med.points.map { (dag, avkastning) -> dag to avkastning.round9() },
        )
    }

    @Test
    fun `ett byte samma dag ger ingen hack i kurvan`() {
        // Sälj A och köp B samma dag. Det gamla måttet strök A:s upparbetade vinst ur serien
        // när positionen stängdes — i en app som handlar om byten sänktes kurvan vid varje byte
        // utan att en krona gått förlorad.
        val transaktioner = listOf(
            kop("A", day = 10, shares = 10.0, price = 100.0),
            salj("A", day = 11, shares = 10.0, price = 110.0),
            kop("B", day = 11, shares = 11.0, price = 100.0),
        )
        val historik = mapOf(
            "A" to priser("A", 10L to 100.0, 11L to 110.0),
            "B" to priser("B", 10L to 100.0, 11L to 100.0, 12L to 110.0),
        )

        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA, fondB), transaktioner, historik)

        // Dag 11: A:s +10 % räknas — den ägdes under rörelsen. Dag 12: B:s +10 % ovanpå det.
        assertEquals(listOf(0.0, 0.10, 0.21), result.points.map { it.second.round9() })
    }

    @Test
    fun `en forsaljning med vinst sanker inte kurvan`() {
        val transaktioner = listOf(
            kop("A", day = 10, shares = 10.0, price = 100.0),
            salj("A", day = 11, shares = 5.0, price = 150.0),
        )
        val historik = mapOf("A" to priser("A", 10L to 100.0, 11L to 150.0, 12L to 150.0))

        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA), transaktioner, historik)

        assertEquals(0.50, result.points.first { it.first == 11L }.second, 1e-9)
        assertEquals(0.50, result.points.first { it.first == 12L }.second, 1e-9)
    }

    @Test
    fun `dagsavkastningen ar vardeviktad mellan innehaven`() {
        // 1 000 kr i A (+10 %) och 100 kr i B (+100 %) → 1 300 kr mot 1 100 kr, dvs +18,2 %.
        // Ett osviktat snitt hade gett +55 %.
        val transaktioner = listOf(
            kop("A", day = 10, shares = 10.0, price = 100.0),
            kop("B", day = 10, shares = 10.0, price = 10.0),
        )
        val historik = mapOf(
            "A" to priser("A", 10L to 100.0, 11L to 110.0),
            "B" to priser("B", 10L to 10.0, 11L to 20.0),
        )

        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA, fondB), transaktioner, historik)

        assertEquals(200.0 / 1_100.0, result.points.last().second, 1e-9)
    }

    @Test
    fun `kurvan skiljer sig medvetet fran totalkortet nar insattningar gjorts`() {
        // Kurvan mäter fondernas utveckling, totalkortet avkastningen på insatt kapital. Efter en
        // påfyllning är de två olika tal — det är avsiktligt (issue #116) och sägs ut i UI-texten.
        // Vaktar att kopplingen är medvetet bruten och inte tyst tappad igen.
        val transaktioner = listOf(
            kop("A", day = 10, shares = 10.0, price = 100.0),
            kop("A", day = 11, shares = 100.0, price = 110.0),
        )
        val historik = mapOf("A" to priser("A", 10L to 100.0, 11L to 110.0, 12L to 121.0))
        val holdings = PortfolioCalc.withCurrentValue(
            PortfolioCalc.computeHoldings(listOf(fondA), transaktioner),
            mapOf("A" to FundPrice("A", 12L, 121.0)),
        )

        val kurva = PortfolioReturnSeriesCalc.compute(listOf(fondA), transaktioner, historik)

        assertEquals(0.21, kurva.points.last().second, 1e-9)
        // Totalkortet: 110 andelar à 121 kr mot 12 000 kr investerat ≈ +10,9 %.
        assertEquals(1_310.0 / 12_000.0, PortfolioCalc.totalGainLossFraction(holdings)!!, 1e-9)
    }

    @Test
    fun `ett kop pa en dag utan NAV far med rorelsen till nasta kursdag`() {
        // Köpet gjordes en helg — utan transaktionsdagen i tidslinjen hade kedjan startat först
        // dag 12 och rörelsen från köpkursen dit fallit bort.
        val transaktioner = listOf(kop("A", day = 11, shares = 10.0, price = 100.0))
        val historik = mapOf("A" to priser("A", 10L to 100.0, 12L to 110.0))

        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA), transaktioner, historik)

        assertEquals(listOf(11L, 12L), result.points.map { it.first })
        assertEquals(0.10, result.points.last().second, 1e-9)
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
    fun `en delforsaljning flyttar inte kurvan`() {
        // Två köp till olika kurs, sedan en sälj av den första lotten. Anskaffningsvärdet
        // (FIFO) styr totalkortet, men inte den tidsviktade kurvan: kedjan följer kursrörelsen
        // på de andelar som faktiskt ägdes varje dag.
        val transaktioner = listOf(
            kop("A", day = 10, shares = 10.0, price = 100.0),
            kop("A", day = 11, shares = 10.0, price = 200.0),
            salj("A", day = 12, shares = 10.0, price = 200.0),
        )
        val historik = mapOf("A" to priser("A", 10L to 100.0, 11L to 200.0, 12L to 200.0, 13L to 220.0))

        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA), transaktioner, historik)

        // Dag 11: kursen dubblas på de 10 andelar som ägdes dag 10 → +100 %.
        assertEquals(1.0, result.points.first { it.first == 11L }.second, 1e-9)
        // Dag 12: säljdagen rör inte kedjan, kursen står still → oförändrat +100 %.
        assertEquals(1.0, result.points.first { it.first == 12L }.second, 1e-9)
        // Dag 13: +10 % på de kvarvarande andelarna → 2,0 × 1,1 − 1 = +120 %.
        assertEquals(1.20, result.points.first { it.first == 13L }.second, 1e-9)
    }

    @Test
    fun `tom transaktionslista ger tom serie`() {
        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA), emptyList(), mapOf("A" to priser("A", 10L to 100.0)))

        assertTrue(result.isEmpty)
        assertFalse(result.partial)
    }

    @Test
    fun `en helt avsald portfolj flatlinjerar i stallet for att brytas`() {
        // Dagarna mellan ett sälj och nästa köp (ett pågående byte, ANA-12) finns inget att
        // värdera. Kedjan pausar på sin nivå — den upparbetade avkastningen försvinner inte,
        // och när pengarna är tillbaka i marknaden fortsätter den utan hopp.
        val transaktioner = listOf(
            kop("A", day = 10, shares = 10.0, price = 100.0),
            salj("A", day = 11, shares = 10.0, price = 150.0),
            kop("B", day = 13, shares = 15.0, price = 100.0),
        )
        val historik = mapOf(
            "A" to priser("A", 10L to 100.0, 11L to 150.0, 12L to 160.0),
            "B" to priser("B", 13L to 100.0, 14L to 110.0),
        )

        val result = PortfolioReturnSeriesCalc.compute(listOf(fondA, fondB), transaktioner, historik)

        assertEquals(listOf(10L, 11L, 12L, 13L, 14L), result.points.map { it.first })
        // Dag 11 gav +50 %. Dag 12–13 står kedjan still (inget ägs, A:s fortsatta uppgång är
        // inte portföljens), dag 14 lägger B:s +10 % ovanpå: 1,5 × 1,1 − 1 = +65 %.
        assertEquals(listOf(0.0, 0.50, 0.50, 0.50, 0.65), result.points.map { it.second.round9() })
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
        val benchmark = PortfolioReturnSeriesCalc.benchmark(transaktioner, enFond(referens))

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

        val benchmark = PortfolioReturnSeriesCalc.benchmark(transaktioner, enFond(referens))!!

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

        val benchmark = PortfolioReturnSeriesCalc.benchmark(transaktioner, enFond(referens))!!

        assertEquals(0.0, benchmark.points.first { it.first == 11L }.second, 1e-9)
    }

    @Test
    fun `referensfondens kurs framatfylls till en transaktionsdag utan NAV`() {
        // Köpet gjordes en dag referensfonden saknar kurs (helg) — närmast föregående gäller.
        val transaktioner = listOf(kop("A", day = 11, shares = 10.0, price = 100.0))
        val referens = priser("IDX", 10L to 100.0, 12L to 110.0)

        val benchmark = PortfolioReturnSeriesCalc.benchmark(transaktioner, enFond(referens))!!

        assertEquals(0.10, benchmark.points.first { it.first == 12L }.second, 1e-9)
    }

    @Test
    fun `ingen skuggportfolj nar referenshistoriken inte nar tillbaka till forsta kopet`() {
        // En jämförelse som tyst hoppar över den första insättningen är inte samma kassaflöden.
        val transaktioner = listOf(kop("A", day = 10, shares = 10.0, price = 100.0))
        val referens = priser("IDX", 15L to 100.0, 16L to 110.0)

        assertNull(PortfolioReturnSeriesCalc.benchmark(transaktioner, enFond(referens)))
    }

    @Test
    fun `ingen skuggportfolj utan referenshistorik alls`() {
        val transaktioner = listOf(kop("A", day = 10, shares = 10.0, price = 100.0))

        assertNull(PortfolioReturnSeriesCalc.benchmark(transaktioner, emptyList()))
        assertNull(PortfolioReturnSeriesCalc.benchmark(emptyList(), enFond(priser("IDX", 10L to 100.0))))
    }

    @Test
    fun `en enkomponentsblandning ger exakt samma kurva som en ensam referensfond`() {
        // Regressionsvakt för issue #101: viktningen infördes utan att ändra normalfallet.
        val transaktioner = listOf(kop("A", day = 10, shares = 10.0, price = 100.0))
        val referens = priser("IDX", 10L to 250.0, 11L to 300.0)

        val en = PortfolioReturnSeriesCalc.benchmark(transaktioner, enFond(referens))!!
        val viktad = PortfolioReturnSeriesCalc.benchmark(
            transaktioner,
            listOf(PortfolioReturnSeriesCalc.BenchmarkComponent(weight = 1.0, history = referens)),
        )!!

        assertEquals(en.points, viktad.points)
    }

    @Test
    fun `viktad blandning ger det viktade snittet av komponenternas utveckling`() {
        // 1 000 kr: 700 i aktier (+20 %) och 300 i räntor (+0 %) → 840 + 300 = 1 140, dvs +14 %.
        val transaktioner = listOf(kop("A", day = 10, shares = 10.0, price = 100.0))
        val aktier = priser("AKT", 10L to 100.0, 11L to 120.0)
        val rantor = priser("RNT", 10L to 50.0, 11L to 50.0)

        val result = PortfolioReturnSeriesCalc.benchmark(
            transaktioner,
            listOf(
                PortfolioReturnSeriesCalc.BenchmarkComponent(weight = 0.7, history = aktier),
                PortfolioReturnSeriesCalc.BenchmarkComponent(weight = 0.3, history = rantor),
            ),
        )!!

        assertEquals(0.0, result.points.first { it.first == 10L }.second, 1e-9)
        assertEquals(0.14, result.points.first { it.first == 11L }.second, 1e-9)
    }

    @Test
    fun `blandningen speglar varje insattning, inte bara den forsta`() {
        // Andra insättningen delas efter samma vikter till den dagens kurser.
        val transaktioner = listOf(
            kop("A", day = 10, shares = 10.0, price = 100.0),
            kop("A", day = 11, shares = 10.0, price = 100.0),
        )
        val aktier = priser("AKT", 10L to 100.0, 11L to 200.0)
        val rantor = priser("RNT", 10L to 100.0, 11L to 100.0)

        val result = PortfolioReturnSeriesCalc.benchmark(
            transaktioner,
            listOf(
                PortfolioReturnSeriesCalc.BenchmarkComponent(weight = 0.5, history = aktier),
                PortfolioReturnSeriesCalc.BenchmarkComponent(weight = 0.5, history = rantor),
            ),
        )!!

        // Dag 11 mäts på köp 1:s andelar: 5 st à 100 i aktier (nu 200) och 5 st à 100 i räntor.
        // 1 500 mot 1 000 = +50 %. Dagens andra insättning räknas först från dag 12 — den späder
        // inte ut kurvan (issue #116).
        assertEquals(0.50, result.points.first { it.first == 11L }.second, 1e-9)
    }

    @Test
    fun `skuggportfoljen ar oberoende av nar insattningarna gjordes`() {
        // Samma referensfond och samma kursutveckling ska ge samma skuggkurva oavsett
        // insättningsmönster — annars mäter jämförelsen fortfarande kassaflöden.
        val referens = priser("IDX", 10L to 100.0, 11L to 110.0, 12L to 121.0)
        val enInsattning = listOf(kop("A", day = 10, shares = 10.0, price = 100.0))
        val tvaInsattningar = enInsattning + kop("A", day = 11, shares = 100.0, price = 100.0)

        val en = PortfolioReturnSeriesCalc.benchmark(enInsattning, enFond(referens))!!
        val tva = PortfolioReturnSeriesCalc.benchmark(tvaInsattningar, enFond(referens))!!

        assertEquals(listOf(0.0, 0.10, 0.21), en.points.map { it.second.round9() })
        assertEquals(en.points.map { it.second.round9() }, tva.points.map { it.second.round9() })
    }

    @Test
    fun `en komponent utan historik ger ingen kurva alls`() {
        // Halva blandningen saknas — en jämförelse på fel vikter är sämre än ingen.
        val transaktioner = listOf(kop("A", day = 10, shares = 10.0, price = 100.0))

        val result = PortfolioReturnSeriesCalc.benchmark(
            transaktioner,
            listOf(
                PortfolioReturnSeriesCalc.BenchmarkComponent(weight = 0.5, history = priser("AKT", 10L to 100.0)),
                PortfolioReturnSeriesCalc.BenchmarkComponent(weight = 0.5, history = emptyList()),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `en komponent vars historik inte nar tillbaka till forsta kopet ger ingen kurva`() {
        val transaktioner = listOf(kop("A", day = 10, shares = 10.0, price = 100.0))

        val result = PortfolioReturnSeriesCalc.benchmark(
            transaktioner,
            listOf(
                PortfolioReturnSeriesCalc.BenchmarkComponent(weight = 0.5, history = priser("AKT", 10L to 100.0, 11L to 110.0)),
                PortfolioReturnSeriesCalc.BenchmarkComponent(weight = 0.5, history = priser("RNT", 11L to 100.0)),
            ),
        )

        assertNull(result)
    }
}
