package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import java.time.LocalDate

class PortfolioPerformanceCalcTest {

    private val fondA = Fund(fundId = "SHB0000442", name = "Fond A")
    private val fondB = Fund(fundId = "SHB0000627", name = "Fond B")
    private val today = LocalDate.of(2026, 7, 31)

    private fun price(daysAgo: Long, nav: Double, fundId: String = "SHB0000442") =
        FundPrice(fundId = fundId, epochDay = today.minusDays(daysAgo).toEpochDay(), nav = nav)

    private fun available(result: PortfolioPerformanceCalc.PeriodResult?) =
        result as PortfolioPerformanceCalc.PeriodResult.Available

    @Test
    fun `holdingChange berknar dag vecka och manad nar full historik finns`() {
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1200.0) // nav 120 idag
        // Priset som bär upp currentValue (120, "idag") måste finnas i history för att inte
        // felaktigt tolkas som en inaktuell kurs (issue #18).
        val history = listOf(
            price(daysAgo = 0, nav = 120.0),
            price(daysAgo = 1, nav = 110.0),
            price(daysAgo = 7, nav = 100.0),
            price(daysAgo = 30, nav = 80.0),
        )

        val day = available(PortfolioPerformanceCalc.holdingChange(holding, PortfolioPerformanceCalc.Period.DAG, today, history))
        assertEquals(100.0, day.amount, 1e-9) // 1200 - 10*110
        assertEquals(100.0 / 1100.0, day.fraction!!, 1e-9)

        val week = available(PortfolioPerformanceCalc.holdingChange(holding, PortfolioPerformanceCalc.Period.VECKA, today, history))
        assertEquals(200.0, week.amount, 1e-9) // 1200 - 10*100

