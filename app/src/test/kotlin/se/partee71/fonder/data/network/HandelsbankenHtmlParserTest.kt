package se.partee71.fonder.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Fixturerna nedan är trimmade utdrag av verklig markup från handelsbanken.fondlista.se
 * (verifierad i spike-issue #2 mot en riktig sidkälla från användaren, 2026-07-02).
 * Jsoup:s HTML5-parser kräver en `<table>`-omslutning runt `<tbody>`/`<tr>`/`<td>` för att
 * bygga trädet korrekt — fragment utan den foster-parentas och missas av selektorer.
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
            <table><tbody>
            <tr class="funds-data">
                <td class="name "><span class="arrow" id="SHB0000442"></span></td>
                <td class="positive">1${' '}563,19</td>
                <td class="left">SEK</td>
                <td>2026-07-01</td>
            </tr>
            </tbody></table>
        """.trimIndent()

        val prices = HandelsbankenHtmlParser.parseHistory(html, fundId = "SHB0000442")

        assertEquals(1, prices.size)
        assertEquals(1563.19, prices[0].nav, 1e-9)
    }

    @Test
    fun `parseHistory hoppar over rader med trasig data`() {
        val html = """
            <table><tbody>
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
            </tbody></table>
        """.trimIndent()

        val prices = HandelsbankenHtmlParser.parseHistory(html, fundId = "SHB0000442")

        assertEquals(0, prices.size)
    }

    @Test
    fun `parseFundCatalog ar ofiltrerad och tar med alla fondbolags fonder`() {
        val html = """
            <select id="FundId" name="FundId" style="width:230px;"><option value="">Välj fond</option>
            <option value="0P000083RV">AstraZeneca Allemansfond</option>
            <option value="0P00017JMP">Handelsbanken Aktiv 100 (A1 NOK)</option>
            <option value="SHB0000625">Handelsbanken Aktiv 100 (A14 NOK)</option>
            <option selected="selected" value="SHB0000627">Handelsbanken Aktiv 50 (A14 NOK)</option>
            <option value="SHB0000442">Handelsbanken Amerika Småbolag Tema</option>
            </select>
        """.trimIndent()

        val catalog = HandelsbankenHtmlParser.parseFundCatalog(html)

        // Filtrering per fondbolag görs av källan via `company`-parametern (TP-18), inte här —
        // parsern läser bara det som levererats, och tar bara bort det tomma valet.
        assertEquals(5, catalog.size)
        assertEquals("Handelsbanken Amerika Småbolag Tema", catalog.first { it.fundId == "SHB0000442" }.name)
        assertTrue(catalog.any { it.fundId == "0P000083RV" })
    }

    @Test
    fun `parseIsin laser isin ur fondsidans faktabladslank`() {
        // Verkligt utdrag från /shb/sv/funds/0p0001kre7 (verifierat live 2026-07-27): sidan har
        // inget eget ISIN-fält, men faktabladslänken bär det som Identifier-parameter.
        val html = """
            <div class="fund-page">
              <h1>CPR Invest Global Gold Mines A USD Acc</h1>
              <a class="icon pdf" href="https://handelsbanken.modelity.com/Structures/external/public/preorder-exante-costs-and-charges?ProductType=19&amp;IdentifierType=1&amp;Identifier=LU1989766289&amp;Country=SE&amp;Language=SE_sv&amp;Channel=1">Kostnader och avgifter</a>
            </div>
        """.trimIndent()

        assertEquals("LU1989766289", HandelsbankenHtmlParser.parseIsin(html))
    }

    @Test
    fun `parseIsin laser svenskt isin aven med oescapade parametrar`() {
        // Källan skriver länken med rå `&` (verifierat live) — men `&amp;` ska funka lika bra,
        // och `IdentifierType=` får aldrig förväxlas med `Identifier=`.
        val html = """<a href="/x?IdentifierType=1&Identifier=SE0000582033&Country=SE">Faktablad</a>"""

        assertEquals("SE0000582033", HandelsbankenHtmlParser.parseIsin(html))
    }

    @Test
    fun `parseIsin hittar ett entydigt isin aven utan faktabladslank`() {
        val html = """<table><tr><th>ISIN</th><td>SE0010921338</td></tr></table>"""

        assertEquals("SE0010921338", HandelsbankenHtmlParser.parseIsin(html))
    }

    @Test
    fun `parseIsin ar null nar sidan saknar isin`() {
        val html = """<div><h1>Handelsbanken Sverige</h1><p>Ingen identifierare här.</p></div>"""

        assertNull(HandelsbankenHtmlParser.parseIsin(html))
    }

    @Test
    fun `parseIsin ar null nar flera olika isin ar mojliga utan faktabladslank`() {
        // Hellre inget ISIN än fel ISIN på fel fond — en tvetydig sida ska falla igenom till
        // de ISIN-baserade källorna (TP-14) i stället för att gissa.
        val html = """<div><span>SE0000582033</span><span>LU1989766289</span></div>"""

        assertNull(HandelsbankenHtmlParser.parseIsin(html))
    }

    @Test
    fun `parseIsin plockar inte upp isin-liknande fondid`() {
        // FundId som 0P0001KRE7 är kortare, men en id-sträng i en längre alfanumerisk kontext
        // får aldrig läsas som ISIN.
        val html = """<div data-id="XSE0000582033Q">CPR Invest</div>"""

        assertNull(HandelsbankenHtmlParser.parseIsin(html))
    }

    @Test
    fun `parseFundCompanies laser id och namn per fondbolag, hoppar over tomt val`() {
        val html = """
            <select id="company" name="company" style="width:230px;"><option value="">Välj fondbolag</option>
            <option selected="selected" value="1">Handelsbanken</option>
            <option value="1101">Aberdeen Global Services S.A.</option>
            <option value="1339">AIFM Capital AB</option>
            </select>
        """.trimIndent()

        val companies = HandelsbankenHtmlParser.parseFundCompanies(html)

        assertEquals(3, companies.size)
        assertEquals("Handelsbanken", companies.first { it.id == "1" }.name)
        assertEquals("Aberdeen Global Services S.A.", companies.first { it.id == "1101" }.name)
        assertTrue(companies.none { it.id.isEmpty() })
    }

    @Test
    fun `parseSwedishNumber hanterar komma minus och tomt`() {
        assertEquals(1234.5, HandelsbankenHtmlParser.parseSwedishNumber("1${' '}234,5"))
        assertEquals(-12.3, HandelsbankenHtmlParser.parseSwedishNumber("-12,3"))
        assertNull(HandelsbankenHtmlParser.parseSwedishNumber("—"))
    }
}
