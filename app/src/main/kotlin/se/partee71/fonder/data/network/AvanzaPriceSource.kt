package se.partee71.fonder.data.network

import se.partee71.fonder.domain.model.IsinFundInfo
import se.partee71.fonder.domain.model.IsinPricePoint
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ISIN-baserad kurskälla mot avanza.se (se KRAVLISTA TP-14). Slår upp fondens `orderbookId`
 * (+ valuta) via sök/guide-anropen, hämtar sedan daglig kurshistorik för det ID:t eller
 * exponerar namn/valuta direkt ([findFund], för fonder utanför Handelsbankens katalog, se
 * TP-13). Ett fel i något steg ger tomt resultat (aldrig en krasch) — anroparen
 * (`FundPriceRepository`) provar nästa källa i fallback-kedjan eller behåller cachen.
 */
@Singleton
class AvanzaPriceSource @Inject constructor(
    private val source: AvanzaSource,
) : IsinPriceHistorySource {

    override suspend fun fetchHistory(isin: String, from: LocalDate, to: LocalDate): List<IsinPricePoint> {
        val (match, currency) = resolve(isin) ?: return emptyList()
        return AvanzaJsonParser.parseChart(source.fetchChart(match.orderbookId, from, to), currency)
    }

    override suspend fun suggestIsin(fundName: String): String? =
        AvanzaJsonParser.bestMatch(source.search(fundName))?.isin

    override suspend fun findFund(isin: String): IsinFundInfo? {
        val (match, currency) = resolve(isin) ?: return null
        return IsinFundInfo(name = match.name, currency = currency)
    }

    private suspend fun resolve(isin: String): Pair<AvanzaJsonParser.SearchMatch, String>? {
        val match = AvanzaJsonParser.findByIsin(source.search(isin), isin) ?: return null
        val currency = AvanzaJsonParser.parseCurrency(source.fetchGuide(match.orderbookId)) ?: "SEK"
        return match to currency
    }
}
