package se.partee71.fonder.data.imports

import se.partee71.fonder.domain.model.ImportedOrderTransaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.SwedishNumberFormat

/**
 * Parsar Handelsbankens "Avräkningsnota" — en PDF-orderbekräftelse för en enskild
 * fondtransaktion (issue #8-uppföljning). Isolerad i en egen fil — precis som
 * [HoldingsImportParser] och [se.partee71.fonder.data.network.HandelsbankenHtmlParser] —
 * eftersom det är ett odokumenterat, layoutberoende format som kan ändras.
 *
 * Tar emot redan extraherad text (se [PdfTextExtractor]) i stället för råa PDF-bytes, så
 * parsningslogiken kan enhetstestas med fixturer utan det tunga PDF-biblioteket.
 *
 * Antaganden om layouten (verifierat mot en riktig avräkningsnota):
 * - Fondbolag och fondnamn står på de två raderna direkt före "ISIN: ...".
 * - Varje transaktionsrad börjar med "In" (köp) eller "Ut" (sälj), följt av valfri text,
 *   ett datum (ÅÅÅÅ-MM-DD), och fyra tal (belopp, kurs, andelar, saldo andelar).
 * - Rader som "Ingående saldo" eller "Marknadsvärde" saknar "In"/"Ut"-prefixet och
 *   matchar därför aldrig — de är balanser, inte transaktioner.
 * - En fil kan innehålla flera transaktionsrader (t.ex. flera delleveranser av en order),
 *   men antas gälla en enda fond (första ISIN i filen).
 */
object AvrakningsnotaPdfParser {

    private val isinRegex = Regex("""ISIN:\s*([A-Z]{2}[A-Z0-9]{9}\d)""")
    private const val NUMBER = """[0-9][0-9\s ]*[.,]\d{2,4}"""
    private val transactionLineRegex = Regex(
        """^(In|Ut)\s+.+?\s+(\d{4}-\d{2}-\d{2})\s+($NUMBER)\s+($NUMBER)\s+($NUMBER)\s+($NUMBER)\s*$""",
    )

    /** Tomt om ingen ISIN hittas i texten alls — inget att importera från filen. */
    fun parse(text: String, sourceFileName: String): List<ImportedOrderTransaction> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val isinLineIndex = lines.indexOfFirst { isinRegex.containsMatchIn(it) }
        if (isinLineIndex < 0) return emptyList()
        val isin = isinRegex.find(lines[isinLineIndex])!!.groupValues[1]
        val fundName = lines.getOrNull(isinLineIndex - 1).orEmpty()
        val fundCompanyName = lines.getOrNull(isinLineIndex - 2).orEmpty()

        return lines.mapNotNull { line ->
            parseTransactionLine(line, isin, fundName, fundCompanyName, sourceFileName)
        }
    }

    private fun parseTransactionLine(
        line: String,
        isin: String,
        fundName: String,
        fundCompanyName: String,
        sourceFileName: String,
    ): ImportedOrderTransaction? {
        val match = transactionLineRegex.find(line) ?: return null
        val (typeToken, dateRaw, amountRaw, priceRaw, sharesRaw) = match.groupValues.drop(1)

        val type = when (typeToken) {
            "In" -> TransactionType.KOP
            "Ut" -> TransactionType.SALJ
            else -> return null
        }
        val epochDay = runCatching { java.time.LocalDate.parse(dateRaw).toEpochDay() }.getOrNull() ?: return null
        val amount = SwedishNumberFormat.parse(amountRaw) ?: return null
        val price = SwedishNumberFormat.parse(priceRaw) ?: return null
        val shares = SwedishNumberFormat.parse(sharesRaw) ?: return null
        if (shares <= 0.0 || price <= 0.0) return null

        return ImportedOrderTransaction(
            isin = isin,
            fundCompanyName = fundCompanyName,
            fundName = fundName,
            type = type,
            epochDay = epochDay,
            shares = shares,
            pricePerShare = price,
            amount = amount,
            sourceFileName = sourceFileName,
        )
    }
}
