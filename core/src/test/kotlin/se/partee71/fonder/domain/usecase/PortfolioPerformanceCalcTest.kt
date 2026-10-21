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

class PortfolioPerformanceCalcTest {

    private val fondA = Fund(fundId = "SHB0000442", name = "Fond A")
    private val fondB = Fund(fundId = "SHB0000627", name = "Fond B")
    private val today = LocalDate.of(2026, 7, 31)

    private fun price(daysAgo: Long, nav: Double, fundId: String = "SHB0000442") = FundPrice(fundId = fundId, epochDay = today.minusDays(daysAgo).toEpochDay(), nav = nav)

    /** Ett köp av innehavets andelar långt före alla perioder — andelarna har ägts hela tiden. */
    private fun bought(holding: Holding) = listOf(buy(holding.fund.fundId, daysAgo = 400, shares = holding.netShares, nav = 50.0))

    private fun owned(vararg holdings: Holding) = holdings.associate { it.fund.fundId to bought(it) }

    private fun buy(fundId: String, daysAgo: Long, shares: Double, nav: Double, fee: Double = 0.0) = Transaction(fundId = fundId, type = TransactionType.KOP, epochDay = today.minusDays(daysAgo).toEpochDay(), shares = shares, pricePerShare = nav, fee = fee)

    private fun sell(fundId: String, daysAgo: Long, shares: Double, nav: Double, fee: Double = 0.0) = Transaction(fundId = fundId, type = TransactionType.SALJ, epochDay = today.minusDays(daysAgo).toEpochDay(), shares = shares, pricePerShare = nav, fee = fee)

    private fun available(result: PortfolioPerformanceCalc.PeriodResult?) = result as PortfolioPerformanceCalc.PeriodResult.Available

    @Test
    fun `holdingChange berknar dag vecka och manad nar full historik finns`() {
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1200.0) // nav 120 idag
        // Priset som bär upp currentValue (120, "idag") måste finnas i history för att inte
        // felaktigt tolkas som en inaktuell kurs (issue #18).
        val history = listOf(
            price(daysAgo = 0, nav = 120.0),
            price(daysAgo = 1, nav = 110.0),
            price(daysAgo = 7, nav = 100.0),
            price(daysAgo = 30, nav = 80.0)
        )

        val day = available(PortfolioPerformanceCalc.holdingChange(holding, bought(holding), PortfolioPerformanceCalc.Period.DAG, today, history))
        assertEquals(100.0, day.amount, 1e-9) // 1200 - 10*110
        assertEquals(100.0 / 1100.0, day.fraction!!, 1e-9)

        val week = available(PortfolioPerformanceCalc.holdingChange(holding, bought(holding), PortfolioPerformanceCalc.Period.VECKA, today, history))
        assertEquals(200.0, week.amount, 1e-9) // 1200 - 10*100

