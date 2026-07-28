package se.partee71.fonder.data.network

import java.time.LocalDate

/** En dagsnoterad växelkurs mot kronor: `1 <valuta> = [rate] SEK` den [epochDay]. */
data class FxRatePoint(val epochDay: Long, val rate: Double)

/**
 * Källa för historiska växelkurser mot kronor (se KRAVLISTA TP-20). Abstraherad så
 * `FundPriceRepository` kan enhetstestas med en fejk, utan riktigt nätverk — samma princip
 * som [FondlistaHtmlSource] och [IsinPriceHistorySource].
 *
 * Tom lista betyder "inga kurser för den här valutan/perioden" (t.ex. en valuta källan inte
 * noterar) — anroparen får då inte konvertera, utan hoppar över kursen hellre än gissar.
 */
fun interface FxRateSource {
    suspend fun fetchRates(currency: String, from: LocalDate, to: LocalDate): List<FxRatePoint>
}
