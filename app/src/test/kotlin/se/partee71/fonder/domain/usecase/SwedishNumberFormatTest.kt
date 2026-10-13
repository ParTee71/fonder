package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwedishNumberFormatTest {

    @Test
    fun `punkt som decimalavgransare tolkas`() {
        assertEquals(150.75, SwedishNumberFormat.parse("150.75")!!, 1e-9)
    }

    @Test
    fun `komma som decimalavgransare tolkas`() {
        assertEquals(150.75, SwedishNumberFormat.parse("150,75")!!, 1e-9)
    }

    @Test
    fun `mellanslag som tusentalsavgransare tas bort`() {
        assertEquals(1234.5, SwedishNumberFormat.parse("1 234,50")!!, 1e-9)
    }

    @Test
    fun `hart mellanslag (U+00A0) som tusentalsavgransare tas bort`() {
        // Regression: SwedishNumberFormat hade tidigare två identiska ersättningar
        // av vanligt mellanslag i stället för att också hantera U+00A0 — vanligt i
        // talformatering hämtad från webbsidor (t.ex. Handelsbankens kurstabell).
        assertEquals(1234.5, SwedishNumberFormat.parse("1 234,50")!!, 1e-9)
    }

    @Test
    fun `omgivande blanksteg trimmas`() {
        assertEquals(42.0, SwedishNumberFormat.parse("  42  ")!!, 1e-9)
    }

    @Test
    fun `ogiltig text ger null`() {
        assertNull(SwedishNumberFormat.parse("inte ett tal"))
    }

    @Test
    fun `tom text ger null`() {
        assertNull(SwedishNumberFormat.parse(""))
    }

    @Test
    fun `format ger decimalkomma utan tusentalsavgransare och tolkas tillbaka`() {
        assertEquals("38,4615", SwedishNumberFormat.format(38.46153, 4))
        assertEquals("12500,0000", SwedishNumberFormat.format(12_500.0, 4))
        assertEquals(38.4615, SwedishNumberFormat.parse(SwedishNumberFormat.format(38.46153, 4))!!, 1e-9)
    }

    @Test
    fun `andelar for ett belopp ar belopp delat med kurs med fyra decimaler`() {
        assertEquals(38.4615, TransactionPrefill.sharesForAmount(12_500.0, 325.0)!!, 1e-9)
        assertNull(TransactionPrefill.sharesForAmount(null, 325.0))
        assertNull(TransactionPrefill.sharesForAmount(12_500.0, null))
        assertNull(TransactionPrefill.sharesForAmount(12_500.0, 0.0))
    }
}
