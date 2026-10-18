package se.partee71.fonder.data.repository

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundPrice

/**
 * In-memory-fake av [FundPriceRepository] för läsarna av **kurscachen** — Översikten och
 * [se.partee71.fonder.domain.usecase.PendingDecisionsSource]. [latestPrices] är cachens senaste
 * kurser (alla nycklar, oavsett vilka som efterfrågas), [priceHistoryByFundId] dess historik.
 * Inga nätverksvägar: hämtningarna svarar tomt.
 */
class FakeCachedPriceRepository : FundPriceRepository {

    val latestPrices = MutableStateFlow<Map<String, FundPrice>>(emptyMap())
    var priceHistoryByFundId: Map<String, List<FundPrice>> = emptyMap()

    /** Varje `priceHistory`-anrop, i ordning — driver testet att hämtningen sker en gång per fond och emission. */
    val priceHistoryCalls = mutableListOf<String>()

    // Gränssnittet i omvänd ordning mot de inbäddade fakes som äldre tester bär: den här är den
    // delade, och nya tester ska använda den i stället för att kopiera en till.
    override suspend fun fetchFundCatalog(): FundCatalog = FundCatalog(emptyList(), emptyList())

    override suspend fun fetchFundsForCompany(companyId: String): List<Fund>? = emptyList()

    override suspend fun lookupIsin(fundId: String): String? = null

    override suspend fun findFundByIsin(isin: String): Fund? = null

    override suspend fun suggestIsin(fundName: String): String? = null

    override suspend fun historyForIsin(isin: String, from: LocalDate, to: LocalDate): List<FundPrice> = emptyList()

    override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate) = true

    override suspend fun refresh(fundId: String, since: LocalDate?) = true

    override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())

    override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice> {
        priceHistoryCalls += fundId
        return priceHistoryByFundId[fundId].orEmpty().filter { it.epochDay in fromEpochDay..toEpochDay }
    }

    override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = latestPrices

    override suspend fun latestPrice(fundId: String): FundPrice? = latestPrices.value[fundId]
}
