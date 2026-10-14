package se.partee71.fonder.data.repository

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.SwitchPlanCalc

/**
 * In-memory-fake av [FundMetadataRepository] för de läsare som bara behöver metadata och
 * köpkandidater — bevakningsskärmen (ANA-12/ANA-13) och Sålda fonder (SLD-5), issue #114.
 *
 * Öppen så ett enskilt test kan skärpa en metod (t.ex. räkna anrop) utan att implementera hela
 * det nio metoder stora gränssnittet på nytt. Äldre testers inbäddade fakes lämnas orörda —
 * de driver andra beteenden och skulle bara bli svårare att läsa av en gemensam bas.
 */
open class FakeFundMetadataRepository(
    var metadataByIsin: Map<String, FundMetadata> = emptyMap(),
    var switchCandidates: List<SwitchPlanCalc.Candidate> = emptyList(),
) : FundMetadataRepository {

    /** Varje `findSwitchCandidates`-anrop som (nivå, uteslutna) — driver att förslagen hämtas en gång. */
    val switchCandidateCalls = mutableListOf<Pair<Int, Set<String>>>()

    override suspend fun query(query: FundScreenQuery): List<FundMetadata> = emptyList()

    override suspend fun resolveHandelsbankenAvailability(isin: String): Boolean? = null

    override fun observeFilterVocabulary(): Flow<FundFilterVocabulary> = flowOf(FundFilterVocabulary())

    override suspend fun suggestCheaperAlternatives(isin: String, holdingValue: Double): List<FeeComparisonCalc.Alternative>? = null

    override suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata> =
        metadataByIsin.filterKeys { it in isins }

    override suspend fun cachedMetadataFor(isins: List<String>): Map<String, FundMetadata> =
        metadataByIsin.filterKeys { it in isins }

    override suspend fun cachedRiskByFundName(): Map<String, Int> = emptyMap()

    override suspend fun knownRiskLevels(): List<Int> = emptyList()

    /** Sant spelar "köpbarheten gick inte att kontrollera" i [searchSwitchCandidates] (issue #149). */
    var switchLookupFailed: Boolean = false

    override suspend fun findSwitchCandidates(level: Int, excludeIsins: Set<String>): List<SwitchPlanCalc.Candidate> {
        switchCandidateCalls += level to excludeIsins
        return switchCandidates.filterNot { it.metadata.isin in excludeIsins }
    }

    /** Fördröjning i [searchSwitchCandidates] — håller en autofyllning pågående i test av dubbeltryck. */
    var switchSearchDelayMs: Long = 0

    /** Filtret i varje `searchSwitchCandidates`-anrop, i ordning — driver bevakningens indexfilter (issue #150). */
    val switchSearchIndexOnly = mutableListOf<Boolean>()

    /** Som källan: [indexOnly] sållar bort fonder som inte är indexfonder, nivån filtrerar inte (testerna styr poolen). */
    override suspend fun searchSwitchCandidates(level: Int, excludeIsins: Set<String>, indexOnly: Boolean): SwitchPlanCalc.CandidateSearch {
        if (switchSearchDelayMs > 0) delay(switchSearchDelayMs)
        switchSearchIndexOnly += indexOnly
        val found = findSwitchCandidates(level, excludeIsins).filter { !indexOnly || it.metadata.indexFund }
        return SwitchPlanCalc.CandidateSearch(found, lookupFailed = switchLookupFailed)
    }
}
