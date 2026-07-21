package se.partee71.fonder.data.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Enhetstest för den rena URL-byggaren i [AvanzaClient] (nätverksdelen kräver riktig HTTP och
 * testas inte här). Vaktar den avgörande `resolution=DAY`-parametern: utan den nedsamplar
 * Avanza långa spann till veckopunkter, så dagskurserna aldrig når cachen och dag/vecka fastnar
 * på "Kurs ej uppdaterad" (POR-5/HEM-2) — samt att ett `to`-datum i framtiden clampas (Avanza
 * svarar annars 400 Bad Request).
 */
class AvanzaClientTest {

    @Test
    fun `chartUrl begar daglig upplosning over hela spannet`() {
        val url = AvanzaClient.chartUrl(
            orderbookId = "236069",
            from = LocalDate.of(2025, 6, 10),
            to = LocalDate.of(2026, 7, 20),
            today = LocalDate.of(2026, 7, 20),
        )

        assertEquals(
            "https://www.avanza.se/_api/fund-guide/chart/236069/2025-06-10/2026-07-20?raw=true&resolution=DAY",
            url,
        )
    }

    @Test
    fun `chartUrl clampar ett to-datum i framtiden till idag`() {
        val url = AvanzaClient.chartUrl(
            orderbookId = "236069",
            from = LocalDate.of(2026, 7, 1),
            to = LocalDate.of(2026, 7, 31),
            today = LocalDate.of(2026, 7, 20),
        )

        assertEquals(
            "https://www.avanza.se/_api/fund-guide/chart/236069/2026-07-01/2026-07-20?raw=true&resolution=DAY",
            url,
        )
    }

    @Test
    fun `chartUrl lamnar ett to-datum i det forflutna orort`() {
        val url = AvanzaClient.chartUrl(
            orderbookId = "1",
            from = LocalDate.of(2020, 1, 1),
            to = LocalDate.of(2020, 6, 1),
            today = LocalDate.of(2026, 7, 20),
        )

        assertEquals(
            "https://www.avanza.se/_api/fund-guide/chart/1/2020-01-01/2020-06-01?raw=true&resolution=DAY",
            url,
        )
    }
}
