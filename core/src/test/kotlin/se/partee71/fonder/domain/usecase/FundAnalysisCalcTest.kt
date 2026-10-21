package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

class FundAnalysisCalcTest {

    private val fond = Fund(fundId = "SHB0000442", name = "Fond A")
    private val today = LocalDate.of(2026, 7, 31)

    private fun price(daysAgo: Long, nav: Double) = FundPrice(fundId = fond.fundId, epochDay = today.minusDays(daysAgo).toEpochDay(), nav = nav)

    /** Daglig historik 4 år tillbaka, NAV stiger linjärt från 50 till 120 (dagens kurs). */
    private fun longHistory(): List<FundPrice> = (0..1460L step 1).map { daysAgo -> price(daysAgo, nav = 120.0 - daysAgo * (70.0 / 1460.0)) }

    @Test
    fun `periodavkastning berknas fran senaste kanda NAV pa eller fore periodens start`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0, currentValue = 1200.0)
        val history = longHistory()
        val firstPurchase = today.minusYears(2)

        val analysis = FundAnalysisCalc.analyze(
            today = today,
            holding = holding,
            transactions = emptyList(),
            priceHistory = history,
            firstPurchaseDate = firstPurchase,
            portfolioTotalValue = 1200.0,
            otherHoldingsAverageThreeMonthReturn = null
        )!!

        val threeMonths = analysis.keyFigures.periodReturns.first { it.period == FundAnalysisCalc.Period.TRE_MANADER }
        assertTrue(threeMonths.fraction != null && threeMonths.fraction!! > 0.0) // NAV stiger, så positiv utveckling
    }

    @Test
    fun `period utan tillrcklig historik far null i stallet for gissat varde`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0, currentValue = 1200.0)
        // Bara 30 dagars historik — otillräckligt för "3 år".
        val history = (0..30L).map { price(it, nav = 100.0 + it) }

        val analysis = FundAnalysisCalc.analyze(
            today = today,
            holding = holding,
            transactions = emptyList(),
            priceHistory = history,
            firstPurchaseDate = today.minusDays(30),
            portfolioTotalValue = 1200.0,
            otherHoldingsAverageThreeMonthReturn = null
        )!!

        val threeYears = analysis.keyFigures.periodReturns.first { it.period == FundAnalysisCalc.Period.TRE_AR }
        assertNull(threeYears.amount)
        assertNull(threeYears.fraction)
    }

    @Test
    fun `CAGR ar null om innehavet ar yngre an ett ar`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0, currentValue = 1200.0)
        val history = longHistory()

        val analysis = FundAnalysisCalc.analyze(
            today = today,
            holding = holding,
            transactions = emptyList(),
            priceHistory = history,
            firstPurchaseDate = today.minusMonths(6),
            portfolioTotalValue = 1200.0,
            otherHoldingsAverageThreeMonthReturn = null
        )!!

        assertNull(analysis.keyFigures.cagr)
    }

    @Test
    fun `CAGR berknas for ett innehav minst ett ar gammalt`() {
        val firstPurchase = today.minusYears(2)
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0, currentValue = 1200.0)
        // NAV dubblas over exakt 2 ar -> CAGR = sqrt(2) - 1 ~ 41.4 %.
        val history = listOf(price(daysAgo = 0, nav = 200.0), price(daysAgo = 730, nav = 100.0))

        val analysis = FundAnalysisCalc.analyze(
            today = today,
            holding = holding,
            transactions = emptyList(),
            priceHistory = history,
            firstPurchaseDate = firstPurchase,
            portfolioTotalValue = 2000.0,
            otherHoldingsAverageThreeMonthReturn = null
        )!!

        assertEquals(kotlin.math.sqrt(2.0) - 1.0, analysis.keyFigures.cagr!!, 1e-3)
    }

    private val holding198 = Holding(fund = fond, netShares = 10.0, netInvested = 500.0, currentValue = 1200.0)

    private fun analyze198(history: List<FundPrice>, today: LocalDate, firstPurchase: LocalDate) = FundAnalysisCalc.analyze(
        today = today,
        holding = holding198,
        transactions = emptyList(),
        priceHistory = history,
        firstPurchaseDate = firstPurchase,
        portfolioTotalValue = 1200.0,
        otherHoldingsAverageThreeMonthReturn = null
    )!!

    private fun fundPrice(day: String, nav: Double) = FundPrice(fond.fundId, LocalDate.parse(day).toEpochDay(), nav)

    @Test
    fun `lucka fore periodens start ger otillrcklig data i stallet for en gammal kurs (issue 198)`() {
        // Kurser till 2026-03-01, sedan från 2026-09-15; idag 2026-10-20. "3 månader" startar 2026-07-20.
        val history = listOf(fundPrice("2026-03-01", 100.0), fundPrice("2026-09-15", 120.0), fundPrice("2026-10-20", 130.0))
        val today = LocalDate.of(2026, 10, 20)

        val analysis = analyze198(history, today, firstPurchase = LocalDate.of(2026, 9, 15))

        val threeMonths = analysis.row(FundAnalysisCalc.Period.TRE_MANADER)
        assertNull(threeMonths.fraction)
        assertNull(threeMonths.amount)
        assertNull(FundAnalysisCalc.threeMonthReturn(today, history))
    }

    @Test
    fun `startkurs fem dagar fore periodens start anvands men sex dagar ger otillrcklig data`() {
        val today = LocalDate.of(2026, 10, 20) // 3 mån = 2026-07-20
        val five = listOf(fundPrice("2026-07-15", 100.0), fundPrice("2026-10-20", 110.0))
        val six = listOf(fundPrice("2026-07-14", 100.0), fundPrice("2026-10-20", 110.0))

        assertEquals(0.10, FundAnalysisCalc.threeMonthReturn(today, five)!!, 1e-12)
        assertNull(FundAnalysisCalc.threeMonthReturn(today, six))
    }

    @Test
    fun `slutkursen ar senaste NAV pa eller fore idag och en gammal slutkurs markeras med datum (issue 198)`() {
        // Senaste NAV 2026-01-02, idag 2026-01-23; en framtida punkt får aldrig bli slutkurs.
        val history = listOf(
            fundPrice("2025-01-02", 100.0),
            fundPrice("2026-01-02", 125.0),
            fundPrice("2026-02-01", 999.0)
        )
        val today = LocalDate.of(2026, 1, 23)

        val oneYear = analyze198(history, today, firstPurchase = LocalDate.of(2025, 1, 2)).row(FundAnalysisCalc.Period.ETT_AR)

        // 1 år startar 2025-01-23: närmaste kurs 2025-01-02 ligger 21 dagar före, så otillräcklig data.
        assertNull(oneYear.fraction)
        val sincePurchase = analyze198(history, today, firstPurchase = LocalDate.of(2025, 1, 2)).row(FundAnalysisCalc.Period.SEDAN_KOP)
        assertEquals(0.25, sincePurchase.fraction!!, 1e-12)
        assertEquals(LocalDate.of(2025, 1, 2).toEpochDay(), sincePurchase.startEpochDay)
        assertEquals(LocalDate.of(2026, 1, 2).toEpochDay(), sincePurchase.endEpochDay)
        assertTrue(sincePurchase.endIsStale)
    }

    @Test
    fun `slutkurs fran i fredags ar inte inaktuell`() {
        val history = listOf(fundPrice("2025-10-17", 100.0), fundPrice("2026-10-16", 110.0)) // fredag
        val analysis = analyze198(history, LocalDate.of(2026, 10, 19), firstPurchase = LocalDate.of(2025, 10, 17))

        assertFalse(analysis.row(FundAnalysisCalc.Period.SEDAN_KOP).endIsStale)
    }

    @Test
    fun `julhelgen ar inte en inaktuell slutkurs men en vecka utan kurs ar det (issue 198)`() {
        val history = listOf(fundPrice("2024-12-30", 100.0), fundPrice("2025-12-23", 110.0)) // tis
        // Idag mån 2025-12-29 före 18: förväntad senaste NAV är fre 12-26, tre dagar efter.
        val christmas = analyze198(history, LocalDate.of(2025, 12, 29), firstPurchase = LocalDate.of(2024, 12, 30))
        assertFalse(christmas.row(FundAnalysisCalc.Period.YTD).endIsStale)
        assertFalse(christmas.keyFigures.currentNavIsStale)

        val later = analyze198(history, LocalDate.of(2026, 1, 5), firstPurchase = LocalDate.of(2024, 12, 30))
        assertTrue(later.keyFigures.currentNavIsStale)
        assertEquals(LocalDate.of(2025, 12, 23).toEpochDay(), later.keyFigures.currentNavEpochDay)
    }

    @Test
    fun `sedan kop utan kurs inom fem dagar fore kopet har kronor men varken procent eller CAGR (issue 198)`() {
        // Första kursen kommer 40 dagar efter köpet. Kronorna är GAV-vinsten (10 × 120 − 1 000 = 200).
        val today = LocalDate.of(2026, 7, 31)
        val purchase = today.minusYears(2)
        val history = listOf(
            FundPrice(fond.fundId, purchase.plusDays(40).toEpochDay(), 100.0),
            FundPrice(fond.fundId, today.toEpochDay(), 120.0)
        )
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1200.0)

        val analysis = FundAnalysisCalc.analyze(today, holding, emptyList(), history, purchase, 1200.0, null)!!

        val sinceBuy = analysis.row(FundAnalysisCalc.Period.SEDAN_KOP)
        assertEquals(200.0, sinceBuy.amount!!, 1e-9)
        assertNull(sinceBuy.fraction)
        assertNull(analysis.keyFigures.cagr)
    }

    @Test
    fun `periodraderna matchar fondmatte golden for periodgap (issue 198)`() {
        // tools/fondmatte/periodgap.json → funds.A.periods: lucka före 3 mån, senaste NAV 2026-10-12 mot idag 2026-10-20.
        val today = LocalDate.of(2026, 10, 20)
        val history = listOf("2025-10-01" to 95.0, "2025-10-20" to 100.0, "2025-12-31" to 110.0, "2026-03-02" to 120.0, "2026-09-15" to 130.0, "2026-10-12" to 140.0)
            .map { (day, nav) -> fundPrice(day, nav) }
        val buy = Transaction(fundId = fond.fundId, type = TransactionType.KOP, epochDay = LocalDate.of(2025, 10, 1).toEpochDay(), shares = 10.0, pricePerShare = 95.0)
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 950.0, currentValue = 1400.0)

        val analysis = FundAnalysisCalc.analyze(today, holding, listOf(buy), history, LocalDate.of(2025, 10, 1), 1400.0, null)!!

        for (period in listOf(FundAnalysisCalc.Period.TRE_MANADER, FundAnalysisCalc.Period.TRE_AR)) {
            assertNull(analysis.row(period).fraction)
            assertNull(analysis.row(period).amount)
        }
        val ytd = analysis.row(FundAnalysisCalc.Period.YTD)
        assertEquals(0.2727272727272727, ytd.fraction!!, 1e-12)
        assertEquals(300.0, ytd.amount!!, 1e-9)
        assertEquals(LocalDate.of(2025, 12, 31).toEpochDay(), ytd.startEpochDay)
        assertEquals(LocalDate.of(2026, 10, 12).toEpochDay(), ytd.endEpochDay)
        assertTrue(ytd.endIsStale)
        val oneYear = analysis.row(FundAnalysisCalc.Period.ETT_AR)
        assertEquals(0.3999999999999999, oneYear.fraction!!, 1e-12)
        assertEquals(400.0, oneYear.amount!!, 1e-9)
        val sinceBuy = analysis.row(FundAnalysisCalc.Period.SEDAN_KOP)
        assertEquals(0.4736842105263157, sinceBuy.fraction!!, 1e-12)
        assertEquals(LocalDate.of(2025, 10, 1).toEpochDay(), sinceBuy.startEpochDay)
        assertEquals(0.45742534226654086, analysis.keyFigures.cagr!!, 1e-12)
    }

    @Test
    fun `CAGR rknas mellan de tv NAV-dagarna, inte fram till idag (issue 198)`() {
        // Golden (fondmatte.py period_rows): NAV 100 -> 200 mellan 2024-01-02 och 2026-01-02 (731 dagar),
        // idag 2026-01-23: 2^(365,2425/731) − 1 = 0,4138683015541049. Mot idag (752 dagar) blev det 0,4034.
        val history = listOf(fundPrice("2024-01-02", 100.0), fundPrice("2026-01-02", 200.0))

        val analysis = analyze198(history, LocalDate.of(2026, 1, 23), firstPurchase = LocalDate.of(2024, 1, 2))

        assertEquals(0.4138683015541049, analysis.keyFigures.cagr!!, 1e-12)
    }

    @Test
    fun `GAV ar kvarvarande anskaffningsvarde per andel, jamfort mot aktuell NAV`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 800.0, currentValue = 1200.0)
        val history = longHistory()

        val analysis = FundAnalysisCalc.analyze(
            today = today,
            holding = holding,
            transactions = emptyList(),
            priceHistory = history,
            firstPurchaseDate = today.minusYears(2),
            portfolioTotalValue = 1200.0,
            otherHoldingsAverageThreeMonthReturn = null
        )!!

        assertEquals(80.0, analysis.keyFigures.gavPerShare, 1e-9) // 800 / 10
        assertEquals(120.0, analysis.keyFigures.currentNav, 1e-9)
        assertEquals(0.5, analysis.keyFigures.gavFraction!!, 1e-9) // (120-80)/80
    }

    @Test
    fun `portfoljandel ar innehavets varde delat pa portfoljens totala varde`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 800.0, currentValue = 1200.0)
        val analysis = FundAnalysisCalc.analyze(
            today = today,
            holding = holding,
            transactions = emptyList(),
            priceHistory = longHistory(),
            firstPurchaseDate = today.minusYears(2),
            portfolioTotalValue = 4800.0,
            otherHoldingsAverageThreeMonthReturn = null
        )!!

        assertEquals(0.25, analysis.keyFigures.portfolioShareFraction!!, 1e-9) // 1200/4800
    }

    @Test
    fun `portfoljandel ar null om portfoljens totala varde ar okant`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 800.0, currentValue = 1200.0)
        val analysis = FundAnalysisCalc.analyze(
            today = today,
            holding = holding,
            transactions = emptyList(),
            priceHistory = longHistory(),
            firstPurchaseDate = today.minusYears(2),
            portfolioTotalValue = 0.0,
            otherHoldingsAverageThreeMonthReturn = null
        )!!

        assertNull(analysis.keyFigures.portfolioShareFraction)
    }

    @Test
    fun `S1 avstand fran toppen - grader gron gul rod`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)

        fun historyWithHighAndCurrent(high: Double, current: Double) = listOf(
            price(daysAgo = 364, nav = high),
            price(daysAgo = 0, nav = current)
        )

        val gron = FundAnalysisCalc.analyze(
            today,
            holding,
            emptyList(),
            historyWithHighAndCurrent(100.0, 95.0),
            today.minusYears(2),
            950.0,
            null
        )!!.distanceFromHigh!!
        assertEquals(FundAnalysisCalc.SignalLevel.GRON, gron.level)

        val gul = FundAnalysisCalc.analyze(
            today,
            holding,
            emptyList(),
            historyWithHighAndCurrent(100.0, 89.0),
            today.minusYears(2),
            890.0,
            null
        )!!.distanceFromHigh!!
        assertEquals(FundAnalysisCalc.SignalLevel.GUL, gul.level)

        val rod = FundAnalysisCalc.analyze(
            today,
            holding,
            emptyList(),
            historyWithHighAndCurrent(100.0, 79.0),
            today.minusYears(2),
            790.0,
            null
        )!!.distanceFromHigh!!
        assertEquals(FundAnalysisCalc.SignalLevel.ROD, rod.level)
    }

    @Test
    fun `S1 ar null om historiken inte nar 52 veckor tillbaka`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        val history = (0..100L).map { price(it, nav = 100.0) }

        val analysis = FundAnalysisCalc.analyze(today, holding, emptyList(), history, today.minusDays(100), 1000.0, null)!!

        assertNull(analysis.distanceFromHigh)
    }

    @Test
    fun `S2 trend - NAV under 200-dagars snitt ger gul, annars gron`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        // Snitt över fönstret är 100 (två punkter: 100 för 290 dagar sedan, 100 idag) plus en
        // extra punkt som drar ner/upp aktuell kurs relativt snittet.
        val underSnitt = listOf(price(290, 100.0), price(100, 100.0), price(0, 80.0))
        val overSnitt = listOf(price(290, 100.0), price(100, 100.0), price(0, 130.0))

        val gul = FundAnalysisCalc.analyze(today, holding, emptyList(), underSnitt, today.minusYears(1), 800.0, null)!!.trend!!
        assertEquals(FundAnalysisCalc.SignalLevel.GUL, gul.level)

        val gron = FundAnalysisCalc.analyze(today, holding, emptyList(), overSnitt, today.minusYears(1), 1300.0, null)!!.trend!!
        assertEquals(FundAnalysisCalc.SignalLevel.GRON, gron.level)
    }

    @Test
    fun `S2 ar null om historiken inte nar 290 dagar tillbaka`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        val history = (0..100L).map { price(it, nav = 100.0) }

        val analysis = FundAnalysisCalc.analyze(today, holding, emptyList(), history, today.minusDays(100), 1000.0, null)!!

        assertNull(analysis.trend)
    }

    @Test
    fun `S2 raknar snittet over kalenderfonstret (290 dagar) till idag, kurser fore fonstret ingar inte`() {
        // Regression för utbrytningen till MovingAverageCalc (ANA-14): samma fönster som förut —
        // kurser i [idag − 290, idag], och en kurs på eller före fönstrets start krävs.
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        // 291 dagar gammal toppkurs ligger utanför fönstret: snittet är (100 + 100 + 110) / 3 ≈ 103,3
        // och dagens 110 ligger över det. Hade toppen räknats in hade S2 blivit gul.
        val history = listOf(price(291, 1000.0), price(290, 100.0), price(100, 100.0), price(0, 110.0))

        val trend = FundAnalysisCalc.analyze(today, holding, emptyList(), history, today.minusYears(1), 1100.0, null)!!.trend!!

        assertEquals(FundAnalysisCalc.SignalLevel.GRON, trend.level)
        assertEquals(
            310.0 / 3,
            MovingAverageCalc.averageAt(history.map { it.epochDay to it.nav }, today.toEpochDay())!!,
            1e-9
        )
    }

    @Test
    fun `S3 momentum - minst 5 procentenheter samre an portfoljsnittet ger gul`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        val history = listOf(price(92, 100.0), price(0, 90.0)) // -10 % senaste 3 mån

        val analysis = FundAnalysisCalc.analyze(
            today,
            holding,
            emptyList(),
            history,
            today.minusYears(1),
            900.0,
            otherHoldingsAverageThreeMonthReturn = 0.0 // portföljsnitt 0 %, denna fond -10 % -> -10 p.e.
        )!!

        assertEquals(FundAnalysisCalc.SignalLevel.GUL, analysis.momentum!!.level)
        assertEquals(-10.0, analysis.momentum!!.differencePp, 1e-6)
    }

    @Test
    fun `S3 ar null om portfoljsnittet saknas`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        val history = listOf(price(92, 100.0), price(0, 90.0))

        val analysis = FundAnalysisCalc.analyze(
            today,
            holding,
            emptyList(),
            history,
            today.minusYears(1),
            900.0,
            otherHoldingsAverageThreeMonthReturn = null
        )!!

        assertNull(analysis.momentum)
    }

    @Test
    fun `statussummering - noll gula ger gron`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        val history = longHistory() // platt/stigande, inga signaler triggade
        // Portföljsnittet sätts nära denna fonds egen 3-månadersutveckling så momentumsignalen
        // inte triggas av misstag — testet gäller S1/S2, inte S3.
        val analysis = FundAnalysisCalc.analyze(today, holding, emptyList(), history, today.minusYears(4), 1200.0, 0.0)!!

        assertEquals(FundAnalysisCalc.SignalLevel.GRON, analysis.status)
    }

    @Test
    fun `statussummering - tva gula ger rod`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        // Kraftigt fallande NAV: under 200-dagarssnitt (S2 gul) och 15 % under toppen (S1 gul,
        // inte röd), momentum uteslutet (null -> räknas inte).
        val history = (0..400L).map { daysAgo -> price(daysAgo, nav = 100.0 + daysAgo * 0.05) } // NAV var högre förr

        val analysis = FundAnalysisCalc.analyze(today, holding, emptyList(), history, today.minusYears(2), 1200.0, null)!!

        assertEquals(FundAnalysisCalc.SignalLevel.GUL, analysis.distanceFromHigh!!.level)
        assertEquals(FundAnalysisCalc.SignalLevel.GUL, analysis.trend!!.level)
        assertEquals(FundAnalysisCalc.SignalLevel.ROD, analysis.status)
    }

    @Test
    fun `analyze ar null om inga andelar kvarstar`() {
        val holding = Holding(fund = fond, netShares = 0.0, netInvested = 0.0)
        assertNull(FundAnalysisCalc.analyze(today, holding, emptyList(), longHistory(), today.minusYears(1), 0.0, null))
    }

    @Test
    fun `analyze ar null om ingen kurshistorik alls finns`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        assertNull(FundAnalysisCalc.analyze(today, holding, emptyList(), emptyList(), today.minusYears(1), 0.0, null))
    }

    @Test
    fun `threeMonthReturn och averageThreeMonthReturn`() {
        val history = listOf(price(92, 100.0), price(0, 110.0))
        assertEquals(0.10, FundAnalysisCalc.threeMonthReturn(today, history)!!, 1e-9)

        val average = FundAnalysisCalc.averageThreeMonthReturn(
            today,
            mapOf("A" to history, "B" to listOf(price(92, 100.0), price(0, 90.0)))
        )
        assertEquals(0.0, average!!, 1e-9) // (+10 % + -10 %) / 2
    }

    @Test
    fun `averageThreeMonthReturn ar null om ingen fond har tillrcklig historik`() {
        assertNull(FundAnalysisCalc.averageThreeMonthReturn(today, mapOf("A" to emptyList())))
    }

    @Test
    fun `analyzeAll analyserar varje innehav mot ovriga fonders snitt och utelamnar fonder utan kopdag`() {
        val fondB = Fund(fundId = "B", name = "Fond B")
        val fondC = Fund(fundId = "C", name = "Fond C")
        val firstPurchase = today.minusYears(1)
        // Positionens startdatum kommer ur Holding (issue #194); C saknar det och utelämnas.
        val holdingA = Holding(fund = fond, netShares = 10.0, netInvested = 500.0, currentValue = 1100.0, firstPurchaseEpochDay = firstPurchase.toEpochDay())
        val holdingB = Holding(fund = fondB, netShares = 10.0, netInvested = 900.0, currentValue = 900.0, firstPurchaseEpochDay = firstPurchase.toEpochDay())
        val holdingC = Holding(fund = fondC, netShares = 10.0, netInvested = 900.0, currentValue = 1000.0)
        val historyA = listOf(price(92, 100.0), price(0, 110.0))
        val historyB = listOf(price(92, 100.0).copy(fundId = "B"), price(0, 90.0).copy(fundId = "B"))
        val histories = mapOf(fond.fundId to historyA, "B" to historyB, "C" to historyB.map { it.copy(fundId = "C") })

        val result = FundAnalysisCalc.analyzeAll(
            today = today,
            holdings = listOf(holdingA, holdingB, holdingC),
            transactionsByFundId = emptyMap(),
            historyByFundId = histories
        )

        assertEquals(listOf(fond.fundId, "B"), result.keys.toList())
        val expectedA = FundAnalysisCalc.analyze(
            today,
            holdingA,
            emptyList(),
            historyA,
            firstPurchase,
            3000.0,
            FundAnalysisCalc.averageThreeMonthReturn(today, histories - fond.fundId)
        )
        assertEquals(expectedA, result[fond.fundId])
        val expectedB = FundAnalysisCalc.analyze(
            today,
            holdingB,
            emptyList(),
            historyB,
            firstPurchase,
            3000.0,
            FundAnalysisCalc.averageThreeMonthReturn(today, histories - "B")
        )
        assertEquals(expectedB, result["B"])
    }

    // --- Volatilitet och Sharpe (ANA-7, issue #24) ---

    private val riskHolding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0, currentValue = 1200.0)

    private fun analyzeWith(history: List<FundPrice>) = FundAnalysisCalc.analyze(
        today = today,
        holding = riskHolding,
        transactions = emptyList(),
        priceHistory = history,
        firstPurchaseDate = today.minusYears(1),
        portfolioTotalValue = 1200.0,
        otherHoldingsAverageThreeMonthReturn = null
    )!!

    /** [nDays]+1 punkter med kurser som växlar mellan [low] och [high] varje dag. */
    private fun alternating(nDays: Long, low: Double, high: Double) = (0..nDays).map { daysAgo -> price(daysAgo, if (daysAgo % 2 == 0L) high else low) }

    /** Dagliga kurser [fromDaysAgo]..0 med ett mönster som ger nollskild volatilitet. */
    private fun wavy(fromDaysAgo: Long, amplitude: Double = 1.0) = (0..fromDaysAgo).map { daysAgo -> price(daysAgo, 100.0 + amplitude * ((daysAgo * 7) % 13)) }

    @Test
    fun `platt kurshistorik ger nollvolatilitet och ingen Sharpe (ingen division med noll)`() {
        val flat = (0..400L).map { price(it, nav = 100.0) }
        val kf = analyzeWith(flat).keyFigures
        assertEquals(0.0, kf.annualizedVolatility!!, 1e-9)
        assertNull(kf.sharpeRatio) // vol = 0 → Sharpe odefinierad, null i stället för oändlig
        assertEquals(1, kf.riskWindowYears)
        // UI:t säger "ej definierad (ingen kursrörelse)", inte otillräcklig data.
        assertTrue(kf.sharpeUndefinedWithoutMovement)
    }

    @Test
    fun `for kort historik ger null for bade volatilitet och Sharpe`() {
        // 30 punkter → 29 dagsavkastningar: kortare än ett år och långt under täckningskravet (152).
        val short = (0..29L).map { price(it, nav = 100.0 + it) }
        val kf = analyzeWith(short).keyFigures
        assertNull(kf.annualizedVolatility)
        assertNull(kf.sharpeRatio)
        assertNull(kf.riskWindowYears)
        assertFalse(kf.sharpeUndefinedWithoutMovement)
    }

    @Test
    fun `historik under ett ar ger otillracklig data trots manga dagsavkastningar`() {
        // 364 dagliga punkter: gott om avkastningar, men ingen kurs på eller före senaste − 1 år (issue #195).
        val kf = analyzeWith(wavy(fromDaysAgo = 364)).keyFigures
        assertNull(kf.annualizedVolatility)
        assertNull(kf.sharpeRatio)
        assertNull(kf.riskWindowYears)
    }

    @Test
    fun `storre svangningar ger hogre volatilitet`() {
        val calm = analyzeWith(alternating(400, low = 99.0, high = 101.0)).keyFigures.annualizedVolatility!!
        val wild = analyzeWith(alternating(400, low = 90.0, high = 110.0)).keyFigures.annualizedVolatility!!
        assertTrue(wild > calm)
    }

    @Test
    fun `stigande kurshistorik ger positiv Sharpe`() {
        // Stadig uppgång — positiv medelavkastning och positiv (nollskild) volatilitet.
        val rising = (0..400L).map { daysAgo -> price(daysAgo, nav = 120.0 - daysAgo * 0.05 + (daysAgo % 3) * 0.1) }
        val kf = analyzeWith(rising).keyFigures
        assertTrue(kf.annualizedVolatility!! > 0.0)
        assertTrue(kf.sharpeRatio!! > 0.0)
    }

    @Test
    fun `historik over tre ar raknas pa de senaste tre aren och aldre kurser paverkar inte`() {
        // Fyra år: det äldsta året är vilt (en krasch), de senaste tre lugna. Fönstret ska bara se de tre åren.
        val threeYearsAgo = today.minusYears(3).toEpochDay()
        val calm = wavy(fromDaysAgo = today.toEpochDay() - threeYearsAgo)
        val crash = (today.toEpochDay() - threeYearsAgo + 1..today.toEpochDay() - threeYearsAgo + 365)
            .map { daysAgo -> price(daysAgo, if (daysAgo % 2 == 0L) 40.0 else 160.0) }

        val withCrash = analyzeWith(calm + crash).keyFigures
        val onlyWindow = analyzeWith(calm).keyFigures

        assertEquals(3, withCrash.riskWindowYears)
        assertEquals(3, onlyWindow.riskWindowYears)
        assertEquals(onlyWindow.annualizedVolatility!!, withCrash.annualizedVolatility!!, 1e-12)
        assertEquals(onlyWindow.sharpeRatio!!, withCrash.sharpeRatio!!, 1e-12)
    }

    @Test
    fun `historik mellan ett och tre ar raknas pa senaste aret`() {
        // Två år: bara det senaste året (från kursen på senaste − 1 år) räknas, oavsett anroparens lista.
        val oneYearAgo = today.minusYears(1).toEpochDay()
        val lastYear = wavy(fromDaysAgo = today.toEpochDay() - oneYearAgo)
        val yearBefore = (today.toEpochDay() - oneYearAgo + 1..730L).map { daysAgo -> price(daysAgo, if (daysAgo % 2 == 0L) 50.0 else 150.0) }

        val twoYears = analyzeWith(lastYear + yearBefore).keyFigures
        val oneYear = analyzeWith(lastYear).keyFigures

        assertEquals(1, twoYears.riskWindowYears)
        assertEquals(1, oneYear.riskWindowYears)
        assertEquals(oneYear.annualizedVolatility!!, twoYears.annualizedVolatility!!, 1e-12)
        assertEquals(oneYear.sharpeRatio!!, twoYears.sharpeRatio!!, 1e-12)
    }

    @Test
    fun `fonstret raknas fran senaste kurs inte fran idag`() {
        // Senaste kursen är tio dagar gammal och historiken når exakt ett år bakåt från den.
        val latest = today.minusDays(10)
        val days = latest.toEpochDay() - latest.minusYears(1).toEpochDay()
        val history = (0..days).map { back -> price(10 + back, 100.0 + (back * 7) % 13) }

        val kf = analyzeWith(history).keyFigures

        assertEquals(1, kf.riskWindowYears)
        assertTrue(kf.annualizedVolatility!! > 0.0)
        // En dag kortare: ingen kurs på eller före senaste − 1 år → otillräcklig data.
        assertNull(analyzeWith(history.dropLast(1)).keyFigures.annualizedVolatility)
    }

    /**
     * Daglig historik 400 dagar bakåt utan kurser dagarna [gapDays] (räknat från idag); kurserna
     * efter luckan är multiplicerade med [jumpAfterGap] — ett hopp som bara syns i paret över luckan.
     */
    private fun withGap(gapDays: LongRange, jumpAfterGap: Double) = alternating(400, low = 99.0, high = 101.0)
        .filter { LocalDate.ofEpochDay(it.epochDay) !in gapDays.map { d -> today.minusDays(d) } }
        .map { if (it.epochDay > today.minusDays(gapDays.first).toEpochDay()) it.copy(nav = it.nav * jumpAfterGap) else it }

    @Test
    fun `kurspar med langre lucka an fem dagar hoppas over`() {
        // Kurs 110 dagar sedan och sedan 100 dagar sedan: 10 dagar isär. Hoppet på +100 % över
        // luckan ska inte räknas som en dagsavkastning — volatiliteten blir densamma som utan hopp.
        val gap = 101L..109L
        val withJump = analyzeWith(withGap(gap, jumpAfterGap = 2.0)).keyFigures
        val withoutJump = analyzeWith(withGap(gap, jumpAfterGap = 1.0)).keyFigures

        assertEquals(withoutJump.annualizedVolatility!!, withJump.annualizedVolatility!!, 1e-12)
        // Sharpe räknas på fönstrets totala utveckling, så hoppet över luckan syns i täljaren.
        assertTrue(withJump.sharpeRatio!! > withoutJump.sharpeRatio!!)
    }

    @Test
    fun `fall over en overhoppad lucka sanker Sharpe men inte volatiliteten`() {
        // Kursen faller 30 % mellan 110 och 100 dagar sedan (10 dagar isär, paret hoppas över).
        // Med medel(dagsavkastning) × 252 i täljaren hade fallet försvunnit ur Sharpe.
        val gap = 101L..109L
        val fallen = withGap(gap, jumpAfterGap = 0.7)
        val kf = analyzeWith(fallen).keyFigures
        val flat = analyzeWith(withGap(gap, jumpAfterGap = 1.0)).keyFigures

        assertEquals(flat.annualizedVolatility!!, kf.annualizedVolatility!!, 1e-12)
        assertTrue(kf.sharpeRatio!! < 0.0)
        assertTrue(kf.sharpeRatio!! < flat.sharpeRatio!!)
        // Täljaren = (NAV_slut / NAV_ankare)^(365,2425 / dagar) − 1; ankaret är kursen på senaste − 1 år.
        val anchor = fallen.filter { it.epochDay <= today.minusYears(1).toEpochDay() }.maxBy { it.epochDay }
        val last = fallen.maxBy { it.epochDay }
        val days = (last.epochDay - anchor.epochDay).toDouble()
        val expected = (Math.pow(last.nav / anchor.nav, 365.2425 / days) - 1.0) / kf.annualizedVolatility!!
        assertEquals(expected, kf.sharpeRatio!!, 1e-12)
    }

    @Test
    fun `kurspar fem dagar isar raknas som en handelsdag (helg plus helgdag)`() {
        // Påsk: torsdag → tisdag = 5 dagar. Paret räknas, så hoppet över luckan syns i volatiliteten.
        val gap = 101L..104L
        val withJump = analyzeWith(withGap(gap, jumpAfterGap = 2.0)).keyFigures
        val withoutJump = analyzeWith(withGap(gap, jumpAfterGap = 1.0)).keyFigures

        assertTrue(withJump.annualizedVolatility!! > withoutJump.annualizedVolatility!! * 2)
    }

    @Test
    fun `gles historik inom fonstret ger otillracklig data`() {
        // Veckokurser i tre år: inget par ligger högst fem dagar isär, så det finns inga dagsavkastningar.
        val weekly = (0..(3 * 366L) step 7).map { daysAgo -> price(daysAgo, 100.0 + (daysAgo % 13)) }
        val kf = analyzeWith(weekly).keyFigures
        assertNull(kf.annualizedVolatility)
        assertNull(kf.sharpeRatio)
        assertNull(kf.riskWindowYears)
    }

    @Test
    fun `tackningskravet ar 60 procent av 252 handelsdagar per ar`() {
        assertEquals(152, FundAnalysisCalc.minWindowReturns(1))
        assertEquals(454, FundAnalysisCalc.minWindowReturns(3))
    }

    @Test
    fun `glest trearsfonster faller tillbaka till ett ar`() {
        // Veckokurser de två äldsta åren (inga par högst fem dagar isär) och dagliga det senaste
        // året: treårsfönstret har bara ~365 giltiga avkastningar (< 454) och får inte heta "3 år".
        val oneYearBack = today.toEpochDay() - today.minusYears(1).toEpochDay()
        val daily = wavy(fromDaysAgo = oneYearBack)
        // Veckopunkterna når förbi treårsstarten, så ankaret finns (högst 5 dagar före) och inget glapp
        // överstiger 31 dagar — det är bara täckningen som fäller treårsfönstret.
        val weekly = (oneYearBack + 7..3 * 366L + 7 step 7).map { daysAgo -> price(daysAgo, 100.0 + (daysAgo % 11)) }

        val sparse = analyzeWith(daily + weekly).keyFigures
        val onlyDaily = analyzeWith(daily).keyFigures

        assertEquals(1, sparse.riskWindowYears)
        assertEquals(onlyDaily.annualizedVolatility!!, sparse.annualizedVolatility!!, 1e-12)
        assertEquals(onlyDaily.sharpeRatio!!, sparse.sharpeRatio!!, 1e-12)
    }

    @Test
    fun `ettarsfonster under tackningskravet ger otillracklig data`() {
        // Ankare exakt ett år bakåt, veckokurser fram till n dagar sedan (7 dagar isär: inga
        // dagsavkastningar men inget glapp över 31 dagar) och sedan n dagliga avkastningar: 152 räcker, 151 inte.
        val oneYearBack = today.toEpochDay() - today.minusYears(1).toEpochDay()
        fun history(n: Long) = (oneYearBack downTo n + 6 step 7).map { price(it, 100.0 + (it % 11)) } + wavy(fromDaysAgo = n)

        assertEquals(1, analyzeWith(history(152)).keyFigures.riskWindowYears)
        val kf = analyzeWith(history(151)).keyFigures
        assertNull(kf.riskWindowYears)
        assertNull(kf.annualizedVolatility)
        assertNull(kf.sharpeRatio)
    }

    @Test
    fun `ankaret far ligga hogst fem dagar fore fonstrets start`() {
        // Daglig historik det senaste året utom startdagen, plus en ensam äldre kurs som ankare.
        val oneYearBack = today.toEpochDay() - today.minusYears(1).toEpochDay()
        fun history(anchorBefore: Long) = wavy(fromDaysAgo = oneYearBack - 1) + price(oneYearBack + anchorBefore, 100.0)

        assertEquals(1, analyzeWith(history(5)).keyFigures.riskWindowYears)
        val kf = analyzeWith(history(6)).keyFigures
        assertNull(kf.riskWindowYears)
        assertNull(kf.annualizedVolatility)
    }

    @Test
    fun `glapp over 31 dagar i fonstret gor det ogiltigt`() {
        // Daglig historik med ett hål mitt i året: 31 dagar mellan två punkter går, 32 inte.
        val oneYearBack = today.toEpochDay() - today.minusYears(1).toEpochDay()
        fun history(gap: Long) = wavy(fromDaysAgo = oneYearBack).filter { daysAgo(it) !in 150L until 150L + gap - 1 }

        assertEquals(1, analyzeWith(history(31)).keyFigures.riskWindowYears)
        assertNull(analyzeWith(history(32)).keyFigures.riskWindowYears)
    }

    @Test
    fun `glapp i trearsfonstret faller tillbaka till ett ar`() {
        // Tre års daglig historik med ett hål på 40 dagar för två år sedan: 3 år ogiltigt, 1 år gäller.
        val threeYearsBack = today.toEpochDay() - today.minusYears(3).toEpochDay()
        val history = wavy(fromDaysAgo = threeYearsBack).filter { daysAgo(it) !in 700L until 739L }

        assertEquals(1, analyzeWith(history).keyFigures.riskWindowYears)
    }

    private fun daysAgo(p: FundPrice) = today.toEpochDay() - p.epochDay

    @Test
    fun `volatilitet och Sharpe matchar fondmatte golden for riskfonster`() {
        // tools/fondmatte/riskfonster.json → funds.A: vardagar 2024-03-04–2026-03-13 utan påskens
        // helgdagar 2025 och utan 7–18 juli 2025; kurs = 100 + 0,05·i + 0,3·(7·i mod 13).
        val end = LocalDate.of(2026, 3, 13)
        val skipped = setOf(LocalDate.of(2025, 4, 18), LocalDate.of(2025, 4, 21)) +
            (0L until 12L).map { LocalDate.of(2025, 7, 7).plusDays(it) }
        val history = mutableListOf<FundPrice>()
        var day = LocalDate.of(2024, 3, 4)
        while (!day.isAfter(end)) {
            if (day.dayOfWeek != java.time.DayOfWeek.SATURDAY && day.dayOfWeek != java.time.DayOfWeek.SUNDAY && day !in skipped) {
                val i = history.size
                history += FundPrice(fundId = fond.fundId, epochDay = day.toEpochDay(), nav = 100.0 + 0.05 * i + 0.3 * ((i * 7) % 13))
            }
            day = day.plusDays(1)
        }

        val kf = FundAnalysisCalc.analyze(
            today = end,
            holding = riskHolding,
            transactions = emptyList(),
            priceHistory = history,
            firstPurchaseDate = LocalDate.of(2024, 3, 4),
            portfolioTotalValue = 1200.0,
            otherHoldingsAverageThreeMonthReturn = null
        )!!.keyFigures

        assertEquals(1, kf.riskWindowYears)
        assertEquals(0.2555423226530543, kf.annualizedVolatility!!, 1e-9)
        assertEquals(0.43567903130850577, kf.sharpeRatio!!, 1e-9)
    }

    // --- Vinstsignal (S4, ANA-8, issue #26) ---

    @Test
    fun `S4 vinstsignal triggas vid gavFraction minst plus 50 procent`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 800.0, currentValue = 1200.0)
        val analysis = FundAnalysisCalc.analyze(
            today,
            holding,
            emptyList(),
            listOf(price(daysAgo = 0, nav = 120.0)),
            today.minusYears(2),
            1200.0,
            null
        )!!

        assertEquals(0.5, analysis.profitTake!!.gainFraction, 1e-9) // (120-80)/80
        assertTrue(analysis.profitTake!!.triggered)
    }

    @Test
    fun `S4 vinstsignal triggas inte strax under plus 50 procent`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1490.0)
        val analysis = FundAnalysisCalc.analyze(
            today,
            holding,
            emptyList(),
            listOf(price(daysAgo = 0, nav = 149.0)),
            today.minusYears(2),
            1490.0,
            null
        )!!

        assertFalse(analysis.profitTake!!.triggered)
    }

    @Test
    fun `S4 ar null nar gavFraction ar null (samma gate som nyckeltalet)`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 0.0, currentValue = 500.0)
        val analysis = FundAnalysisCalc.analyze(
            today,
            holding,
            emptyList(),
            listOf(price(daysAgo = 0, nav = 50.0)),
            today.minusYears(2),
            500.0,
            null
        )!!

        assertNull(analysis.keyFigures.gavFraction)
        assertNull(analysis.profitTake)
    }

    @Test
    fun `S4 paverkar inte den sammanslagna statusen`() {
        // Samma historik/portfölj-parametrar som "statussummering - noll gula ger gron", bara
        // ett lägre netInvested så vinstsignalen (S4) också triggas — status ska ändå bli grön,
        // eftersom S4 medvetet inte deltar i combineStatus.
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 400.0, currentValue = 1200.0)
        val analysis = FundAnalysisCalc.analyze(today, holding, emptyList(), longHistory(), today.minusYears(4), 1200.0, 0.0)!!

        assertTrue(analysis.profitTake!!.triggered)
        assertEquals(FundAnalysisCalc.SignalLevel.GRON, analysis.status)
    }

    // --- Periodradernas kronor på ägda andelar (issue #193) ---

    private fun kop(daysAgo: Long, shares: Double, nav: Double, fee: Double = 0.0, fundId: String = fond.fundId) = Transaction(fundId = fundId, type = TransactionType.KOP, epochDay = today.minusDays(daysAgo).toEpochDay(), shares = shares, pricePerShare = nav, fee = fee)

    private fun salj(daysAgo: Long, shares: Double, nav: Double, fee: Double = 0.0) = Transaction(fundId = fond.fundId, type = TransactionType.SALJ, epochDay = today.minusDays(daysAgo).toEpochDay(), shares = shares, pricePerShare = nav, fee = fee)

    private fun FundAnalysisCalc.Analysis.row(period: FundAnalysisCalc.Period) = keyFigures.periodReturns.first { it.period == period }

    /** 3 mån-raden när kursen var 80 för ett år och 92 dagar sedan, 100 för 30 dagar sedan och 120 idag. */
    private fun threeMonthsWithTradeThirtyDaysAgo(holding: Holding, transactions: List<Transaction>) = FundAnalysisCalc.analyze(
        today,
        holding,
        transactions,
        listOf(price(daysAgo = 365, nav = 80.0), price(daysAgo = 92, nav = 80.0), price(daysAgo = 30, nav = 100.0), price(daysAgo = 0, nav = 120.0)),
        today.minusDays(365),
        holding.currentValue!!,
        null
    )!!.row(FundAnalysisCalc.Period.TRE_MANADER)

    @Test
    fun `tre manader raknar kronorna pa agda andelar nar ett kop gjorts inom perioden`() {
        // 10 andelar sedan ett år, 10 till köpta för 30 dagar sedan à 100. 3 mån: 80 → 100 på 10
        // andelar och 100 → 120 på 20 = 600 kr, inte 20 × (120 − 80) = 800 kr. Procenten är fondens.
        val holding = Holding(fund = fond, netShares = 20.0, netInvested = 1800.0, currentValue = 2400.0)
        val transactions = listOf(kop(daysAgo = 365, shares = 10.0, nav = 80.0), kop(daysAgo = 30, shares = 10.0, nav = 100.0, fee = 39.0))

        val threeMonths = threeMonthsWithTradeThirtyDaysAgo(holding, transactions)
        assertEquals(600.0, threeMonths.amount!!, 1e-9)
        assertEquals(0.5, threeMonths.fraction!!, 1e-9)
    }

    @Test
    fun `tre manader raknar kronorna pa salda andelar fram till saljdagen`() {
        // 20 andelar sedan ett år, 10 sålda för 30 dagar sedan à 100: 20 × 20 + 10 × 20 = 600 kr,
        // inte dagens 10 × (120 − 80) = 400 kr.
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 800.0, currentValue = 1200.0)
        val transactions = listOf(kop(daysAgo = 365, shares = 20.0, nav = 80.0), salj(daysAgo = 30, shares = 10.0, nav = 100.0, fee = 39.0))

        val threeMonths = threeMonthsWithTradeThirtyDaysAgo(holding, transactions)
        assertEquals(600.0, threeMonths.amount!!, 1e-9)
        assertEquals(0.5, threeMonths.fraction!!, 1e-9)
    }

    @Test
    fun `sedan kop i kronor ar positionens vinst mot anskaffningsvardet`() {
        // Samma tal som GAV-raden: 20 × 120 − 1 800 = 600 kr, även om kursen sedan första köpet
        // (80 → 120) gånger dagens andelar vore 800 kr. Procenten är fondens kursutveckling.
        val holding = Holding(fund = fond, netShares = 20.0, netInvested = 1800.0, currentValue = 2400.0)
        val transactions = listOf(kop(daysAgo = 365, shares = 10.0, nav = 80.0), kop(daysAgo = 30, shares = 10.0, nav = 100.0))
        val history = listOf(price(daysAgo = 365, nav = 80.0), price(daysAgo = 30, nav = 100.0), price(daysAgo = 0, nav = 120.0))

        val sedanKop = FundAnalysisCalc.analyze(today, holding, transactions, history, today.minusDays(365), 2400.0, null)!!
            .row(FundAnalysisCalc.Period.SEDAN_KOP)

        assertEquals(600.0, sedanKop.amount!!, 1e-9)
        assertEquals(0.5, sedanKop.fraction!!, 1e-9)
    }

    @Test
    fun `sedan kop i kronor finns aven utan kurshistorik vid kopet`() {
        // Bara dagens kurs är känd: procenten saknas (ingen kurs vid köpet), men vinsten mot
        // anskaffningsvärdet går ändå att räkna — 10 × 120 − 1 000 = 200 kr.
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1200.0)

        val sedanKop = FundAnalysisCalc.analyze(today, holding, listOf(kop(daysAgo = 730, shares = 10.0, nav = 100.0)), listOf(price(daysAgo = 0, nav = 120.0)), today.minusYears(2), 1200.0, null)!!
            .row(FundAnalysisCalc.Period.SEDAN_KOP)

        assertNull(sedanKop.fraction)
        assertEquals(200.0, sedanKop.amount!!, 1e-9)
    }

    @Test
    fun `period utan startkurs har varken kronor eller procent aven med transaktioner`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1200.0)
        val history = listOf(price(daysAgo = 30, nav = 100.0), price(daysAgo = 0, nav = 120.0))

        val ettAr = FundAnalysisCalc.analyze(today, holding, listOf(kop(daysAgo = 30, shares = 10.0, nav = 100.0)), history, today.minusDays(30), 1200.0, null)!!
            .row(FundAnalysisCalc.Period.ETT_AR)

        assertNull(ettAr.amount)
        assertNull(ettAr.fraction)
    }

    @Test
    fun `analyzeAll raknar periodradernas kronor med varje fonds egna transaktioner`() {
        val fondB = Fund(fundId = "B", name = "Fond B")
        val firstPurchase = today.minusYears(1)
        val holdingA = Holding(fund = fond, netShares = 10.0, netInvested = 1000.0, currentValue = 1100.0, firstPurchaseEpochDay = firstPurchase.toEpochDay())
        val holdingB = Holding(fund = fondB, netShares = 10.0, netInvested = 1000.0, currentValue = 900.0, firstPurchaseEpochDay = firstPurchase.toEpochDay())
        val histories = mapOf(
            fond.fundId to listOf(price(92, 100.0), price(0, 110.0)),
            "B" to listOf(price(92, 100.0).copy(fundId = "B"), price(0, 90.0).copy(fundId = "B"))
        )
        // A har sitt köp i kartan, B saknas där: B:s kronor räknas inte på A:s transaktioner.
        val transactions = mapOf(fond.fundId to listOf(kop(daysAgo = 365, shares = 10.0, nav = 100.0)))

        val result = FundAnalysisCalc.analyzeAll(today, listOf(holdingA, holdingB), transactions, histories)

        assertEquals(100.0, result.getValue(fond.fundId).row(FundAnalysisCalc.Period.TRE_MANADER).amount!!, 1e-9) // 10 × (110 − 100)
        // Utan egna transaktioner har inga andelar ägts: 0 kr, procenten är ändå fondens (−10 %).
        assertEquals(0.0, result.getValue("B").row(FundAnalysisCalc.Period.TRE_MANADER).amount!!, 0.0)
        assertEquals(-0.1, result.getValue("B").row(FundAnalysisCalc.Period.TRE_MANADER).fraction!!, 1e-9)
    }

    @Test
    fun `periodraderna matchar fondmatte golden for periodkop`() {
        // tools/fondmatte/periodkop.json → funds.A.periods (3m, since_purchase, ytd/1y/3y null).
        val fixture = PeriodkopFixture
        val analysis = FundAnalysisCalc.analyze(
            today = fixture.endDate,
            holding = fixture.holding,
            transactions = fixture.transactions,
            priceHistory = fixture.history,
            firstPurchaseDate = java.time.LocalDate.ofEpochDay(fixture.holding.firstPurchaseEpochDay!!),
            portfolioTotalValue = fixture.holding.currentValue!!,
            otherHoldingsAverageThreeMonthReturn = null
        )!!

        assertEquals(933.0000000000002, analysis.row(FundAnalysisCalc.Period.TRE_MANADER).amount!!, 1e-9)
        assertEquals(0.12448979591836729, analysis.row(FundAnalysisCalc.Period.TRE_MANADER).fraction!!, 1e-9)
        assertEquals(856.0, analysis.row(FundAnalysisCalc.Period.SEDAN_KOP).amount!!, 1e-9)
        assertEquals(0.08571428571428563, analysis.row(FundAnalysisCalc.Period.SEDAN_KOP).fraction!!, 1e-9)
        for (period in listOf(FundAnalysisCalc.Period.YTD, FundAnalysisCalc.Period.ETT_AR, FundAnalysisCalc.Period.TRE_AR)) {
            assertNull(analysis.row(period).amount)
            assertNull(analysis.row(period).fraction)
        }
    }

    @Test
    fun `analysHistoryStart ar forsta transaktionen eller tre ar bakat, det som ligger tidigast`() {
        val lookbackStart = today.minusDays(FundAnalysisCalc.ANALYSIS_LOOKBACK_DAYS)
        // Köpt för tre månader sedan: tre år bakåt vinner (S2 behöver 290 dagar, #195 tre år).
        assertEquals(lookbackStart, FundAnalysisCalc.analysisHistoryStart(today.minusMonths(3), today))
        // Köpt för fem år sedan: köpet vinner, sedan köp och CAGR behöver hela innehavstiden.
        assertEquals(today.minusYears(5), FundAnalysisCalc.analysisHistoryStart(today.minusYears(5), today))
        // Bevakad fond utan transaktion: lookbacken ensam.
        assertEquals(lookbackStart, FundAnalysisCalc.analysisHistoryStart(null, today))
    }

    @Test
    fun `lookbacken tacker treårsperioden och 200-dagarssnittet med marginal`() {
        // En kurs på eller före "3 år"-periodens startdag måste rymmas även över ett skottår
        // och en långhelg — och snittets 290 dagar med råge.
        assertTrue(FundAnalysisCalc.ANALYSIS_LOOKBACK_DAYS >= java.time.temporal.ChronoUnit.DAYS.between(today.minusYears(3), today) + 7)
        // Treårsfönstret räknas från senaste NAV: en NAV som är fem veckor gammal ska också rymmas.
        assertTrue(FundAnalysisCalc.ANALYSIS_LOOKBACK_DAYS >= java.time.temporal.ChronoUnit.DAYS.between(today.minusYears(3), today) + 35)
        assertTrue(FundAnalysisCalc.ANALYSIS_LOOKBACK_DAYS > MovingAverageCalc.WINDOW_DAYS)
    }

    @Test
    fun `nyss kopt fond med tre veckor gammal NAV far anda treårsfonstret`() {
        // Vardagskurser fyra år bakåt men senaste NAV tre veckor gammal (källan släpar). Historiken
        // klipps som i appen vid analysisHistoryStart för en fond köpt för tre månader sedan.
        val start = FundAnalysisCalc.analysisHistoryStart(today.minusMonths(3), today)
        val history = (21L..4 * 366L)
            .map { today.minusDays(it) }
            .filter { it.dayOfWeek != java.time.DayOfWeek.SATURDAY && it.dayOfWeek != java.time.DayOfWeek.SUNDAY && !it.isBefore(start) }
            .map { price(today.toEpochDay() - it.toEpochDay(), 100.0 + (it.toEpochDay() * 7) % 13) }

        assertEquals(3, analyzeWith(history).keyFigures.riskWindowYears)
    }

    @Test
    fun `fond kopt for tre manader sedan med lang historik far S2 men kronor och sedan kop fran kopet`() {
        // Issue #214: analyshistoriken når två år bakåt fast fonden köptes för 91 dagar sedan.
        val history = longHistory().filter { it.epochDay >= today.minusYears(2).toEpochDay() }
        val buy = kop(daysAgo = 91, shares = 10.0, nav = navDaysAgo(91))
        val holding = PortfolioCalc.withCurrentValue(PortfolioCalc.computeHoldings(listOf(fond), listOf(buy)), mapOf(fond.fundId to history.maxBy { it.epochDay })).single()

        val analysis = FundAnalysisCalc.analyzeAll(today, listOf(holding), mapOf(fond.fundId to listOf(buy)), mapOf(fond.fundId to history)).getValue(fond.fundId)

        // S1 och S2 handlar om fondens kurs och beräknas trots att innehavet är tre månader.
        assertTrue(analysis.trend != null)
        assertTrue(analysis.distanceFromHigh != null)
        // Periodprocenten är fondens kursutveckling under hela perioden (ANA-1) …
        val oneYear = analysis.row(FundAnalysisCalc.Period.ETT_AR)
        assertEquals((120.0 - navDaysAgo(365)) / navDaysAgo(365), oneYear.fraction!!, 1e-9)
        // … men kronorna räknar bara de andelar som faktiskt ägts, alltså från köpet (#193).
        val sinceBuyValueChange = 10.0 * (120.0 - navDaysAgo(91))
        assertEquals(sinceBuyValueChange, oneYear.amount!!, 1e-9)
        // Tre år når historiken (två år) inte: otillräcklig data, inte gissat (ANA-4).
        assertNull(analysis.row(FundAnalysisCalc.Period.TRE_AR).fraction)
        // "Sedan köp" och GAV utgår från köpet, CAGR saknas för ett innehav yngre än ett år.
        val sinceBuy = analysis.row(FundAnalysisCalc.Period.SEDAN_KOP)
        assertEquals((120.0 - navDaysAgo(91)) / navDaysAgo(91), sinceBuy.fraction!!, 1e-9)
        assertEquals(sinceBuyValueChange, sinceBuy.amount!!, 1e-9)
        assertEquals(navDaysAgo(91), analysis.keyFigures.gavPerShare, 1e-9)
        assertNull(analysis.keyFigures.cagr)
    }

    /** NAV i [longHistory] för en given dag. */
    private fun navDaysAgo(daysAgo: Long): Double = 120.0 - daysAgo * (70.0 / 1460.0)
}
