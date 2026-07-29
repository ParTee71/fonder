package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseMarkerFilterTest {

    private fun point(epochDay: Long) = epochDay to 100.0

    @Test
    fun `kopdag som exakt matchar en kurspunkt behalls oforandrad`() {
        val points = listOf(point(100), point(101), point(102))

        val result = PurchaseMarkerFilter.apply(points, listOf(101))

        assertEquals(listOf(101L), result)
    }

    @Test
    fun `kopdag utanfor periodens intervall filtreras bort`() {
        val points = listOf(point(100), point(101), point(102))

        val result = PurchaseMarkerFilter.apply(points, listOf(50, 200))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `kopdag utan exakt matchande kurspunkt snappas till narmaste`() {
        // En helgdaterad köpdag (ingen NAV-punkt just den dagen) — Vicos persistenta
        // markörer visar annars ingen markör alls om x inte matchar en punkt exakt.
        val points = listOf(point(100), point(103), point(107))

        val result = PurchaseMarkerFilter.apply(points, listOf(104))

        assertEquals(listOf(103L), result)
    }

    @Test
    fun `flera kopdagar inom perioden ger flera markorer`() {
        val points = listOf(point(100), point(101), point(102), point(103), point(104))

        val result = PurchaseMarkerFilter.apply(points, listOf(104, 100, 102))

        assertEquals(listOf(100L, 102L, 104L), result)
    }

    @Test
    fun `dubbletter efter snappning ger bara en markor`() {
        val points = listOf(point(100), point(110))

        // Två köp som båda ligger närmast samma kurspunkt (100) ska bara ge en markör.
        val result = PurchaseMarkerFilter.apply(points, listOf(101, 102))

        assertEquals(listOf(100L), result)
    }

    @Test
    fun `tom punktlista ger inga markorer`() {
        assertTrue(PurchaseMarkerFilter.apply(emptyList(), listOf(100)).isEmpty())
    }

    @Test
    fun `inga kopdagar ger inga markorer`() {
        assertTrue(PurchaseMarkerFilter.apply(listOf(point(100)), emptyList()).isEmpty())
    }
}
