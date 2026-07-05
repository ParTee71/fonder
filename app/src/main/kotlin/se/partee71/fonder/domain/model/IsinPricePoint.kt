package se.partee71.fonder.domain.model

/**
 * En daglig kurspunkt från en ISIN-baserad källa (Avanza m.fl., se KRAVLISTA TP-14) —
 * innan den kopplas till appens [Fund.fundId] och skrivs in i den delade `fund_prices`-cachen.
 */
data class IsinPricePoint(
    val epochDay: Long,
    val nav: Double,
    val currency: String,
)
