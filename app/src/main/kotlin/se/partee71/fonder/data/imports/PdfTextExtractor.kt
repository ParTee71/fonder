package se.partee71.fonder.data.imports

/**
 * Extraherar textinnehållet ur en PDF-fil. Abstraherat bort från PDFBox-implementationen så
 * [AvrakningsnotaPdfParser]s anropare kan enhetstestas med en fejk, utan det tunga
 * PDF-biblioteket (samma princip som [se.partee71.fonder.data.network.FondlistaHtmlSource]).
 */
fun interface PdfTextExtractor {
    fun extractText(bytes: ByteArray): String
}
