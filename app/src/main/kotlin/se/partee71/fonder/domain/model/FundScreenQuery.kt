package se.partee71.fonder.domain.model

/**
 * En fråga mot fondmetadata-källan (KRAVLISTA TP-21) — fondtyp, region (både [region] för
 * källans breda `COMMON_REGION` och [otherRegion] för den snävare `OTHER_REGION`, t.ex.
 * enskilda länder som saknar en bred region), bransch, inriktning ([alignment], t.ex.
 * "Småbolag"/"Hög utdelning"), räntedimension ([interestType], t.ex. valuta för räntefonder),
 * övrigt ([misc]) och fondbolag filtrerar (flera värden inom samma fält = OR, olika fält = AND
 * — källans verifierade semantik), plus risknivå, avgiftstak, fritextsök på namn (accepterar
 * även ett ISIN, TP-21), sortering och sidoffset.
 *
 * De kategoriska filtren måste innehålla titlar som finns i den senast kända
 * [FundFilterVocabulary] — de skickas rakt av mot källan (och mot
 * [se.partee71.fonder.domain.usecase.FundScreenFilter] offline), aldrig validerade mot en
 * hårdkodad lista.
 *
 * @param startIndex Källans paginering — sidstorleken är hårt låst till 20 (verifierat, se
 *   KRAVLISTA TP-21), går inte att ändra.
 */
data class FundScreenQuery(
    val fundType: List<String> = emptyList(),
    val region: List<String> = emptyList(),
    val otherRegion: List<String> = emptyList(),
    val industry: List<String> = emptyList(),
    val alignment: List<String> = emptyList(),
    val interestType: List<String> = emptyList(),
    val misc: List<String> = emptyList(),
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
        fundType.isNotEmpty() || region.isNotEmpty() || otherRegion.isNotEmpty() || industry.isNotEmpty() ||
            alignment.isNotEmpty() || interestType.isNotEmpty() || misc.isNotEmpty() || company.isNotEmpty() || risk.isNotEmpty()
}

enum class FundScreenSortDirection { ASCENDING, DESCENDING }
