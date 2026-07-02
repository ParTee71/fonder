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
}
