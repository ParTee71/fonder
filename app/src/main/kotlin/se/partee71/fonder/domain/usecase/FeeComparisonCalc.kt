package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.model.FundScreenSortDirection
import se.partee71.fonder.domain.model.FundTag

/**
 * Föreslår billigare, likvärdiga alternativ till ett innehav (ANA-9, issue #59) — appens
 * första **rådgivande** funktion (ANA-3:s tidigare "aldrig rådgivning"-princip är struken,
 * se KRAVLISTA). Ren, testbar domänlogik — nätverksanrop, ISIN-uppslag och budgeterad
 * köpbarhetsverifiering sköts av
 * [se.partee71.fonder.data.repository.FundMetadataRepository.suggestCheaperAlternatives].
 */
object FeeComparisonCalc {

    /** [candidate] föreslås i stället för det jämförda innehavet, med [annualSavingsKr] beräknad besparing per år. */
    data class Alternative(
        val candidate: FundMetadata,
        val candidateFeePercent: Double,
        val annualSavingsKr: Double,
    )

    /**
     * Bygger en kandidatfråga ur [held]s egna dimension-taggar, grupperade per filterfält
     * (TP-21) — en grov förfiltrering som håller nere antalet nätverksanrop. Den faktiska
     * "identisk exponering"-kontrollen sker i [rank] (exakt taggmängdslikhet), så en
     * dimension utan eget frågefält (t.ex. en framtida taggkategori källan lägger till)
     * fortfarande utesluter icke-matchande kandidater där — bara inte förfiltrerar bort dem
     * tidigare i pipelinen.
     */
    fun candidateQuery(held: FundMetadata): FundScreenQuery {
        fun titlesFor(category: String) = held.tags.filter { it.category == category }.map { it.title }
        return FundScreenQuery(
            fundType = titlesFor(FundTag.CATEGORY_TYPE),
            region = titlesFor(FundTag.CATEGORY_COMMON_REGION),
            otherRegion = titlesFor(FundTag.CATEGORY_OTHER_REGION),
            industry = titlesFor(FundTag.CATEGORY_INDUSTRY),
            alignment = titlesFor(FundTag.CATEGORY_ALIGNMENT),
            interestType = titlesFor(FundTag.CATEGORY_INTEREST),
            misc = titlesFor(FundTag.CATEGORY_MISC),
            maxTotalFee = held.totalFee,
            sortField = "totalFee",
            sortDirection = FundScreenSortDirection.ASCENDING,
        )
    }

    /**
     * Filtrerar [candidates] till de som har **identisk taggmängd** och samma `indexFund`-
     * status som [held] (samma exponering, validerat mot verklig data — se issue #59),
     * **strikt lägre** `totalFee` och ett annat ISIN — rankade på årsbesparing (störst
     * först). `indexFund` kontrolleras separat från taggmängden trots att källans egen
     * `INDEX`-tagg i praktiken redan speglar den (försvar mot att den kopplingen skulle
     * brytas i källans data utan förvarning).
     *
     * Null `totalFee` på [held] eller en kandidat utesluter (aldrig en gissad besparing,
     * samma princip som ANA-4/POR-3). Tom lista om inget kvalificerar — ett ärligt "inga
     * billigare alternativ med samma exponering" i stället för ett förslag som tyst byter
     * exponering.
     */
    fun rank(held: FundMetadata, candidates: List<FundMetadata>, holdingValue: Double): List<Alternative> {
        val heldFee = held.totalFee ?: return emptyList()
        val heldTags = held.tags.toSet()
        return candidates
            .filter { it.isin != held.isin }
            .filter { it.indexFund == held.indexFund }
            .filter { it.tags.toSet() == heldTags }
            .mapNotNull { candidate ->
                val candidateFee = candidate.totalFee ?: return@mapNotNull null
                if (candidateFee >= heldFee) return@mapNotNull null
                Alternative(candidate, candidateFee, annualSavings(heldFee, candidateFee, holdingValue))
            }
            .sortedByDescending { it.annualSavingsKr }
    }

    private fun annualSavings(heldFeePercent: Double, candidateFeePercent: Double, holdingValue: Double): Double =
        (heldFeePercent - candidateFeePercent) / 100.0 * holdingValue
}
