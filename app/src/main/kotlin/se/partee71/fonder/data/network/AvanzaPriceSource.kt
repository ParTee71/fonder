package se.partee71.fonder.data.network

import se.partee71.fonder.domain.model.IsinPricePoint
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ISIN-baserad kurskälla mot avanza.se (se KRAVLISTA TP-14). Två steg: slå upp fondens
 * `orderbookId` (+ valuta) via sök/guide-anropen, hämta sedan daglig kurshistorik för det
 * ID:t. Ett fel i något steg ger tomt resultat (aldrig en krasch) — anroparen
 * (`FundPriceRepository`) provar nästa källa i fallback-kedjan eller behåller cachen.
 */
@Singleton
class AvanzaPriceSource @Inject constructor(
    private val source: AvanzaSource,
) : IsinPriceHistorySource {

    override suspend fun fetchHistory(isin: String, from: LocalDate, to: LocalDate): List<IsinPricePoint> {
        val match = AvanzaJsonParser.findByIsin(source.search(isin), isin) ?: return emptyList()
        val currency = AvanzaJsonParser.parseCurrency(source.fetchGuide(match.orderbookId)) ?: "SEK"
        return AvanzaJsonParser.parseChart(source.fetchChart(match.orderbookId, from, to), currency)
    }

    override suspend fun suggestIsin(fundName: String): String? =
        AvanzaJsonParser.bestMatch(source.search(fundName))?.isin
}
