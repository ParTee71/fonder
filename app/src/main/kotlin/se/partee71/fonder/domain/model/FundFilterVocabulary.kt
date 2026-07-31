package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/**
 * Vilka filtervärden som faktiskt finns hos källan just nu, nyckel = filternamnets prefix
 * (t.ex. `"fundType"`, `"commonRegion"`, `"industry"`, `"company"`, `"risk"` — samma sträng
 * som källans `filterCounts[*].type`, se KRAVLISTA TP-21) → lista av giltiga titlar.
 *
 * Byggs **enbart** ur källans eget svar (`AvanzaFundListParser`), aldrig hårdkodad: källan
 * fail:ar closed på ett okänt filtervärde (`fundTypeFilter:["Trams"]` ger noll träffar, inte
 * ett fel), så en hårdkodad titel skulle tyst sluta fungera den dag källan döper om en
 * kategori. Persisteras i [se.partee71.fonder.data.datastore.PreferencesRepository] så den
 * finns kvar mellan sessioner (t.ex. för en framtida fondsök-UI att bygga filterval från).
 */
@Serializable
data class FundFilterVocabulary(
    val filters: Map<String, List<String>> = emptyMap(),
)
