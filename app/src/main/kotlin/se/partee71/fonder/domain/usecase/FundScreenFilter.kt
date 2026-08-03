package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.model.FundScreenSortDirection
import se.partee71.fonder.domain.model.FundTag

/**
 * Ren, testbar filtrering/sortering/paginering av [FundMetadata] mot en [FundScreenQuery] —
 * återskapar källans verifierade semantik (KRAVLISTA TP-21): **OR** mellan flera värden inom
 * samma dimension, **AND** mellan olika dimensioner (verifierat live 2026-07-30:
 * `companyFilter` med två bolag gav facken summerade, `commonRegionFilter` + `industryFilter`
 * gav snittet).
 *
 * Används på två ställen i [se.partee71.fonder.data.repository.FundMetadataRepository] — och
 * bara här, så semantiken aldrig kan glida isär mellan dem (regel 4):
 * 1. Offline/nätverksfel — frågan besvaras ur den lokala cachen (ÖV-6).
 * 2. Källan har tyst slutat respektera ett kategoriskt filter (samma `totalNoFunds` som en
 *    obefiltrerad baslinje) — resultatet filtreras om lokalt i stället för att fel urval visas.
 */
object FundScreenFilter {

    /** Källans egen sidstorlek — hårt låst, går inte att ändra (verifierat, TP-21). */
    const val PAGE_SIZE = 20

    /**
     * Källans egna sorteringsnycklar (verifierade live, TP-21) — exakt de strängar som skickas
     * i frågan. Ligger här, hos den enda kod som också kan sortera *lokalt* på dem, så en ny
     * nyckel inte kan införas i en fråga utan att offline-vägen förstår den (se [sort]).
     */
    const val SORT_FIELD_TOTAL_FEE = "totalFee"
    const val SORT_FIELD_DEVELOPMENT_ONE_YEAR = "developmentOneYear"

    /** Filtrerar, sorterar och sidbryter [all] enligt [query] — samma resultatform som ett källsvar. */
    fun apply(all: List<FundMetadata>, query: FundScreenQuery): List<FundMetadata> =
        sort(all.filter { matches(it, query) }, query)
            .drop(query.startIndex)
            .take(PAGE_SIZE)

    /** Sant om [metadata] matchar samtliga satta filter i [query]. */
    fun matches(metadata: FundMetadata, query: FundScreenQuery): Boolean {
        if (query.fundType.isNotEmpty() && !hasTag(metadata, FundTag.CATEGORY_TYPE, query.fundType)) return false
        if (query.region.isNotEmpty() && !hasTag(metadata, FundTag.CATEGORY_COMMON_REGION, query.region)) return false
        if (query.otherRegion.isNotEmpty() && !hasTag(metadata, FundTag.CATEGORY_OTHER_REGION, query.otherRegion)) return false
        if (query.industry.isNotEmpty() && !hasTag(metadata, FundTag.CATEGORY_INDUSTRY, query.industry)) return false
        if (query.alignment.isNotEmpty() && !hasTag(metadata, FundTag.CATEGORY_ALIGNMENT, query.alignment)) return false
        if (query.interestType.isNotEmpty() && !hasTag(metadata, FundTag.CATEGORY_INTEREST, query.interestType)) return false
        if (query.misc.isNotEmpty() && !hasTag(metadata, FundTag.CATEGORY_MISC, query.misc)) return false
        val companyName = metadata.companyName
        if (query.company.isNotEmpty() && (companyName == null || companyName !in query.company)) return false
        val risk = metadata.risk
        if (query.risk.isNotEmpty() && (risk == null || risk.toString() !in query.risk)) return false

        val maxFee = query.maxTotalFee
        val totalFee = metadata.totalFee
        if (maxFee != null && (totalFee == null || totalFee > maxFee)) return false

        val nameFilter = query.nameContains
        if (!nameFilter.isNullOrBlank() && !metadata.name.contains(nameFilter, ignoreCase = true)) return false

        return true
    }

    private fun hasTag(metadata: FundMetadata, category: String, allowedTitles: List<String>): Boolean =
        metadata.tags.any { it.category == category && it.title in allowedTitles }

    /**
     * Sorterar som källan gör. Två fällor som båda gav tyst fel urval — offline-vägen
     * sidbryter (`take(PAGE_SIZE)`) **efter** sorteringen, så en fel sortering betyder att rätt
     * fonder aldrig ens hamnar på sidan, och en lokal omrangordning hos anroparen kan inte
     * rädda det:
     * 1. Ett okänt [FundScreenQuery.sortField] föll till namnsortering. `findSwitchCandidates`
     *    sorterar på `developmentOneYear` (issue #75) — offline gav det reverst alfabetisk
     *    ordning, alltså 20 godtyckliga fonder i stället för nivåns bästa.
     * 2. `asReversed()` inverterade även `nullsLast`, så fonder med **okänt** värde hamnade
     *    först i fallande ordning. Riktningen läggs därför på komparatorn, och okänt värde
     *    sorteras alltid sist oavsett riktning.
     */
    private fun sort(list: List<FundMetadata>, query: FundScreenQuery): List<FundMetadata> {
        val descending = query.sortDirection == FundScreenSortDirection.DESCENDING
        val numeric: ((FundMetadata) -> Double?)? = when (query.sortField) {
            SORT_FIELD_TOTAL_FEE -> FundMetadata::totalFee
            SORT_FIELD_DEVELOPMENT_ONE_YEAR -> FundMetadata::developmentOneYear
            else -> null
        }
        if (numeric == null) {
            val byName = list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            return if (descending) byName.asReversed() else byName
        }
        // Ett nullsLast i komparatorn skulle vändas med riktningen — dela i stället upp listan,
        // så okänt värde alltid hamnar sist.
        val (known, unknown) = list.partition { numeric(it) != null }
        val sortedKnown = known.sortedBy { numeric(it) }.let { if (descending) it.asReversed() else it }
        return sortedKnown + unknown.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}
