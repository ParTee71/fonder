package se.partee71.fonder.data.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Enhetstest för den rena URL-byggaren i [RiksbankFxClient] (nätverksdelen kräver riktig HTTP
 * och testas inte här) — samma mönster som [AvanzaClientTest].
 */
class RiksbankFxClientTest {

    @Test
    fun `seriesId versaliserar och foljer SEK-mot-valuta-monstret`() {
        assertEquals("SEKUSDPMI", RiksbankFxClient.seriesId("usd"))
        assertEquals("SEKUSDPMI", RiksbankFxClient.seriesId("USD"))
    }

    @Test
    fun `seriesUrl byggs av bas-url, serie-id och datumintervall`() {
        val url = RiksbankFxClient.seriesUrl("USD", LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 28))

        assertEquals(
            "https://api.riksbank.se/swea/v1/Observations/SEKUSDPMI/2026-07-20/2026-07-28",
            url,
        )
    }
}
