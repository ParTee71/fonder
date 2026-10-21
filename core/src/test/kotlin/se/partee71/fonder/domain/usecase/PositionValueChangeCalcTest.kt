package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/**
 * [PositionValueChangeCalc] — värdeförändringen i kr på de andelar som faktiskt ägts, dag för
 * dag (issue #193). Kurserna 100 → 110 → 99 → 120 på dag 1–4 gör att "dagens andelar × hela
 * kursrörelsen" (+20 per andel) och den faktiska förändringen skiljer sig i varje fall.
 */
class PositionValueChangeCalcTest {

    private val history = listOf(1L to 100.0, 2L to 110.0, 3L to 99.0, 4L to 120.0)
        .map { (day, nav) -> FundPrice(fundId = "A", epochDay = day, nav = nav) }

    private fun kop(day: Long, shares: Double, price: Double, fee: Double = 0.0) = Transaction(fundId = "A", type = TransactionType.KOP, epochDay = day, shares = shares, pricePerShare = price, fee = fee)

    private fun salj(day: Long, shares: Double, price: Double, fee: Double = 0.0) = Transaction(fundId = "A", type = TransactionType.SALJ, epochDay = day, shares = shares, pricePerShare = price, fee = fee)

    private fun change(transactions: List<Transaction>, from: Long = 1L, to: Long = 4L) = PositionValueChangeCalc.valueChange(transactions, history, from, to)

    @Test
    fun `andelar agda hela perioden far hela kursrorelsen`() {
        assertEquals(200.0, change(listOf(kop(day = 0, shares = 10.0, price = 95.0)))!!, 1e-9) // 10 × (120 − 100)
    }

    @Test
    fun `kop inom perioden far rorelsen forst fran nasta kurspunkt`() {
        // Köpt dag 2 à 110: 10 × (99 − 110) + 10 × (120 − 99) = 100 kr, inte 10 × (120 − 100) = 200.
        assertEquals(100.0, change(listOf(kop(day = 2, shares = 10.0, price = 110.0)))!!, 1e-9)
    }

    @Test
    fun `salj inom perioden behaller rorelsen fram till saljdagen`() {
        // 10 andelar från start, 4 sålda dag 3: 10 × 10 + 10 × (−11) + 6 × 21 = 116 kr.
        val transactions = listOf(kop(day = 0, shares = 10.0, price = 95.0), salj(day = 3, shares = 4.0, price = 99.0))
        assertEquals(116.0, change(transactions)!!, 1e-9)
    }

    @Test
    fun `avgifter paverkar inte vardeforandringen`() {
        // Kursen på transaktionsdagen = transaktionens kurs: från tomt innehav är förändringen
        // slutvärdet minus nettoköpen (andelar × kurs), courtaget ingår inte.
        val transactions = listOf(kop(day = 2, shares = 10.0, price = 110.0, fee = 39.0), salj(day = 3, shares = 4.0, price = 99.0, fee = 39.0))
        assertEquals(6 * 120.0 - (10 * 110.0 - 4 * 99.0), change(transactions)!!, 1e-9)
    }

    @Test
    fun `inget innehav vid periodstart och inget kop ger noll`() {
        assertEquals(0.0, change(emptyList())!!, 0.0)
        // Köpet ligger efter perioden: ingenting ägdes, oavsett hur kursen rört sig.
        assertEquals(0.0, change(listOf(kop(day = 5, shares = 10.0, price = 120.0)))!!, 0.0)
    }

    @Test
    fun `helt salt innehav far ingen rorelse efter saljet`() {
        // Allt sålt dag 2: bara 10 × (110 − 100) räknas.
        val transactions = listOf(kop(day = 0, shares = 10.0, price = 95.0), salj(day = 2, shares = 10.0, price = 110.0))
        assertEquals(100.0, change(transactions)!!, 1e-9)
    }

    @Test
    fun `salj utover innehavet tar inte andelarna under noll`() {
        // 15 sålda av 10: andelar utan känt köp ignoreras, innehavet blir 0 – inte −5.
        val transactions = listOf(kop(day = 0, shares = 10.0, price = 95.0), salj(day = 2, shares = 15.0, price = 110.0))
        assertEquals(100.0, change(transactions)!!, 1e-9)
    }

    @Test
    fun `saknad kurs vid periodstart ger null`() {
        assertNull(change(listOf(kop(day = 0, shares = 10.0, price = 95.0)), from = 0L))
        assertNull(PositionValueChangeCalc.valueChange(listOf(kop(day = 0, shares = 10.0, price = 95.0)), emptyList(), 1L, 4L))
    }

    @Test
    fun `startdag mellan kurspunkter anvander senaste kurs pa eller fore och slutdag klipper historiken`() {
        val owned = listOf(kop(day = 0, shares = 10.0, price = 95.0))
        // Helg/lucka: ingen kurs dag 2.5 finns, så startpunkten blir dag 2 (110). Slutet dag 3 (99).
        val withGap = history.filter { it.epochDay != 3L }
        assertEquals(10 * (120.0 - 110.0), PositionValueChangeCalc.valueChange(owned, withGap, 3L, 4L)!!, 1e-9)
        assertEquals(10 * (99.0 - 100.0), change(owned, from = 1L, to = 3L)!!, 1e-9)
    }

    @Test
    fun `osorterade transaktioner och kurser ger samma resultat`() {
        val transactions = listOf(salj(day = 3, shares = 4.0, price = 99.0), kop(day = 0, shares = 10.0, price = 95.0))
        assertEquals(116.0, PositionValueChangeCalc.valueChange(transactions, history.reversed(), 1L, 4L)!!, 1e-9)
    }
}
