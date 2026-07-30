package se.partee71.fonder.domain.model

/**
 * En fråga mot fondmetadata-källan (KRAVLISTA TP-21) — fondtyp, region, bransch, fondbolag och
 * risknivå filtrerar (flera värden inom samma fält = OR, olika fält = AND — källans verifierade
 * semantik), plus avgiftstak, fritextsök på namn, sortering och sidoffset.
 *
 * De kategoriska filtren ([fundType]/[region]/[industry]/[company]/[risk]) måste innehålla
 * titlar som finns i den senast kända [FundFilterVocabulary] — de skickas rakt av mot källan
 * (och mot [se.partee71.fonder.domain.usecase.FundScreenFilter] offline), aldrig validerade
 * mot en hårdkodad lista.
 *
 * @param startIndex Källans paginering — sidstorleken är hårt låst till 20 (verifierat, se
 *   KRAVLISTA TP-21), går inte att ändra.
 */
data class FundScreenQuery(
    val fundType: List<String> = emptyList(),
    val region: List<String> = emptyList(),
    val industry: List<String> = emptyList(),
    val company: List<String> = emptyList(),
    val risk: List<String> = emptyList(),
    val maxTotalFee: Double? = null,
    val nameContains: String? = null,
    val sortField: String? = null,
    val sortDirection: FundScreenSortDirection? = null,
    val startIndex: Int = 0,
) {
    /**
     * Sant om frågan har minst ett kategoriskt filter satt — de filtren är de enda med
     * verifierat "fail open"-beteende hos källan (okänt filternamn ignoreras tyst i stället
     * för att ge fel), så bara de är värda att baslinjejämföra i
     * [se.partee71.fonder.data.repository.FundMetadataRepository] (TP-21).
     */
    fun hasCategoricalFilters(): Boolean =
        fundType.isNotEmpty() || region.isNotEmpty() || industry.isNotEmpty() || company.isNotEmpty() || risk.isNotEmpty()
}

enum class FundScreenSortDirection { ASCENDING, DESCENDING }
