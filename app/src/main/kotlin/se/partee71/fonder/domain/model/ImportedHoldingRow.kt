package se.partee71.fonder.domain.model

/**
 * En rad ur en importerad innehavsexport (Handelsbankens "Innehav Fonder"-Excel, issue #8).
 * Identifieras av **ISIN** i källfilen — inte samma sak som appens [Fund.fundId], se
 * [se.partee71.fonder.domain.usecase.FundNameMatcher].
 */
data class ImportedHoldingRow(
    val isin: String,
    val fundCompanyName: String,
    val fundName: String,
    val shares: Double,
    val latestNav: Double,
    val currency: String,
    val quoteEpochDay: Long,
    val marketValue: Double,
    val acquisitionValue: Double,
) {
    /** Snittkurs vid anskaffning — grund för [se.partee71.fonder.domain.usecase.PurchaseDateEstimator]. */
    val averageCostPerShare: Double get() = acquisitionValue / shares
}