        val month = available(PortfolioPerformanceCalc.holdingChange(holding, bought(holding), PortfolioPerformanceCalc.Period.MANAD, today, history))
        assertEquals(400.0, month.amount, 1e-9) // 1200 - 10*80
    }

    @Test
    fun `holdingChange anvander senaste kanda pris pa eller fore malldagen`() {
        // Ingen kurs exakt 7 dagar bak (helg/röd dag) — närmast föregående (8 dagar bak) används.
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1200.0)
        val history = listOf(price(daysAgo = 0, nav = 120.0), price(daysAgo = 8, nav = 100.0), price(daysAgo = 3, nav = 105.0))

        val week = available(PortfolioPerformanceCalc.holdingChange(holding, bought(holding), PortfolioPerformanceCalc.Period.VECKA, today, history))
        assertEquals(200.0, week.amount, 1e-9) // 1200 - 10*100
    }

    @Test
    fun `holdingChange flaggar otillrackligt nar narmaste kurs ligger for langt fran malldagen`() {
        // issue #35: kurscachen har ett hål strax före periodens start (t.ex. en odokumenterad
        // källa som samplar ner långa intervall) — närmaste kurs på/före måldagen hittas
        // fortfarande, men är för gammal för att vara en trovärdig proxy. Utan denna gräns
        // skulle "En dag" tyst visa en missvisande siffra byggd på en 9 dagar gammal kurs —
        // exakt samma, för gamla, rad som "Senaste veckan" också råkar välja.
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1200.0)
        val history = listOf(price(daysAgo = 0, nav = 120.0), price(daysAgo = 9, nav = 90.0))

        assertEquals(
            PortfolioPerformanceCalc.PeriodResult.InsufficientHistory,
            PortfolioPerformanceCalc.holdingChange(holding, bought(holding), PortfolioPerformanceCalc.Period.DAG, today, history)
        )
        // Veckan hittar samma rad, men gapet (2 dagar) är inom toleransen — fortsatt beräkningsbar.
        val week = available(PortfolioPerformanceCalc.holdingChange(holding, bought(holding), PortfolioPerformanceCalc.Period.VECKA, today, history))
        assertEquals(300.0, week.amount, 1e-9) // 1200 - 10*90
    }

    @Test
    fun `holdingChange otillrackligt om historiken inte racker tillbaka for perioden`() {
        // Fonden köptes för 3 dagar sedan — ingen kurs finns 7 eller 30 dagar bak.
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1100.0)
        // Referensdagen (senaste kända NAV) är idag (nav 110); dagen före finns (nav 108) men
        // ingen kurs når 7/30 dagar bak — DAG räknas, vecka/månad blir otillräcklig.
        val history = listOf(price(daysAgo = 3, nav = 100.0), price(daysAgo = 1, nav = 108.0), price(daysAgo = 0, nav = 110.0))

        // DAG: referensdag = idag, målldag = referensdag-1, matchar priset vid daysAgo=1 (nav 108) -> 1100 - 10*108 = 20.
        assertEquals(
            20.0,
            available(PortfolioPerformanceCalc.holdingChange(holding, bought(holding), PortfolioPerformanceCalc.Period.DAG, today, history)).amount,
            1e-9
        )
        assertEquals(
            PortfolioPerformanceCalc.PeriodResult.InsufficientHistory,
            PortfolioPerformanceCalc.holdingChange(holding, bought(holding), PortfolioPerformanceCalc.Period.VECKA, today, history)
        )
        assertEquals(
            PortfolioPerformanceCalc.PeriodResult.InsufficientHistory,
            PortfolioPerformanceCalc.holdingChange(holding, bought(holding), PortfolioPerformanceCalc.Period.MANAD, today, history)
        )
    }

    @Test
    fun `holdingChange null utan kand kurs`() {
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = null)
        assertNull(PortfolioPerformanceCalc.holdingChange(holding, bought(holding), PortfolioPerformanceCalc.Period.DAG, today, emptyList()))
    }

    @Test
    fun `holdingChange raknar senaste dagsrorelsen aven nar kursen slapar`() {
        // Kärnan i re-ankringen: senaste NAV är 5 dagar gammal (utländsk fond som släpar, eller
        // dagens NAV inte publicerad än). "En dag" ska då visa den senaste faktiska dagsrörelsen
        // (referensdagen mot dagen före), inte en tom rad — tidigare krävdes en kurs daterad idag.
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1100.0) // nav 110, 5 dagar gammal
        val history = listOf(price(daysAgo = 5, nav = 110.0), price(daysAgo = 6, nav = 100.0))

        val day = available(PortfolioPerformanceCalc.holdingChange(holding, bought(holding), PortfolioPerformanceCalc.Period.DAG, today, history))
        assertEquals(100.0, day.amount, 1e-9) // 1100 - 10*100
        assertEquals(100.0 / 1000.0, day.fraction!!, 1e-9)
    }

    @Test
    fun `holdingChange InsufficientHistory nar bara en enda kurs finns`() {
        // En enda känd kurs — ingen tidigare punkt att jämföra mot -> otillräcklig, aldrig ett gissat 0.
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 900.0, currentValue = 1000.0)
        val history = listOf(price(daysAgo = 10, nav = 100.0))

        assertEquals(
            PortfolioPerformanceCalc.PeriodResult.InsufficientHistory,
            PortfolioPerformanceCalc.holdingChange(holding, bought(holding), PortfolioPerformanceCalc.Period.DAG, today, history)
        )
        assertEquals(
            PortfolioPerformanceCalc.PeriodResult.InsufficientHistory,
            PortfolioPerformanceCalc.holdingChange(holding, bought(holding), PortfolioPerformanceCalc.Period.MANAD, today, history)
        )
    }

    @Test
    fun `holdingChange InsufficientHistory om historik helt saknas trots kand currentValue`() {
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 900.0, currentValue = 1000.0)
        assertEquals(
            PortfolioPerformanceCalc.PeriodResult.InsufficientHistory,
            PortfolioPerformanceCalc.holdingChange(holding, bought(holding), PortfolioPerformanceCalc.Period.DAG, today, emptyList())
        )
    }

    @Test
    fun `holdingPerformance samlar dag vecka och manad`() {
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1200.0)
        val history = listOf(
            price(daysAgo = 0, nav = 120.0),
            price(daysAgo = 1, nav = 110.0),
            price(daysAgo = 7, nav = 100.0),
            price(daysAgo = 30, nav = 80.0)
        )

        val performance = PortfolioPerformanceCalc.holdingPerformance(holding, bought(holding), today, history)

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
            fondB.fundId to listOf(price(daysAgo = 0, nav = 120.0, fundId = fondB.fundId), price(daysAgo = 1, nav = 110.0, fundId = fondB.fundId))
        )

        val total = PortfolioPerformanceCalc.totalChange(listOf(a, b), owned(a, b), PortfolioPerformanceCalc.Period.DAG, today, historyByFundId)
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
            fondB.fundId to listOf(price(daysAgo = 1, nav = 104.0, fundId = fondB.fundId)) // ingen kurs 30 dagar bak
        )

        val total = PortfolioPerformanceCalc.totalChange(listOf(medHistorik, nyligenTillagd), owned(medHistorik, nyligenTillagd), PortfolioPerformanceCalc.Period.MANAD, today, historyByFundId)
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
            PortfolioPerformanceCalc.totalChange(listOf(holding), owned(holding), PortfolioPerformanceCalc.Period.MANAD, today, historyByFundId)
        )
    }

    @Test
    fun `totalChange raknar senaste dagsrorelsen aven nar samtliga kurser slapar`() {
        // Re-ankring på portföljnivå: alla fonders NAV är några dagar gamla men har minst två
        // punkter -> den senaste dagsrörelsen summeras, i stället för en tom total.
        val a = Holding(fund = fondA, netShares = 10.0, netInvested = 900.0, currentValue = 1000.0) // nav 100, 5 dagar gammal
        val b = Holding(fund = fondB, netShares = 5.0, netInvested = 450.0, currentValue = 500.0) // nav 100, 5 dagar gammal
        val historyByFundId = mapOf(
            fondA.fundId to listOf(price(daysAgo = 5, nav = 100.0, fundId = fondA.fundId), price(daysAgo = 6, nav = 95.0, fundId = fondA.fundId)),
            fondB.fundId to listOf(price(daysAgo = 5, nav = 100.0, fundId = fondB.fundId), price(daysAgo = 6, nav = 96.0, fundId = fondB.fundId))
        )

        val day = PortfolioPerformanceCalc.totalChange(listOf(a, b), owned(a, b), PortfolioPerformanceCalc.Period.DAG, today, historyByFundId)
            as PortfolioPerformanceCalc.PortfolioPeriodResult.Available

        // a: 1000 - 10*95 = 50, b: 500 - 5*96 = 20.
        assertEquals(70.0, day.amount, 1e-9)
        assertFalse(day.partial)
    }

    @Test
    fun `totalChange InsufficientHistory nar samtliga innehav bara har en enda kurs`() {
        val a = Holding(fund = fondA, netShares = 10.0, netInvested = 900.0, currentValue = 1000.0)
        val b = Holding(fund = fondB, netShares = 5.0, netInvested = 450.0, currentValue = 500.0)
        val historyByFundId = mapOf(
            fondA.fundId to listOf(price(daysAgo = 10, nav = 100.0, fundId = fondA.fundId)),
            fondB.fundId to listOf(price(daysAgo = 15, nav = 100.0, fundId = fondB.fundId))
        )

        assertEquals(
            PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
            PortfolioPerformanceCalc.totalChange(listOf(a, b), owned(a, b), PortfolioPerformanceCalc.Period.DAG, today, historyByFundId)
        )
    }

    @Test
    fun `totalChange hoppar over innehav utan kand kurs utan att markera partial`() {
        val medKurs = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1100.0)
        val utanKurs = Holding(fund = fondB, netShares = 5.0, netInvested = 500.0, currentValue = null)
        val historyByFundId = mapOf(
            fondA.fundId to listOf(price(daysAgo = 0, nav = 110.0, fundId = fondA.fundId), price(daysAgo = 1, nav = 105.0, fundId = fondA.fundId))
        )

        val total = PortfolioPerformanceCalc.totalChange(listOf(medKurs, utanKurs), owned(medKurs, utanKurs), PortfolioPerformanceCalc.Period.DAG, today, historyByFundId)
            as PortfolioPerformanceCalc.PortfolioPeriodResult.Available

        assertEquals(50.0, total.amount, 1e-9) // 1100 - 10*105
        assertFalse(total.partial)
    }

    @Test
    fun `totalChange InsufficientHistory for tom portfolj`() {
        assertEquals(
            PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
            PortfolioPerformanceCalc.totalChange(emptyList(), emptyMap(), PortfolioPerformanceCalc.Period.DAG, today, emptyMap())
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
                price(daysAgo = 30, nav = 80.0)
            )
        )

        val performance = PortfolioPerformanceCalc.totalPerformance(listOf(holding), owned(holding), today, historyByFundId)

        assertEquals(100.0, (performance.day as PortfolioPerformanceCalc.PortfolioPeriodResult.Available).amount, 1e-9)
        assertEquals(200.0, (performance.week as PortfolioPerformanceCalc.PortfolioPeriodResult.Available).amount, 1e-9)
        assertEquals(400.0, (performance.month as PortfolioPerformanceCalc.PortfolioPeriodResult.Available).amount, 1e-9)
    }

    // --- Kronor på ägda andelar (issue #193) ---

    /** Månaden 80 → 100 (för 3 dagar sedan) → 120 idag, mätt på [holding] med [transactions]. */
    private fun monthWithTradeThreeDaysAgo(holding: Holding, transactions: List<Transaction>) = available(
        PortfolioPerformanceCalc.holdingChange(
            holding,
            transactions,
            PortfolioPerformanceCalc.Period.MANAD,
            today,
            listOf(price(daysAgo = 30, nav = 80.0), price(daysAgo = 3, nav = 100.0), price(daysAgo = 0, nav = 120.0))
        )
    )

    @Test
    fun `holdingChange raknar kronorna pa agda andelar nar ett kop gjorts inom perioden`() {
        // 10 andelar sedan länge, 10 till köpta för 3 dagar sedan à 100. Månaden: 80 → 100 på 10
        // andelar, sedan 100 → 120 på 20 = 600 kr — inte 20 × (120 − 80) = 800 kr.
        val holding = Holding(fund = fondA, netShares = 20.0, netInvested = 1800.0, currentValue = 2400.0)
        val transactions = listOf(buy(fondA.fundId, daysAgo = 400, shares = 10.0, nav = 80.0), buy(fondA.fundId, daysAgo = 3, shares = 10.0, nav = 100.0))

        val month = monthWithTradeThreeDaysAgo(holding, transactions)

        assertEquals(600.0, month.amount, 1e-9)
        assertEquals(0.5, month.fraction!!, 1e-9) // fondens kursutveckling 80 → 120, oberoende av köpet
    }

    @Test
    fun `holdingChange raknar kronorna pa salda andelar fram till saljdagen`() {
        // 20 andelar sedan länge, 10 sålda för 3 dagar sedan à 100: 20 × (100 − 80) + 10 × (120 − 100) = 600 kr
        // — inte dagens 10 × (120 − 80) = 400 kr.
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 800.0, currentValue = 1200.0)
        val transactions = listOf(buy(fondA.fundId, daysAgo = 400, shares = 20.0, nav = 80.0), sell(fondA.fundId, daysAgo = 3, shares = 10.0, nav = 100.0, fee = 39.0))

        val month = monthWithTradeThreeDaysAgo(holding, transactions)

        assertEquals(600.0, month.amount, 1e-9)
        assertEquals(0.5, month.fraction!!, 1e-9)
    }

    @Test
    fun `holdingChange ger noll kronor for andelar kopta efter periodens senaste kurs`() {
        // Köpt idag, efter referensdagens kurs (i går): inga andelar ägdes under dagen som mäts.
        val holding = Holding(fund = fondA, netShares = 10.0, netInvested = 1100.0, currentValue = 1100.0)
        val transactions = listOf(buy(fondA.fundId, daysAgo = 0, shares = 10.0, nav = 110.0))
        val history = listOf(price(daysAgo = 2, nav = 100.0), price(daysAgo = 1, nav = 110.0))

        val day = available(PortfolioPerformanceCalc.holdingChange(holding, transactions, PortfolioPerformanceCalc.Period.DAG, today, history))

        assertEquals(0.0, day.amount, 0.0)
        assertEquals(0.1, day.fraction!!, 1e-9)
    }

    @Test
    fun `totalChange summerar kronorna pa agda andelar och vager procenten med startvardet`() {
        // A: köpt 10 för 3 dagar sedan (80 → 100 → 120 på månaden, men bara 100 → 120 ägt) = 200 kr.
        // B: 5 andelar hela månaden, 100 → 110 = 50 kr. Procent: (10 × 40 + 5 × 10) / (10 × 80 + 5 × 100).
        val a = Holding(fund = fondA, netShares = 10.0, netInvested = 1000.0, currentValue = 1200.0)
        val b = Holding(fund = fondB, netShares = 5.0, netInvested = 500.0, currentValue = 550.0)
        val transactionsByFundId = mapOf(
            fondA.fundId to listOf(buy(fondA.fundId, daysAgo = 3, shares = 10.0, nav = 100.0)),
            fondB.fundId to listOf(buy(fondB.fundId, daysAgo = 400, shares = 5.0, nav = 90.0))
        )
        val historyByFundId = mapOf(
            fondA.fundId to listOf(price(daysAgo = 30, nav = 80.0), price(daysAgo = 3, nav = 100.0), price(daysAgo = 0, nav = 120.0)),
            fondB.fundId to listOf(price(daysAgo = 30, nav = 100.0, fundId = fondB.fundId), price(daysAgo = 0, nav = 110.0, fundId = fondB.fundId))
        )

        val month = PortfolioPerformanceCalc.totalChange(listOf(a, b), transactionsByFundId, PortfolioPerformanceCalc.Period.MANAD, today, historyByFundId)
            as PortfolioPerformanceCalc.PortfolioPeriodResult.Available

        assertEquals(250.0, month.amount, 1e-9)
        assertEquals(450.0 / 1300.0, month.fraction!!, 1e-9)
        assertFalse(month.partial)
    }

    @Test
    fun `periodraderna matchar fondmatte golden for periodkop`() {
        // tools/fondmatte/periodkop.json → funds.A.periods (day/week/month): kronor på ägda andelar.
        val fixture = PeriodkopFixture
        val performance = PortfolioPerformanceCalc.holdingPerformance(fixture.holding, fixture.transactions, fixture.endDate, fixture.history)

        assertEquals(155.99999999999966, available(performance.day).amount, 1e-9)
        assertEquals(0.011937557392102782, available(performance.day).fraction!!, 1e-9)
        assertEquals(588.0000000000007, available(performance.week).amount, 1e-9)
        assertEquals(0.04653371320038002, available(performance.week).fraction!!, 1e-9)
        assertEquals(858.0000000000002, available(performance.month).amount, 1e-9)
        assertEquals(0.07775061124694371, available(performance.month).fraction!!, 1e-9)

        val total = PortfolioPerformanceCalc.totalPerformance(
            listOf(fixture.holding),
            mapOf(fixture.fund.fundId to fixture.transactions),
            fixture.endDate,
            mapOf(fixture.fund.fundId to fixture.history)
        )
        assertEquals(858.0000000000002, (total.month as PortfolioPerformanceCalc.PortfolioPeriodResult.Available).amount, 1e-9)
        assertEquals(0.07775061124694371, (total.month as PortfolioPerformanceCalc.PortfolioPeriodResult.Available).fraction!!, 1e-9)
    }
}
