package se.partee71.fonder.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import se.partee71.fonder.data.network.FondlistaHtmlSource
import se.partee71.fonder.data.network.HandelsbankenHtmlParser
import se.partee71.fonder.data.room.daos.FundPriceDao
import se.partee71.fonder.data.room.entities.FundPriceEntity
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundPrice
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kontrakt för fondkurser (NAV). Källa: handelsbanken.fondlista.se, beslutad i spike-issue
 * #2, implementerad i #3.
 */
interface FundPriceRepository {
    /** Senaste kända (cachade) kurs för en fond, eller null om okänd. */
    suspend fun latestPrice(fundId: String): FundPrice?

    /** Senaste kända kurs per fondId, reaktivt — uppdateras när cachen ändras (issue #6). */
    fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>>

    /** Kurshistorik för en fond inom ett epoch-day-intervall (inklusive), ur lokal cache. */
    suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice>

    /** Som [priceHistory], men reaktivt — uppdateras när nya kurser cachas (issue #7). */
    fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>>

    /** Hämtar senaste årets kurser från källan och cachar dem. Fel loggas, kraschar aldrig. */
    suspend fun refresh(fundId: String)

    /** Alla fondbolag + hela fondkatalogen (en hämtning) för fondsök-UI. */
    suspend fun fetchFundCatalog(): FundCatalog
}

@Singleton
class HandelsbankenFundPriceRepository @Inject constructor(
    private val client: FondlistaHtmlSource,
    private val dao: FundPriceDao,
) : FundPriceRepository {

    override suspend fun latestPrice(fundId: String): FundPrice? =
        dao.getLatest(fundId)?.toDomain()

    override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> {
        if (fundIds.isEmpty()) return flowOf(emptyMap())
        return dao.observeLatest(fundIds).map { list -> list.associateBy({ it.fundId }, { it.toDomain() }) }
    }

    override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice> =
        dao.getRange(fundId, fromEpochDay, toEpochDay).map { it.toDomain() }

    override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> =
        dao.observeRange(fundId, fromEpochDay, toEpochDay).map { list -> list.map { it.toDomain() } }

    override suspend fun refresh(fundId: String) {
        runCatching {
            val to = LocalDate.now()
            val from = to.minusYears(1)
            val html = client.fetchHistoryPage(fundId, from, to)
            HandelsbankenHtmlParser.parseHistory(html, fundId)
        }.onSuccess { prices ->
            if (prices.isNotEmpty()) {
                dao.upsertAll(prices.map(FundPriceEntity::fromDomain))
            }
        }.onFailure { e ->
            // Nätverksfel eller ett brott i sidans format — behåll senast cachade kurs,
            // krascha aldrig UI:t. Se riskavsnittet i issue #2/#3.
            Log.w(TAG, "Kunde inte uppdatera kurser för fund $fundId, behåller cache", e)
        }
    }

    override suspend fun fetchFundCatalog(): FundCatalog =
        runCatching {
            val today = LocalDate.now()
            val html = client.fetchHistoryPage(fundId = null, from = today, to = today)
            FundCatalog(
                companies = HandelsbankenHtmlParser.parseFundCompanies(html),
                funds = HandelsbankenHtmlParser.parseFundCatalog(html),
            )
        }.onFailure { e ->
            Log.w(TAG, "Kunde inte hämta fondkatalogen", e)
        }.getOrDefault(FundCatalog(companies = emptyList(), funds = emptyList()))

    private companion object {
        const val TAG = "FundPriceRepository"
    }
}
