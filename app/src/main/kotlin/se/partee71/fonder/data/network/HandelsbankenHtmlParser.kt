package se.partee71.fonder.data.network

import org.jsoup.Jsoup
import se.partee71.fonder.domain.model.Fund
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
     * Parsar `<select id="FundId">` till Handelsbankens egna fonder. Plattformen är delad
     * med andra fondbolag — Handelsbankens fonder identifieras genom att deras `FundId`
     * börjar med "SHB" (verifierat mot riktig sidkälla i #2).
     */
    fun parseHandelsbankenFundCatalog(html: String): List<Fund> {
        val doc = Jsoup.parse(html)
        return doc.select("select#FundId option[value]").mapNotNull { option ->
            val id = option.attr("value").trim()
            if (id.isEmpty() || !id.startsWith("SHB", ignoreCase = true)) return@mapNotNull null
            val name = option.text().trim()
            if (name.isEmpty()) return@mapNotNull null
            Fund(fundId = id, name = name)
        }
    }

    /** Svenskt talformat: mellanslag (vanligt eller hårt,  ) som tusentalsavgränsare, komma som decimal. */
    internal fun parseSwedishNumber(raw: String): Double? {
        val cleaned = raw.trim()
            .replace(" ", "")
            .replace(" ", "")
            .replace(",", ".")
        return cleaned.toDoubleOrNull()
    }
}
