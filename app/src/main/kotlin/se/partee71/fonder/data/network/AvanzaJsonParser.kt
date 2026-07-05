package se.partee71.fonder.data.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.partee71.fonder.domain.model.IsinPricePoint
import java.time.Instant
import java.time.ZoneOffset

/**
 * Parsar JSON-svar från avanza.se:s fond-API. Isolerad från [AvanzaClient] så ett brott i
 * källans format är lätt att lokalisera/fixa (samma princip som [HandelsbankenHtmlParser]).
 */
object AvanzaJsonParser {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class SearchResponse(val fundSearchViews: List<FundSearchView> = emptyList())

    @Serializable
    private data class FundSearchView(val isin: String, val name: String, val orderbookId: String)

    @Serializable
    private data class GuideResponse(val currency: String? = null)

    @Serializable
    private data class ChartResponse(val dataSerie: List<ChartPoint> = emptyList())

    @Serializable
    private data class ChartPoint(val x: Long, val y: Double? = null)

    data class SearchMatch(val isin: String, val orderbookId: String, val name: String)

    /** Exakt träff på [isin] bland sökresultaten (skiftlägesokänsligt), eller null. */
    fun findByIsin(responseJson: String, isin: String): SearchMatch? =
        searchViews(responseJson)
            .firstOrNull { it.isin.equals(isin, ignoreCase = true) }
            ?.toMatch()

    /** Bästa (första, mest relevanta) träffen bland sökresultaten — används för namnsökning. */
    fun bestMatch(responseJson: String): SearchMatch? =
        searchViews(responseJson).firstOrNull()?.toMatch()

    /** Fondens valuta, eller null om svaret saknar/inte kunde tolkas. */
    fun parseCurrency(responseJson: String): String? =
        runCatching { json.decodeFromString<GuideResponse>(responseJson) }.getOrNull()?.currency

    /** Daglig kurshistorik i [currency] (kräver `raw=true` i anropet — annars procentavkastning). Saknade (null) punkter filtreras bort. */
    fun parseChart(responseJson: String, currency: String): List<IsinPricePoint> =
        runCatching { json.decodeFromString<ChartResponse>(responseJson) }
            .getOrNull()
            ?.dataSerie
            ?.mapNotNull { point ->
                val nav = point.y ?: return@mapNotNull null
                val epochDay = Instant.ofEpochMilli(point.x).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                IsinPricePoint(epochDay = epochDay, nav = nav, currency = currency)
            }
            ?: emptyList()

    private fun searchViews(responseJson: String): List<FundSearchView> =
        runCatching { json.decodeFromString<SearchResponse>(responseJson) }
            .getOrNull()
            ?.fundSearchViews
            ?: emptyList()

    private fun FundSearchView.toMatch() = SearchMatch(isin = isin, orderbookId = orderbookId, name = name)
}
