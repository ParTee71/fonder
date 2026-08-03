package se.partee71.fonder.data.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundTag
import java.time.LocalDate

/**
 * Parsar JSON-svar från `POST /_api/fund-guide/list` (avanza.se:s odokumenterade fond-API,
 * samma källa som TP-14, se KRAVLISTA TP-21). Isolerad från [AvanzaClient], samma princip som
 * [AvanzaJsonParser]/[HandelsbankenHtmlParser].
 */
object AvanzaFundListParser {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class FundListResponse(
        val fundListViews: List<FundListView> = emptyList(),
        val totalNoFunds: Int = 0,
        val filterCounts: Map<String, List<FilterCountView>> = emptyMap(),
    )

    @Serializable
    private data class FundListView(
        val isin: String,
        val name: String,
        val orderbookId: String,
        val totalFee: Double? = null,
        val managementFee: Double? = null,
        val category: String? = null,
        val fundType: String? = null,
        val companyName: String? = null,
        val risk: Int? = null,
        val indexFund: Boolean = false,
        val startDate: String? = null,
        val minimumBuy: Double? = null,
        val developmentOneYear: Double? = null,
        val tagList: List<TagView> = emptyList(),
    )

    @Serializable
    private data class TagView(val title: String, val fundTagCategory: String)

    /** [type] är källans egen transportnyckel för dimensionen (t.ex. `"fundType"`) — se [FundFilterVocabulary]. */
    @Serializable
    private data class FilterCountView(val title: String, val type: String)

    data class ParsedFundListPage(
        val funds: List<FundMetadata>,
        val totalNoFunds: Int,
        val vocabulary: FundFilterVocabulary,
    )

    /**
     * **Null** (aldrig en krasch) om svaret inte går att tolka som förväntat — medvetet skilt
     * från ett giltigt svar med noll fonder. `FundListView` har obligatoriska fält, så en enda
     * fondpost utan `isin` (eller ett omdöpt fält hos källan) fäller avkodningen av *hela*
     * svaret; returnerades det som en tom sida såg det ut som ett svar, och
     * `FundMetadataRepository.query` hoppade då över sin offline-fallback: fondsöket visade en
     * tom lista trots full cache, och `lastKnownUnfilteredTotal` förgiftades till 0.
     */
    fun parse(responseJson: String): ParsedFundListPage? {
        val response = runCatching { json.decodeFromString<FundListResponse>(responseJson) }.getOrNull()
            ?: return null

        return ParsedFundListPage(
            funds = response.fundListViews.map { it.toDomain() },
            totalNoFunds = response.totalNoFunds,
            vocabulary = buildVocabulary(response.filterCounts),
        )
    }

    /**
     * Vokabulären byggs enbart ur svarets `type`-fält (redan källans egna transportnycklar,
     * t.ex. `"fundType"`) — ingen hårdkodad dimensionslista, se [FundFilterVocabulary].
     */
    private fun buildVocabulary(filterCounts: Map<String, List<FilterCountView>>): FundFilterVocabulary =
        FundFilterVocabulary(
            filters = filterCounts.values
                .flatten()
                .groupBy { it.type }
                .mapValues { (_, entries) -> entries.map { it.title } },
        )

    private fun FundListView.toDomain() = FundMetadata(
        isin = isin,
        name = name,
        orderbookId = orderbookId,
        totalFee = totalFee,
        managementFee = managementFee,
        category = category,
        fundType = fundType,
        companyName = companyName,
        risk = risk,
        indexFund = indexFund,
        startDateEpochDay = startDate?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() },
        minimumBuy = minimumBuy,
        tags = tagList.map { FundTag(title = it.title, category = it.fundTagCategory) },
        developmentOneYear = developmentOneYear,
    )
}
