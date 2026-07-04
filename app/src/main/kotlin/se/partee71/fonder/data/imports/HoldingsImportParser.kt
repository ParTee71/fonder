package se.partee71.fonder.data.imports

import org.w3c.dom.Element
import se.partee71.fonder.domain.model.ImportedHoldingRow
import se.partee71.fonder.domain.usecase.SwedishNumberFormat
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parsar Handelsbankens "Innehav Fonder"-export till [ImportedHoldingRow] (issue #8).
 * Isolerad i en egen fil — precis som [se.partee71.fonder.data.network.HandelsbankenHtmlParser]
 * — eftersom det är ett odokumenterat exportformat som kan ändras.
 *
 * Exporten visade sig i praktiken **inte** vara en riktig zip-baserad `.xlsx` (OOXML) —
 * den är bara det inre kalkylbladets råa XML (samma innehåll/schema som
 * `xl/worksheets/sheet1.xml` i en uppackad `.xlsx`, men aldrig zippad). [parse] hanterar
 * därför båda fallen: hittas zip-magibytena (`PK\x03\x04`) i början packas filen upp som
 * vanligt, annars tolkas hela innehållet direkt som kalkylbladets XML.
 *
 * Kolumnordning (headerrad med "ISIN" i kolumn A): ISIN, Kortnamn, Värdepapper, Antal,
 * Senast, Valuta, Kursdatum, Marknadsvärde (SEK), Anskaffningsvärde (SEK),
 * Värdeutveckling (SEK). Antal m.fl. lagras ibland som inline-textsträngar med svenskt
 * decimalkomma, ibland som numeriska celler med punkt — [SwedishNumberFormat.parse]
 * hanterar båda (komma-ersättning är ofarlig på redan punkt-separerade tal).
 */
object HoldingsImportParser {

    private val dateFormatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK\x03\x04"

    /** Läser en exportfil (t.ex. från en filväljare) och returnerar de parsade raderna. */
    fun parse(input: InputStream): List<ImportedHoldingRow> {
        val bytes = input.readBytes()
        return if (isZip(bytes)) parseZip(bytes) else parseSheetXml(bytes.toString(Charsets.UTF_8), sharedStrings = emptyList())
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= ZIP_MAGIC.size && bytes.take(ZIP_MAGIC.size).toByteArray().contentEquals(ZIP_MAGIC)

    private fun parseZip(bytes: ByteArray): List<ImportedHoldingRow> {
        var sheetXml: String? = null
        var sharedStringsXml: String? = null

        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name.matches(Regex("""xl/worksheets/sheet\d+\.xml""")) && sheetXml == null ->
                        sheetXml = zip.readBytes().toString(Charsets.UTF_8)
                    entry.name == "xl/sharedStrings.xml" ->
                        sharedStringsXml = zip.readBytes().toString(Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
        }

        val xml = sheetXml ?: return emptyList()
        val sharedStrings = sharedStringsXml?.let { parseSharedStrings(it) } ?: emptyList()
        return parseSheetXml(xml, sharedStrings)
    }

    internal fun parseSharedStrings(xml: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream(Charsets.UTF_8))
        val items = doc.getElementsByTagName("si")
        return (0 until items.length).map { i ->
            val si = items.item(i) as Element
            val directText = si.getElementsByTagName("t")
            (0 until directText.length).joinToString("") { directText.item(it).textContent }
        }
    }

    internal fun parseSheetXml(xml: String, sharedStrings: List<String>): List<ImportedHoldingRow> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream(Charsets.UTF_8))
        val rowNodes = doc.getElementsByTagName("row")

        val rows = (0 until rowNodes.length).map { i -> parseRow(rowNodes.item(i) as Element, sharedStrings) }
        val headerIndex = rows.indexOfFirst { it["A"]?.trim() == "ISIN" }
        if (headerIndex == -1) return emptyList()

        return rows.drop(headerIndex + 1)
            .takeWhile { !it["A"].isNullOrBlank() }
            .mapNotNull { cells -> toHoldingRow(cells) }
    }

    private fun parseRow(row: Element, sharedStrings: List<String>): Map<String, String> {
        val cellNodes = row.getElementsByTagName("c")
        val cells = mutableMapOf<String, String>()
        for (i in 0 until cellNodes.length) {
            val cell = cellNodes.item(i) as Element
            val ref = cell.getAttribute("r")
            val column = ref.takeWhile { it.isLetter() }
            if (column.isBlank()) continue
            cells[column] = cellText(cell, sharedStrings)
        }
        return cells
    }

    private fun cellText(cell: Element, sharedStrings: List<String>): String {
        return when (cell.getAttribute("t")) {
            "inlineStr" -> firstChildText(cell, "is") ?: ""
            "s" -> firstChildText(cell, "v")
                ?.toIntOrNull()
                ?.let { sharedStrings.getOrNull(it) }
                ?: ""
            else -> firstChildText(cell, "v") ?: ""
        }
    }

    private fun firstChildText(parent: Element, tagName: String): String? {
        val children = parent.getElementsByTagName(tagName)
        return if (children.length > 0) children.item(0).textContent else null
    }

    private fun toHoldingRow(cells: Map<String, String>): ImportedHoldingRow? {
        val isin = cells["A"]?.trim().orEmpty()
        if (isin.isEmpty()) return null
        val shares = cells["D"]?.let(SwedishNumberFormat::parse) ?: return null
        val acquisitionValue = cells["I"]?.let(SwedishNumberFormat::parse) ?: return null
        if (shares == 0.0) return null
        val quoteDate = cells["G"]?.trim()?.let { runCatching { LocalDate.parse(it, dateFormatter) }.getOrNull() }
        return ImportedHoldingRow(
            isin = isin,
            fundCompanyName = cells["B"]?.trim().orEmpty(),
            fundName = cells["C"]?.trim().orEmpty(),
            shares = shares,
            latestNav = cells["E"]?.let(SwedishNumberFormat::parse) ?: 0.0,
            currency = cells["F"]?.trim().orEmpty(),
            quoteEpochDay = quoteDate?.toEpochDay() ?: LocalDate.now().toEpochDay(),
            marketValue = cells["H"]?.let(SwedishNumberFormat::parse) ?: 0.0,
            acquisitionValue = acquisitionValue,
        )
    }
}
