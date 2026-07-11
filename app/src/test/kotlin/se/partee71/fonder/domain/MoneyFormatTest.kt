package se.partee71.fonder.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.usecase.MoneyFormat

class MoneyFormatTest {

    @Test
    fun `kr formaterar med tva decimaler komma och kr-suffix`() {
        val out = MoneyFormat.kr(1234.5)
        // sv-SE: decimalkomma och kr-suffix (tusentalsavgränsaren kan vara vanligt/hårt blanksteg).
        assertTrue("fick: $out", out.contains("234,50"))
        assertTrue("fick: $out", out.trim().endsWith("kr"))
    }

    @Test
    fun `percentSigned ger plus for positiv avkastning`() {
        val out = MoneyFormat.percentSigned(0.1234)
        assertTrue("fick: $out", out.startsWith("+"))
        assertTrue("fick: $out", out.contains("12,3"))
    }

    @Test
    fun `percentSigned ger minustecken for negativ avkastning`() {
        assertTrue(MoneyFormat.percentSigned(-0.05).startsWith("−"))
    }

    @Test
    fun `percent ger osignerad procent med en decimal`() {
        val out = MoneyFormat.percent(0.182)
        assertTrue("fick: $out", out.contains("18,2"))
        assertTrue("fick: $out", out.trim().endsWith("%"))
        assertTrue("fick: $out", !out.startsWith("+")) // aldrig tecken (volatilitet är aldrig negativ)
    }

    @Test
    fun `decimal ger tva decimaler och akta minustecken`() {
        assertTrue("fick: ${MoneyFormat.decimal(0.8)}", MoneyFormat.decimal(0.8).contains("0,80"))
        assertTrue(MoneyFormat.decimal(-0.4).startsWith("−"))
    }
}
