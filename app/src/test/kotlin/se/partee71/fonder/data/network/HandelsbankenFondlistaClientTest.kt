package se.partee71.fonder.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Enhetstest för de rena URL-byggarna i [HandelsbankenFondlistaClient] (nätverksdelen kräver
 * riktig HTTP och testas inte här). Vaktar `company`-parametern: den *filtrerar* källans
 * fondlista (KRAVLISTA TP-18, issue #37), så ett hårdkodat `company=1` kapar katalogen från
 * hela plattformens ~1500 fonder till Handelsbankens ~470. Den får därför bara skickas när
 * anroparen faktiskt bett om ett bolag.
 */
class HandelsbankenFondlistaClientTest {

    private val from = LocalDate.of(2021, 7, 27)
    private val to = LocalDate.of(2026, 7, 27)

    @Test
    fun `utan fondbolag skickas ingen company-parameter — hela katalogen`() {
        val url = HandelsbankenFondlistaClient.buildHistoryUrl(fundId = null, company = null, from = from, to = to)

        assertFalse("company=" in url)
        assertEquals(
            "https://handelsbanken.fondlista.se/shb/sv/history" +
                "?startdate=2021-07-27+00%3A00%3A00&enddate=2026-07-27+00%3A00%3A00&s=nav",
            url,
        )
    }

    @Test
    fun `med fondbolag skickas company-parametern`() {
        val url = HandelsbankenFondlistaClient.buildHistoryUrl(fundId = null, company = "1372", from = from, to = to)

        assertTrue("company=1372" in url)
    }

    @Test
    fun `tomt fondbolag behandlas som inget fondbolag`() {
        val url = HandelsbankenFondlistaClient.buildHistoryUrl(fundId = null, company = "", from = from, to = to)

        assertFalse("company=" in url)
    }

    @Test
    fun `kurshistorik for en fond behover inget fondbolag`() {
        val url = HandelsbankenFondlistaClient.buildHistoryUrl(fundId = "0P0001KRE7", company = null, from = from, to = to)

        assertTrue("fundid=0P0001KRE7" in url)
        assertFalse("company=" in url)
    }

    @Test
    fun `fondsidans url pekar pa fondens egen sida`() {
        assertEquals(
            "https://handelsbanken.fondlista.se/shb/sv/funds/0P0001KRE7?hb=true&sa=2",
            HandelsbankenFondlistaClient.fundUrl("0P0001KRE7"),
        )
    }
}
