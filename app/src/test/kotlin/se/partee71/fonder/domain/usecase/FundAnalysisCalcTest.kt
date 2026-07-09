package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import java.time.LocalDate

class FundAnalysisCalcTest {

    private val fond = Fund(fundId = "SHB0000442", name = "Fond A")
    private val today = LocalDate.of(2026, 7, 31)

    private fun price(daysAgo: Long, nav: Double) =
        FundPrice(fundId = fond.fundId, epochDay = today.minusDays(daysAgo).toEpochDay(), nav = nav)

    /** Daglig historik 4 år tillbaka, NAV stiger linjärt från 50 till 120 (dagens kurs). */
    private fun longHistory(): List<FundPrice> =
        (0..1460L step 1).map { daysAgo -> price(daysAgo, nav = 120.0 - daysAgo * (70.0 / 1460.0)) }

    @Test
    fun `periodavkastning berknas fran senaste kanda NAV pa eller fore periodens start`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0, currentValue = 1200.0)
        val history = longHistory()
        val firstPurchase = today.minusYears(2)

        val analysis = FundAnalysisCalc.analyze(
            today = today,
            holding = holding,
            priceHistory = history,
            firstPurchaseDate = firstPurchase,
            portfolioTotalValue = 1200.0,
            otherHoldingsAverageThreeMonthReturn = null,
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
            priceHistory = history,
            firstPurchaseDate = today.minusDays(30),
            portfolioTotalValue = 1200.0,
            otherHoldingsAverageThreeMonthReturn = null,
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
            priceHistory = history,
            firstPurchaseDate = today.minusMonths(6),
            portfolioTotalValue = 1200.0,
            otherHoldingsAverageThreeMonthReturn = null,
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
            priceHistory = history,
            firstPurchaseDate = firstPurchase,
            portfolioTotalValue = 2000.0,
            otherHoldingsAverageThreeMonthReturn = null,
        )!!

        assertEquals(kotlin.math.sqrt(2.0) - 1.0, analysis.keyFigures.cagr!!, 1e-3)
    }

    @Test
    fun `GAV ar kvarvarande anskaffningsvarde per andel, jamfort mot aktuell NAV`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 800.0, currentValue = 1200.0)
        val history = longHistory()

        val analysis = FundAnalysisCalc.analyze(
            today = today,
            holding = holding,
            priceHistory = history,
            firstPurchaseDate = today.minusYears(2),
            portfolioTotalValue = 1200.0,
            otherHoldingsAverageThreeMonthReturn = null,
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
            priceHistory = longHistory(),
            firstPurchaseDate = today.minusYears(2),
            portfolioTotalValue = 4800.0,
            otherHoldingsAverageThreeMonthReturn = null,
        )!!

        assertEquals(0.25, analysis.keyFigures.portfolioShareFraction!!, 1e-9) // 1200/4800
    }

    @Test
    fun `portfoljandel ar null om portfoljens totala varde ar okant`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 800.0, currentValue = 1200.0)
        val analysis = FundAnalysisCalc.analyze(
            today = today,
            holding = holding,
            priceHistory = longHistory(),
            firstPurchaseDate = today.minusYears(2),
            portfolioTotalValue = 0.0,
            otherHoldingsAverageThreeMonthReturn = null,
        )!!

        assertNull(analysis.keyFigures.portfolioShareFraction)
    }

    @Test
    fun `S1 avstand fran toppen - grader gron gul rod`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)

        fun historyWithHighAndCurrent(high: Double, current: Double) = listOf(
            price(daysAgo = 364, nav = high),
            price(daysAgo = 0, nav = current),
        )

        val gron = FundAnalysisCalc.analyze(
            today, holding, historyWithHighAndCurrent(100.0, 95.0), today.minusYears(2), 950.0, null,
        )!!.distanceFromHigh!!
        assertEquals(FundAnalysisCalc.SignalLevel.GRON, gron.level)

        val gul = FundAnalysisCalc.analyze(
            today, holding, historyWithHighAndCurrent(100.0, 89.0), today.minusYears(2), 890.0, null,
        )!!.distanceFromHigh!!
        assertEquals(FundAnalysisCalc.SignalLevel.GUL, gul.level)

        val rod = FundAnalysisCalc.analyze(
            today, holding, historyWithHighAndCurrent(100.0, 79.0), today.minusYears(2), 790.0, null,
        )!!.distanceFromHigh!!
        assertEquals(FundAnalysisCalc.SignalLevel.ROD, rod.level)
    }

    @Test
    fun `S1 ar null om historiken inte nar 52 veckor tillbaka`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        val history = (0..100L).map { price(it, nav = 100.0) }

        val analysis = FundAnalysisCalc.analyze(today, holding, history, today.minusDays(100), 1000.0, null)!!

        assertNull(analysis.distanceFromHigh)
    }

    @Test
    fun `S2 trend - NAV under 200-dagars snitt ger gul, annars gron`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        // Snitt över fönstret är 100 (två punkter: 100 för 200 dagar sedan, 100 idag) plus en
        // extra punkt som drar ner/upp aktuell kurs relativt snittet.
        val underSnitt = listOf(price(200, 100.0), price(100, 100.0), price(0, 80.0))
        val overSnitt = listOf(price(200, 100.0), price(100, 100.0), price(0, 130.0))

        val gul = FundAnalysisCalc.analyze(today, holding, underSnitt, today.minusYears(1), 800.0, null)!!.trend!!
        assertEquals(FundAnalysisCalc.SignalLevel.GUL, gul.level)

        val gron = FundAnalysisCalc.analyze(today, holding, overSnitt, today.minusYears(1), 1300.0, null)!!.trend!!
        assertEquals(FundAnalysisCalc.SignalLevel.GRON, gron.level)
    }

    @Test
    fun `S2 ar null om historiken inte nar 200 dagar tillbaka`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        val history = (0..100L).map { price(it, nav = 100.0) }

        val analysis = FundAnalysisCalc.analyze(today, holding, history, today.minusDays(100), 1000.0, null)!!

        assertNull(analysis.trend)
    }

    @Test
    fun `S3 momentum - minst 5 procentenheter samre an portfoljsnittet ger gul`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        val history = listOf(price(100, 100.0), price(0, 90.0)) // -10 % senaste 3 mån

        val analysis = FundAnalysisCalc.analyze(
            today, holding, history, today.minusYears(1), 900.0,
            otherHoldingsAverageThreeMonthReturn = 0.0, // portföljsnitt 0 %, denna fond -10 % -> -10 p.e.
        )!!

        assertEquals(FundAnalysisCalc.SignalLevel.GUL, analysis.momentum!!.level)
        assertEquals(-10.0, analysis.momentum!!.differencePp, 1e-6)
    }

    @Test
    fun `S3 ar null om portfoljsnittet saknas`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        val history = listOf(price(100, 100.0), price(0, 90.0))

        val analysis = FundAnalysisCalc.analyze(
            today, holding, history, today.minusYears(1), 900.0, otherHoldingsAverageThreeMonthReturn = null,
        )!!

        assertNull(analysis.momentum)
    }

    @Test
    fun `statussummering - noll gula ger gron`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        val history = longHistory() // platt/stigande, inga signaler triggade
        // Portföljsnittet sätts nära denna fonds egen 3-månadersutveckling så momentumsignalen
        // inte triggas av misstag — testet gäller S1/S2, inte S3.
        val analysis = FundAnalysisCalc.analyze(today, holding, history, today.minusYears(4), 1200.0, 0.0)!!

        assertEquals(FundAnalysisCalc.SignalLevel.GRON, analysis.status)
    }

    @Test
    fun `statussummering - tva gula ger rod`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        // Kraftigt fallande NAV: under 200-dagarssnitt (S2 gul) och 15 % under toppen (S1 gul,
        // inte röd), momentum uteslutet (null -> räknas inte).
        val history = (0..400L).map { daysAgo -> price(daysAgo, nav = 100.0 + daysAgo * 0.05) } // NAV var högre förr

        val analysis = FundAnalysisCalc.analyze(today, holding, history, today.minusYears(2), 1200.0, null)!!

        assertEquals(FundAnalysisCalc.SignalLevel.GUL, analysis.distanceFromHigh!!.level)
        assertEquals(FundAnalysisCalc.SignalLevel.GUL, analysis.trend!!.level)
        assertEquals(FundAnalysisCalc.SignalLevel.ROD, analysis.status)
    }

    @Test
    fun `analyze ar null om inga andelar kvarstar`() {
        val holding = Holding(fund = fond, netShares = 0.0, netInvested = 0.0)
        assertNull(FundAnalysisCalc.analyze(today, holding, longHistory(), today.minusYears(1), 0.0, null))
    }

    @Test
    fun `analyze ar null om ingen kurshistorik alls finns`() {
        val holding = Holding(fund = fond, netShares = 10.0, netInvested = 500.0)
        assertNull(FundAnalysisCalc.analyze(today, holding, emptyList(), today.minusYears(1), 0.0, null))
    }

    @Test
    fun `threeMonthReturn och averageThreeMonthReturn`() {
        val history = listOf(price(100, 100.0), price(0, 110.0))
        assertEquals(0.10, FundAnalysisCalc.threeMonthReturn(today, history)!!, 1e-9)

        val average = FundAnalysisCalc.averageThreeMonthReturn(
            today,
            mapOf("A" to history, "B" to listOf(price(100, 100.0), price(0, 90.0))),
        )
        assertEquals(0.0, average!!, 1e-9) // (+10 % + -10 %) / 2
    }

    @Test
    fun `averageThreeMonthReturn ar null om ingen fond har tillrcklig historik`() {
        assertNull(FundAnalysisCalc.averageThreeMonthReturn(today, mapOf("A" to emptyList())))
    }
}
