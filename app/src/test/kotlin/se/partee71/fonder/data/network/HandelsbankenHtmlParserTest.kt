package se.partee71.fonder.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Fixturerna nedan är trimmade utdrag av verklig markup från handelsbanken.fondlista.se
 * (verifierad i spike-issue #2 mot en riktig sidkälla från användaren, 2026-07-02).
 */
class HandelsbankenHtmlParserTest {

    @Test
    fun `parseHistory laser namn kurs valuta och datum per rad`() {
        val html = """
            <table>
            <tbody><tr class="header"><th>Namn</th><th>Kurs</th><th>Valuta</th><th>Datum</th></tr>
            <tr class="funds-data">
                <td class="name "><div class="name-div"><span class="arrow" id="SHB0000627"></span> <span><a href="https://handelsbanken.fondlista.se/shb/sv/funds/shb0000627?hb=true&amp;sa=2" title="Handelsbanken Aktiv 50 (A14 NOK)">Handelsbanken Aktiv 50 (A14 NOK)</a></span></div></td>
                <td class="positive">201,68</td>
                <td style="text-align: left" class="left">NOK</td>
                <td>2026-07-01</td>
            </tr>
            <tr class="funds-data">
                <td class="name "><div class="name-div"><span class="arrow" id="SHB0000627"></span> <span><a href="https://handelsbanken.fondlista.se/shb/sv/funds/shb0000627?hb=true&amp;sa=2" title="Handelsbanken Aktiv 50 (A14 NOK)">Handelsbanken Aktiv 50 (A14 NOK)</a></span></div></td>
                <td class="negative">200,65</td>
                <td style="text-align: left" class="left">NOK</td>
                <td>2026-06-30</td>
            </tr>
            </tbody>
            </table>
        """.trimIndent()

        val prices = HandelsbankenHtmlParser.parseHistory(html, fundId = "SHB0000627")

        assertEquals(2, prices.size)
        assertEquals("SHB0000627", prices[0].fundId)
        assertEquals(201.68, prices[0].nav, 1e-9)
        assertEquals("NOK", prices[0].currency)
        assertEquals(LocalDate.of(2026, 7, 1).toEpochDay(), prices[0].epochDay)
        assertEquals(200.65, prices[1].nav, 1e-9)
    }

    @Test
    fun `parseHistory hanterar tusentalsavgransare med hart mellanslag`() {
        val html = """
            <tbody>
            <tr class="funds-data">
                <td class="name "><span class="arrow" id="SHB0000442"></span></td>
                <td class="positive">1${' '}563,19</td>
                <td class="left">SEK</td>
                <td>2026-07-01</td>
            </tr>
            </tbody>
        """.trimIndent()

        val prices = HandelsbankenHtmlParser.parseHistory(html, fundId = "SHB0000442")

        assertEquals(1, prices.size)
        assertEquals(1563.19, prices[0].nav, 1e-9)
    }

    @Test
    fun `parseHistory hoppar over rader med trasig data`() {
        val html = """
            <tbody>
            <tr class="funds-data">
                <td class="name ">Fond</td>
                <td class="positive">inte-ett-tal</td>
                <td class="left">SEK</td>
                <td>2026-07-01</td>
            </tr>
            <tr class="funds-data">
                <td class="name ">Fond</td>
                <td class="positive">100,00</td>
                <td class="left">SEK</td>
                <td>ogiltigt-datum</td>
            </tr>
            </tbody>
        """.trimIndent()

        val prices = HandelsbankenHtmlParser.parseHistory(html, fundId = "SHB0000442")

        assertEquals(0, prices.size)
    }

    @Test
    fun `parseHandelsbankenFundCatalog filtrerar till SHB-prefixade fonder`() {
        val html = """
            <select id="FundId" name="FundId" style="width:230px;"><option value="">Välj fond</option>
            <option value="0P000083RV">AstraZeneca Allemansfond</option>
            <option value="0P00017JMP">Handelsbanken Aktiv 100 (A1 NOK)</option>
            <option value="SHB0000625">Handelsbanken Aktiv 100 (A14 NOK)</option>
            <option selected="selected" value="SHB0000627">Handelsbanken Aktiv 50 (A14 NOK)</option>
            <option value="SHB0000442">Handelsbanken Amerika Småbolag Tema</option>
            </select>
        """.trimIndent()

        val catalog = HandelsbankenHtmlParser.parseHandelsbankenFundCatalog(html)

        assertEquals(3, catalog.size)
        assertEquals(setOf("SHB0000625", "SHB0000627", "SHB0000442"), catalog.map { it.fundId }.toSet())
        assertEquals("Handelsbanken Amerika Småbolag Tema", catalog.first { it.fundId == "SHB0000442" }.name)
        // Externa fonder (0P-prefix) och tomma val ska inte vara med.
        assertEquals(emptyList<String>(), catalog.filter { it.fundId.startsWith("0P") }.map { it.fundId })
    }

    @Test
    fun `parseSwedishNumber hanterar komma minus och tomt`() {
        assertEquals(1234.5, HandelsbankenHtmlParser.parseSwedishNumber("1${' '}234,5"))
        assertEquals(-12.3, HandelsbankenHtmlParser.parseSwedishNumber("-12,3"))
        assertNull(HandelsbankenHtmlParser.parseSwedishNumber("—"))
    }
}
