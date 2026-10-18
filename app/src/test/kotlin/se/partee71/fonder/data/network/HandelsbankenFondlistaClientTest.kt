package se.partee71.fonder.data.network

import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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
            url
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
            HandelsbankenFondlistaClient.fundUrl("0P0001KRE7")
        )
    }

    @Test
    fun `brandvaggens kontroll ar ett fel, inte ett tomt svar`() {
        // Issue #153: källan svarade 202 med tom kropp och x-amzn-waf-action — lästes som en tom
        // katalog och blev "ingen fond går att köpa hos Handelsbanken".
        assertThrows(IOException::class.java) { HandelsbankenFondlistaClient.checkResponse(202, "challenge", "u") }
        assertThrows(IOException::class.java) { HandelsbankenFondlistaClient.checkResponse(200, "challenge", "u") }
        assertThrows(IOException::class.java) { HandelsbankenFondlistaClient.checkResponse(202, null, "u") }
        assertThrows(IOException::class.java) { HandelsbankenFondlistaClient.checkResponse(503, null, "u") }
    }

    @Test
    fun `ett vanligt 200-svar godtas`() {
        HandelsbankenFondlistaClient.checkResponse(200, null, "u")
    }

    private val ok = HandelsbankenFondlistaClient.Response(200, null, "<html>katalog</html>")
    private val challenge = HandelsbankenFondlistaClient.Response(202, "challenge", "")

    @Test
    fun `nyckeln skickas med och ett vanligt svar godtas utan ny nyckel`() = runTest {
        val rejections = mutableListOf<String?>()
        val sent = mutableListOf<String?>()

        val body = HandelsbankenFondlistaClient.fetchWithWafToken(
            "u",
            token = { rejected ->
                rejections += rejected
                "T1"
            },
            fetch = { token ->
                sent += token
                ok
            }
        )

        assertEquals("<html>katalog</html>", body)
        assertEquals(listOf<String?>(null), rejections)
        assertEquals(listOf<String?>("T1"), sent)
    }

    @Test
    fun `kontroll trots nyckel ger ny nyckel och ett nytt forsok`() = runTest {
        // Issue #155: nyckeln har gått ut — källan svarar med kontrollen igen.
        val sent = mutableListOf<String?>()

        val body = HandelsbankenFondlistaClient.fetchWithWafToken(
            "u",
            token = { rejected -> if (rejected == "T1") "T2" else "T1" },
            fetch = { token ->
                sent += token
                if (token == "T2") ok else challenge
            }
        )

        assertEquals("<html>katalog</html>", body)
        assertEquals(listOf<String?>("T1", "T2"), sent)
    }

    @Test
    fun `utan nyckel att fa ar kontrollen ett fel, och forsoket gors bara en gang`() {
        var fetches = 0

        assertThrows(IOException::class.java) {
            runBlocking {
                HandelsbankenFondlistaClient.fetchWithWafToken(
                    "u",
                    token = { null },
                    fetch = {
                        fetches++
                        challenge
                    }
                )
            }
        }
        assertEquals(1, fetches)
    }

    @Test
    fun `nyckeln lases ur kakhuvudet`() {
        assertEquals("abc:123", WafToken.fromCookieHeader("__utma=1; aws-waf-token=abc:123; AWSALB=x"))
        assertEquals("abc", WafToken.fromCookieHeader("aws-waf-token=abc"))
        assertNull(WafToken.fromCookieHeader("AWSALB=x"))
        assertNull(WafToken.fromCookieHeader("aws-waf-token="))
        assertNull(WafToken.fromCookieHeader(null))
    }

    @Test
    fun `samma nyckel igen ger inget nytt forsok utan ett fel`() {
        var fetches = 0

        assertThrows(IOException::class.java) {
            runBlocking {
                HandelsbankenFondlistaClient.fetchWithWafToken("u", token = { "T1" }, fetch = {
                    fetches++
                    challenge
                })
            }
        }
        assertEquals(1, fetches)
    }

    @Test
    fun `ingen ny nyckel att fa ger ett fel efter ett forsok`() {
        var fetches = 0

        assertThrows(IOException::class.java) {
            runBlocking {
                HandelsbankenFondlistaClient.fetchWithWafToken(
                    "u",
                    token = { rejected -> if (rejected == null) "T1" else null },
                    fetch = {
                        fetches++
                        challenge
                    }
                )
            }
        }
        assertEquals(1, fetches)
    }
}
