package se.partee71.fonder.data.network

import java.time.LocalDate

/**
 * Källa för rå HTML från handelsbanken.fondlista.se. Abstraherat bort från
 * [HandelsbankenFondlistaClient] så [se.partee71.fonder.data.repository.HandelsbankenFundPriceRepository]
 * kan enhetstestas med en fejk, utan riktigt nätverk.
 */
fun interface FondlistaHtmlSource {
    suspend fun fetchHistoryPage(fundId: String?, from: LocalDate, to: LocalDate): String
}
