package se.partee71.fonder.domain.model

/**
 * En enskild fondtransaktion parsad ur en Handelsbanken-avräkningsnota (PDF-orderbekräftelse,
 * se [se.partee71.fonder.data.imports.AvrakningsnotaPdfParser]). Till skillnad från
 * [ImportedHoldingRow] (en aggregerad innehavssnapshot med uppskattat inköpsdatum) är detta
 * en exakt, redan bokförd transaktion — datum, kurs och antal andelar kommer direkt från
 * banken, ingen uppskattning behövs.
 */
data class ImportedOrderTransaction(
    val isin: String,
    val fundCompanyName: String,
    val fundName: String,
    val type: TransactionType,
    val epochDay: Long,
    val shares: Double,
    val pricePerShare: Double,
    val amount: Double,
    val sourceFileName: String,
)