        val month = available(PortfolioPerformanceCalc.holdingChange(holding, PortfolioPerformanceCalc.Period.MANAD, today, history))
        assertEquals(400.0, month.amount, 1e-9) // 1200 - 10*80
    }

    @Test
    fun `holdingChange anvander senaste kanda pris pa eller fore malldagen`() {
        // Ingen kurs exakt 7 dagar bak (helg/röd dag) — närmast föregående (8 dagar bak) används.
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1200.0)
        val history = listOf(price(daysAgo = 0, nav = 120.0), price(daysAgo = 8, nav = 100.0), price(daysAgo = 3, nav = 105.0))

        val week = available(PortfolioPerformanceCalc.holdingChange(holding, PortfolioPerformanceCalc.Period.VECKA, today, history))
        assertEquals(200.0, week.amount, 1e-9) // 1200 - 10*100
    }

    @Test
    fun `holdingChange otillrackligt om historiken inte racker tillbaka for perioden`() {
        // Fonden köptes för 3 dagar sedan — ingen kurs finns 7 eller 30 dagar bak.
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1100.0)
        // Priset som bär upp currentValue (110, "idag") måste finnas i history — annars
        // sammanfaller vår senast kända kurs med gårdagens (periodstarten för DAG) och ger
        // StalePrice i stället för ett beräknat värde (issue #18).
        val history = listOf(price(daysAgo = 3, nav = 100.0), price(daysAgo = 1, nav = 108.0), price(daysAgo = 0, nav = 110.0))

        // DAG: malldag = today-1, som exakt matchar priset vid daysAgo=1 (nav 108) -> 1100 - 10*108 = 20.
        assertEquals(
            20.0,
            available(PortfolioPerformanceCalc.holdingChange(holding, PortfolioPerformanceCalc.Period.DAG, today, history)).amount,
            1e-9,
        )
        assertEquals(
            PortfolioPerformanceCalc.PeriodResult.InsufficientHistory,
            PortfolioPerformanceCalc.holdingChange(holding, PortfolioPerformanceCalc.Period.VECKA, today, history),
        )
        assertEquals(
            PortfolioPerformanceCalc.PeriodResult.InsufficientHistory,
            PortfolioPerformanceCalc.holdingChange(holding, PortfolioPerformanceCalc.Period.MANAD, today, history),
        )
    }

    @Test
    fun `holdingChange null utan kand kurs`() {
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = null)
        assertNull(PortfolioPerformanceCalc.holdingChange(holding, PortfolioPerformanceCalc.Period.DAG, today, emptyList()))
    }

    @Test
    fun `holdingChange ger StalePrice nar senaste kanda kurs ar aldre an periodens start`() {
        // Regression (issue #18): senaste cachade NAV är 10 dagar gammal — periodens start
        // för DAG/VECKA hamnar då efter vår senaste kända kurs. Tidigare blev detta av misstag
        // ett vilseledande `0` (samma prisrad valdes för både "idag" och periodstarten).
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 900.0, currentValue = 1000.0) // nav 100, 10 dagar gammal
        val history = listOf(price(daysAgo = 10, nav = 100.0))

        assertEquals(
            PortfolioPerformanceCalc.PeriodResult.StalePrice,
            PortfolioPerformanceCalc.holdingChange(holding, PortfolioPerformanceCalc.Period.DAG, today, history),
        )
        assertEquals(
            PortfolioPerformanceCalc.PeriodResult.StalePrice,
            PortfolioPerformanceCalc.holdingChange(holding, PortfolioPerformanceCalc.Period.VECKA, today, history),
        )
        // MANAD (30 dagar): vår kända kurs (10 dagar gammal) är nyare än periodens start
        // (30 dagar bak), men vi har ingen kurs som når så långt tillbaka -> otillräcklig,
        // inte inaktuell (vi VET faktiskt vad kursen var för 10 dagar sedan).
        assertEquals(
            PortfolioPerformanceCalc.PeriodResult.InsufficientHistory,
            PortfolioPerformanceCalc.holdingChange(holding, PortfolioPerformanceCalc.Period.MANAD, today, history),
        )
    }

    @Test
    fun `holdingChange ger StalePrice om historik helt saknas trots kand currentValue`() {
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 900.0, currentValue = 1000.0)
        assertEquals(
            PortfolioPerformanceCalc.PeriodResult.StalePrice,
            PortfolioPerformanceCalc.holdingChange(holding, PortfolioPerformanceCalc.Period.DAG, today, emptyList()),
        )
    }

    @Test
    fun `holdingPerformance samlar dag vecka och manad`() {
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1200.0)
        val history = listOf(
            price(daysAgo = 0, nav = 120.0),
            price(daysAgo = 1, nav = 110.0),
            price(daysAgo = 7, nav = 100.0),
            price(daysAgo = 30, nav = 80.0),
        )

        val performance = PortfolioPerformanceCalc.holdingPerformance(holding, today, history)

        assertEquals(100.0, available(performance.day).amount, 1e-9)
        assertEquals(200.0, available(performance.week).amount, 1e-9)
        assertEquals(400.0, available(performance.month).amount, 1e-9)
    }

    @Test
    fun `totalChange summerar over innehav med data`() {
        val a = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1200.0)
        val b = Holding(fund = fondB, netShares = 5.0, netInvested = 500.0, currentValue = 600.0)
        val historyByFundId = mapOf(
            fondA.fundId to listOf(price(daysAgo = 0, nav = 120.0, fundId = fondA.fundId), price(daysAgo = 1, nav = 110.0, fundId = fondA.fundId)),
            fondB.fundId to listOf(price(daysAgo = 0, nav = 120.0, fundId = fondB.fundId), price(daysAgo = 1, nav = 110.0, fundId = fondB.fundId)),
        )

        val total = PortfolioPerformanceCalc.totalChange(listOf(a, b), PortfolioPerformanceCalc.Period.DAG, today, historyByFundId)
            as PortfolioPerformanceCalc.PortfolioPeriodResult.Available

        // a: 1200 - 10*110 = 100, historicalValue 1100. b: 600 - 5*110 = 50, historicalValue 550.
        assertEquals(150.0, total.amount, 1e-9)
        assertEquals(150.0 / 1650.0, total.fraction!!, 1e-9)
        assertFalse(total.partial)
    }

    @Test
    fun `totalChange markerar partial nar nagot innehav saknar historik men andra har data`() {
        val medHistorik = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1200.0)
        val nyligenTillagd = Holding(fund = fondB, netShares = 5.0, netInvested = 500.0, currentValue = 520.0)
        val historyByFundId = mapOf(
            fondA.fundId to listOf(price(daysAgo = 0, nav = 120.0, fundId = fondA.fundId), price(daysAgo = 30, nav = 80.0, fundId = fondA.fundId)),
            fondB.fundId to listOf(price(daysAgo = 1, nav = 104.0, fundId = fondB.fundId)), // ingen kurs 30 dagar bak
        )

        val total = PortfolioPerformanceCalc.totalChange(listOf(medHistorik, nyligenTillagd), PortfolioPerformanceCalc.Period.MANAD, today, historyByFundId)
            as PortfolioPerformanceCalc.PortfolioPeriodResult.Available

        assertEquals(400.0, total.amount, 1e-9) // bara fondA bidrar: 1200 - 10*80
        assertTrue(total.partial)
    }

    @Test
    fun `totalChange InsufficientHistory om inget innehav har tillrackligt data`() {
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1100.0)
        val historyByFundId = mapOf(fondA.fundId to listOf(price(daysAgo = 1, nav = 108.0)))

        assertEquals(
            PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
            PortfolioPerformanceCalc.totalChange(listOf(holding), PortfolioPerformanceCalc.Period.MANAD, today, historyByFundId),
        )
    }

    @Test
    fun `totalChange StalePrice om samtliga innehav har en inaktuell kurs`() {
        // Regression (issue #18) — motsvarar skärmdumpen där varje fond visade falskt 0.
        val a = Holding(fund = fondA, netShares = 10.0, netInvested = 900.0, currentValue = 1000.0)
        val b = Holding(fund = fondB, netShares = 5.0, netInvested = 450.0, currentValue = 500.0)
        val historyByFundId = mapOf(
            fondA.fundId to listOf(price(daysAgo = 10, nav = 100.0, fundId = fondA.fundId)),
            fondB.fundId to listOf(price(daysAgo = 15, nav = 100.0, fundId = fondB.fundId)),
        )

        val day = PortfolioPerformanceCalc.totalChange(listOf(a, b), PortfolioPerformanceCalc.Period.DAG, today, historyByFundId)
        val week = PortfolioPerformanceCalc.totalChange(listOf(a, b), PortfolioPerformanceCalc.Period.VECKA, today, historyByFundId)

        assertEquals(PortfolioPerformanceCalc.PortfolioPeriodResult.StalePrice, day)
        assertEquals(PortfolioPerformanceCalc.PortfolioPeriodResult.StalePrice, week)
    }

    @Test
    fun `totalChange hoppar over innehav utan kand kurs utan att markera partial`() {
        val medKurs = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1100.0)
        val utanKurs = Holding(fund = fondB, netShares = 5.0, netInvested = 500.0, currentValue = null)
        val historyByFundId = mapOf(
            fondA.fundId to listOf(price(daysAgo = 0, nav = 110.0, fundId = fondA.fundId), price(daysAgo = 1, nav = 105.0, fundId = fondA.fundId)),
        )

        val total = PortfolioPerformanceCalc.totalChange(listOf(medKurs, utanKurs), PortfolioPerformanceCalc.Period.DAG, today, historyByFundId)
            as PortfolioPerformanceCalc.PortfolioPeriodResult.Available

        assertEquals(50.0, total.amount, 1e-9) // 1100 - 10*105
        assertFalse(total.partial)
    }

    @Test
    fun `totalChange InsufficientHistory for tom portfolj`() {
        assertEquals(
            PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
            PortfolioPerformanceCalc.totalChange(emptyList(), PortfolioPerformanceCalc.Period.DAG, today, emptyMap()),
        )
    }

    @Test
    fun `totalPerformance samlar dag vecka och manad`() {
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1200.0)
        val historyByFundId = mapOf(
            fondA.fundId to listOf(
                price(daysAgo = 0, nav = 120.0),
                price(daysAgo = 1, nav = 110.0),
                price(daysAgo = 7, nav = 100.0),
                price(daysAgo = 30, nav = 80.0),
            ),
        )

        val performance = PortfolioPerformanceCalc.totalPerformance(listOf(holding), today, historyByFundId)

        assertEquals(100.0, (performance.day as PortfolioPerformanceCalc.PortfolioPeriodResult.Available).amount, 1e-9)
        assertEquals(200.0, (performance.week as PortfolioPerformanceCalc.PortfolioPeriodResult.Available).amount, 1e-9)
        assertEquals(400.0, (performance.month as PortfolioPerformanceCalc.PortfolioPeriodResult.Available).amount, 1e-9)
    }
}
