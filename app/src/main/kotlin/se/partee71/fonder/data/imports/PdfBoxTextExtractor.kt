package se.partee71.fonder.data.imports

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Textextraktion via PdfBox-Android (se KRAVLISTA-risknotis: odokumenterat PDF-layout,
 * text kan brytas annorlunda än förväntat om Handelsbankens mall ändras). Kräver att
 * `PDFBoxResourceLoader.init(context)` har körts (se `FonderApp.onCreate`).
 */
@Singleton
class PdfBoxTextExtractor @Inject constructor() : PdfTextExtractor {
    override fun extractText(bytes: ByteArray): String =
        PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
            PDFTextStripper().getText(document)
        }
}
