package se.partee71.fonder.data.network

import org.jsoup.Jsoup
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCompany
import se.partee71.fonder.domain.model.FundPrice
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Ren HTML-parsning av handelsbanken.fondlista.se (se issue #2/#3, beslut och verifierad
 * markup i #2:s kommentarer). Isolerad i egen fil så ett formatbrott i källan är lätt att
 * lokalisera och laga.
 */
object HandelsbankenHtmlParser {

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** Parsar kurshistorik-tabellen (`tr.funds-data`) på /shb/sv/history. */
    fun parseHistory(html: String, fundId: String): List<FundPrice> {
        val doc = Jsoup.parse(html)
        return doc.select("tr.funds-data").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size < 4) return@mapNotNull null
            val nav = parseSwedishNumber(cells[1].text()) ?: return@mapNotNull null
            val currency = cells[2].text().trim()
            val date = runCatching { LocalDate.parse(cells[3].text().trim(), dateFormatter) }
                .getOrNull() ?: return@mapNotNull null
            FundPrice(fundId = fundId, epochDay = date.toEpochDay(), nav = nav, currency = currency)
        }
    }

    /**
     * Parsar `<select id="FundId">` till fondkatalogen. **Vilka** fonder som finns i listan
     * styrs av `company`-parametern i anropet (se [FondlistaHtmlSource]): utan bolag hela
     * plattformens katalog, med bolag bara det bolagets fonder (KRAVLISTA TP-18). Parsern
     * filtrerar därför inte själv — den läser bara det källan levererat.
     */
    fun parseFundCatalog(html: String): List<Fund> {
        val doc = Jsoup.parse(html)
        return doc.select("select#FundId option[value]").mapNotNull { option ->
            val id = option.attr("value").trim()
            if (id.isEmpty()) return@mapNotNull null
            val name = option.text().trim()
            if (name.isEmpty()) return@mapNotNull null
            Fund(fundId = id, name = name)
        }
    }

    /** Parsar `<select id="company">` till listan av fondbolag på plattformen. */
    fun parseFundCompanies(html: String): List<FundCompany> {
        val doc = Jsoup.parse(html)
        return doc.select("select#company option[value]").mapNotNull { option ->
            val id = option.attr("value").trim()
            if (id.isEmpty()) return@mapNotNull null
            val name = option.text().trim()
            if (name.isEmpty()) return@mapNotNull null
            FundCompany(id = id, name = name)
        }
    }

    /**
     * Parsar fondens **ISIN** ur en fondsida (`/shb/sv/funds/<fundid>`, se
     * [FondlistaFundPageSource]). Källan har inget eget ISIN-fält, men fondsidans
     * faktabladslänkar bär det som `Identifier=`-parameter — den läses i första hand.
     * Saknas den letas en fristående ISIN-formad token i sidan, och accepteras bara om den
     * är **entydig** (exakt ett distinkt värde) — hellre null än fel ISIN på fel fond.
     */
    fun parseIsin(html: String): String? {
        identifierParam.find(html)?.groupValues?.get(1)?.let { return it }
        val candidates = isinToken.findAll(html).map { it.value }.toSet()
        return candidates.singleOrNull()
    }

    /** ISIN: två bokstäver (landkod), nio alfanumeriska tecken, en kontrollsiffra. */
    private const val ISIN_PATTERN = """[A-Z]{2}[A-Z0-9]{9}[0-9]"""

    /** Föregås av `?`, `&` eller `&amp;` — aldrig av en bokstav, så `IdentifierType=` inte träffar. */
    private val identifierParam = Regex("""(?<![A-Za-z])Identifier=($ISIN_PATTERN)(?![A-Z0-9])""")

    /** Fristående token — omgivande alfanumeriska tecken diskvalificerar (undviker träff mitt i en id-sträng). */
    private val isinToken = Regex("""(?<![A-Z0-9])$ISIN_PATTERN(?![A-Z0-9])""")

    /** Svenskt talformat: mellanslag (vanligt eller hårt,  ) som tusentalsavgränsare, komma som decimal. */
    internal fun parseSwedishNumber(raw: String): Double? =
        se.partee71.fonder.domain.usecase.SwedishNumberFormat.parse(raw)
}
