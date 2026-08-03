package se.partee71.fonder.data.network

import se.partee71.fonder.domain.model.FundPrice
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

    /**
     * Chart-svaret är **alltid i kronor** — värdet för en svensk sparare — oavsett fondens
     * egen valuta. Verifierat 2026-07-28: CPR Invest Global Gold Mines (USD-fond) hade NAV
     * 193,48 USD hos fondlista samma dag som Avanza gav 1878,75; kvoten är växelkursen. För
     * rena SEK-fonder är talen identiska hos båda källorna.
     *
     * Punkterna märks därför [FundPrice.VALUE_CURRENCY], inte valutan ur `guide` (som är
     * fondens egen och tidigare sattes här — en felmärkning som inte märktes så länge allt
     * ändå var kronor, men som gör det omöjligt att sålla bort kurser i fel valuta, issue #41).
     *
     * Av samma skäl hämtas `guide` **inte** här: valutan används inte, men anropet kunde fälla
     * hela kurshämtningen. Svarade källan 500/404 på `/guide` medan `/chart` var friskt kastade
     * `fetchHistory` i stället för att lämna kursdatan, och fonden slutade uppdateras på grund
     * av en endpoint vars svar ändå kastades bort. Bara [findFund] behöver valutan.
     */
    override suspend fun fetchHistory(isin: String, from: LocalDate, to: LocalDate): List<IsinPricePoint> {
        val match = AvanzaJsonParser.findByIsin(source.search(isin), isin) ?: return emptyList()
        return AvanzaJsonParser.parseChart(
            source.fetchChart(match.orderbookId, from, to),
            FundPrice.VALUE_CURRENCY,
        )
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
