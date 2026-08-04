package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Namnnyckeln som kopplar en katalogfond utan ISIN till cachad metadata (UI-10, issue #85). */
class FundNameKeyTest {

    @Test
    fun `versaler skiljetecken och dubbla blanksteg pavarkar inte nyckeln`() {
        assertEquals(
            FundNameKey.of("Handelsbanken Sverige Index (A1 SEK)"),
            FundNameKey.of("handelsbanken  sverige index, a1 sek"),
        )
    }

    @Test
    fun `svenska tecken behalls`() {
        assertEquals("länsförsäkringar sverige index", FundNameKey.of("Länsförsäkringar Sverige Index"))
    }

    @Test
    fun `olika fonder far olika nycklar`() {
        assertNotEquals(
            FundNameKey.of("Handelsbanken Sverige Index"),
            FundNameKey.of("Handelsbanken Global Index"),
        )
    }

    @Test
    fun `namn utan matchbara tecken ger tom nyckel`() {
        assertEquals("", FundNameKey.of("  --  "))
    }
}
