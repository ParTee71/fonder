package se.partee71.fonder.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Fixturen nedan är ett trimmat utdrag av ett verkligt svar från Riksbankens SWEA-API
 * (`SEKUSDPMI`-serien, verifierat live 2026-07-28 — se KRAVLISTA TP-20).
 */
class RiksbankJsonParserTest {

    @Test
    fun `parseObservations laser datum och kurs`() {
        val response = """
            [{"date":"2026-07-20","value":9.66655},{"date":"2026-07-21","value":9.67639}]
        """.trimIndent()

        val points = RiksbankJsonParser.parseObservations(response)

        assertEquals(2, points.size)
        assertEquals(LocalDate.of(2026, 7, 20).toEpochDay(), points.first().epochDay)
        assertEquals(9.66655, points.first().rate, 1e-9)
        assertEquals(LocalDate.of(2026, 7, 21).toEpochDay(), points.last().epochDay)
    }

    @Test
    fun `parseObservations hoppar over rader utan varde`() {
        val response = """[{"date":"2026-07-20","value":null},{"date":"2026-07-21","value":9.5}]"""

        val points = RiksbankJsonParser.parseObservations(response)

        assertEquals(1, points.size)
        assertEquals(9.5, points.first().rate, 1e-9)
    }

    @Test
    fun `parseObservations ger tom lista for tomt svar`() {
        assertTrue(RiksbankJsonParser.parseObservations("[]").isEmpty())
    }

    @Test
    fun `parseObservations ger tom lista om svaret inte gar att tolka`() {
        assertTrue(RiksbankJsonParser.parseObservations("inte json").isEmpty())
    }
}
