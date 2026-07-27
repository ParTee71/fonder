package se.partee71.fonder.data.network

import java.time.LocalDate

/**
 * Källa för rå HTML från handelsbanken.fondlista.se:s `history`-sida. Abstraherat bort från
 * [HandelsbankenFondlistaClient] så [se.partee71.fonder.data.repository.HandelsbankenFundPriceRepository]
 * kan enhetstestas med en fejk, utan riktigt nätverk.
 *
 * [company] styr vilken fondkatalog sidan levererar i `select#FundId` (se KRAVLISTA TP-18):
 * `null` ger **hela plattformens** katalog, ett bolags-id ger bara det bolagets fonder.
 * Kurstabellen för ett givet [fundId] påverkas inte av [company] — den är alltid samma.
 */
fun interface FondlistaHtmlSource {
    suspend fun fetchHistoryPage(fundId: String?, company: String?, from: LocalDate, to: LocalDate): String
}

/**
 * Källa för en enskild fonds sida (`/shb/sv/funds/<fundid>`), som — till skillnad från
 * `history`-sidan — bär fondens **ISIN** (se KRAVLISTA TP-9/TP-18). Egen `fun interface`
 * i stället för en till metod på [FondlistaHtmlSource], så bägge kan SAM-fejkas var för sig
 * i tester.
 */
fun interface FondlistaFundPageSource {
    suspend fun fetchFundPage(fundId: String): String
}
