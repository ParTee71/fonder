package se.partee71.fonder.data.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.model.FundScreenSortDirection

/**
 * Bygger JSON-payloaden till `POST /_api/fund-guide/list` (KRAVLISTA TP-21) ur en
 * [FundScreenQuery] — ren och testbar, samma anda som [AvanzaClient.chartUrl]. Bara filter med
 * satta värden tas med; en tom [FundScreenQuery] ger en payload utan filter (hela universumet,
 * källans standardsortering).
 *
 * Filternamnen (`fundTypeFilter`, `commonRegionFilter`, `industryFilter`, `companyFilter`,
 * `riskFilter`) är källans **transportnycklar**, verifierade live 2026-07-30 — till skillnad
 * från filtrens *värden* (som alltid kommer från [se.partee71.fonder.domain.model.FundFilterVocabulary],
 * aldrig hårdkodade här) är dessa nyckelnamn en del av API-kontraktet, i samma kategori som
 * `resolution=DAY` i [AvanzaClient.chartUrl].
 */
object AvanzaFundListRequestBuilder {

    fun buildPayload(query: FundScreenQuery): String =
        buildJsonObject {
            putFilter("fundTypeFilter", query.fundType)
            putFilter("commonRegionFilter", query.region)
            putFilter("industryFilter", query.industry)
            putFilter("companyFilter", query.company)
            putFilter("riskFilter", query.risk)
            query.maxTotalFee?.let { put("maxTotalFee", it) }
            query.nameContains?.let { put("name", it) }
            query.sortField?.let { put("sortField", it) }
            query.sortDirection?.let { put("sortDirection", it.wireValue()) }
            put("startIndex", query.startIndex)
        }.toString()

    private fun JsonObjectBuilder.putFilter(name: String, values: List<String>) {
        if (values.isNotEmpty()) put(name, JsonArray(values.map { JsonPrimitive(it) }))
    }

    private fun FundScreenSortDirection.wireValue(): String = when (this) {
        FundScreenSortDirection.ASCENDING -> "ASCENDING"
        FundScreenSortDirection.DESCENDING -> "DESCENDING"
    }
}
