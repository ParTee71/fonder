package se.partee71.fonder.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import se.partee71.fonder.data.network.FondlistaHtmlSource
import se.partee71.fonder.data.network.HandelsbankenHtmlParser
import se.partee71.fonder.data.network.IsinPriceHistorySource
import se.partee71.fonder.data.room.daos.FundPriceDao
import se.partee71.fonder.data.room.entities.FundPriceEntity
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.IsinPricePoint
import se.partee71.fonder.domain.usecase.NavCalendar
import java.time.LocalDate
import java.time.LocalDateTime
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

    /**
     * Hämtar senaste fem årens kurser från källan och cachar dem. Fel loggas, kraschar aldrig.
     * @return true om hämtningen mot källan lyckades (oavsett om den gav nya priser), false
     *   vid fel — används av [se.partee71.fonder.worker.FundPriceUpdateWorker] för att avgöra
     *   om jobbet bör köras om.
     */
    suspend fun refresh(fundId: String): Boolean

    /**
     * Hämtar kurshistorik för [isin] sedan [since] och cachar den under [fundId] — kompletterar
     * [refresh] (Handelsbankens fasta 5-årsfönster, ingen ISIN) med en ISIN-baserad källkedja
     * som klarar godtyckligt gamla köp (se KRAVLISTA TP-14). Provar källorna i prioritetsordning,
     * går vidare vid fel/tomt resultat. Fel loggas, kraschar aldrig, cache behålls.
     * @return true om någon källa i kedjan gav historik, false om alla misslyckades/var tomma.
     */
    suspend fun refreshSince(fundId: String, isin: String, since: LocalDate): Boolean

    /** Föreslår ett ISIN för [fundName] via namnsökning mot samma källkedja som [refreshSince], eller null om ingen rimlig träff. */
    suspend fun suggestIsin(fundName: String): String?

    /**
     * Slår upp en fond **exakt** via [isin] i samma källkedja som [refreshSince] — ingen
     * fuzzy namnmatchning. Används för fonder som saknas i Handelsbankens katalog (TP-9),
     * t.ex. vid import av innehav från andra fondbolag (se KRAVLISTA TP-13/TP-14). Fondens
     * identitet blir ISIN:et självt (`Fund.fundId == isin`) eftersom källan inte har något
     * Handelsbanken-FundId. Null om ingen källa känner till ISIN:et.
     */
    suspend fun findFundByIsin(isin: String): Fund?

    /** Alla fondbolag + hela fondkatalogen (en hämtning) för fondsök-UI. */
    suspend fun fetchFundCatalog(): FundCatalog
}

/**
 * Uppdaterar en fonds kurscache via rätt källa: den ISIN-baserade källkedjan ([refreshSince])
 * om fonden har ett känt ISIN (t.ex. matchad via [FundPriceRepository.findFundByIsin] eller
 * import, TP-14 — sådana fonder saknar Handelsbanken-FundId och [refresh] hittar dem aldrig),
 * annars Handelsbankens fasta femårsfönster ([refresh]). Samma gren behövdes tidigare separat
 * i flera ViewModels (Portfölj, båda importflödena) — samlad här för att undvika ytterligare
 * en kopia (regel 4, issue #19). [se.partee71.fonder.ui.fond.FondDetaljViewModel] har en
 * egen variant med en extra gate (bara om fonden faktiskt köpts) och lämnas orörd.
 */
suspend fun FundPriceRepository.refreshFund(fund: Fund, since: LocalDate): Boolean {
    val isin = fund.isin
    return if (isin != null) refreshSince(fund.fundId, isin, since) else refresh(fund.fundId)
}

/**
 * Sant om [fundId] saknar cachad kurs helt, eller om senaste kända kurs är äldre än
 * [NavCalendar.expectedLatestNavDay] (issue #18/#19, handelsdagsmedveten sedan issue #27/TP-17)
 * — samma "uppdatera bara vid faktiskt inaktuell cache"-princip återanvänd mellan appstart,
 * bakgrundsjobbet och båda importflödena i stället för en egen kopia var (regel 4). Ersätter
 * den tidigare "senaste kurs < idag"-jämförelsen, som gav falska hämtningar på helger (fredagens
 * NAV är redan det senaste som finns) och falskt "färskt" på kvällar (dagens NAV inte hämtad än).
 */
suspend fun FundPriceRepository.isPriceStale(fundId: String, now: LocalDateTime = LocalDateTime.now()): Boolean {
    val latest = latestPrice(fundId)
    return latest == null || latest.epochDay < NavCalendar.expectedLatestNavDay(now).toEpochDay()
}

@Singleton
class HandelsbankenFundPriceRepository @Inject constructor(
    private val client: FondlistaHtmlSource,
    private val dao: FundPriceDao,
    private val isinSources: List<@JvmSuppressWildcards IsinPriceHistorySource>,
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

    override suspend fun refresh(fundId: String): Boolean {
        val to = LocalDate.now()
        val from = to.minusYears(5)
        val result = runCatching {
            val html = client.fetchHistoryPage(fundId, from, to)
            HandelsbankenHtmlParser.parseHistory(html, fundId)
        }
        result.onSuccess { prices ->
            if (prices.isNotEmpty()) {
                dao.upsertAll(prices.map(FundPriceEntity::fromDomain))
            }
        }.onFailure { e ->
            // Nätverksfel eller ett brott i sidans format — behåll senast cachade kurs,
            // krascha aldrig UI:t. Se riskavsnittet i issue #2/#3.
            Log.w(TAG, "Kunde inte uppdatera kurser för fund $fundId, behåller cache", e)
        }
        refreshRecentHandelsbankenWindow(fundId, to)
        return result.isSuccess
    }

    /**
     * Kompletterande hämtning av ett kort, färskt fönster ovanpå [refresh]s långa femårsfönster
     * (issue #35): källan är odokumenterad och kan i teorin samplas ner över långa intervall,
     * vilket annars kan lämna en lucka i de senaste dagarnas historik — synligt som att
     * "En dag" och "Senaste veckan" råkade visa exakt samma tal (samma, för gamla, kurs valdes
     * för bådas måldag, se [se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc]). Ett
     * kort fönster är osannolikt att samplas ner av samma anledning. Bästa-försök: fel loggas
     * och ignoreras, [refresh]s returvärde styrs fortfarande bara av den långa hämtningen.
     */
    private suspend fun refreshRecentHandelsbankenWindow(fundId: String, to: LocalDate) {
        val recentFrom = to.minusDays(RECENT_WINDOW_DAYS)
        runCatching {
            val html = client.fetchHistoryPage(fundId, recentFrom, to)
            HandelsbankenHtmlParser.parseHistory(html, fundId)
        }.onSuccess { prices ->
            if (prices.isNotEmpty()) dao.upsertAll(prices.map(FundPriceEntity::fromDomain))
        }.onFailure { e ->
            Log.w(TAG, "Kunde inte förtäta senaste kurshistoriken för fund $fundId", e)
        }
    }

    override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate): Boolean {
        val to = LocalDate.now()
        for (source in isinSources) {
            val points = runCatching { source.fetchHistory(isin, since, to) }
                .onFailure { e -> Log.w(TAG, "ISIN-källa gav fel för $isin, provar nästa i kedjan", e) }
                .getOrNull()
            if (!points.isNullOrEmpty()) {
                dao.upsertAll(points.map { it.toEntity(fundId) })
                refreshRecentIsinWindow(fundId, isin, since, to, source)
                return true
            }
        }
        Log.w(TAG, "Ingen ISIN-källa kunde ge historik för $isin, behåller cache")
        return false
    }

    /** Som [refreshRecentHandelsbankenWindow], men för [refreshSince]s ISIN-källkedja — hoppas över om [since] redan ligger inom det korta fönstret (då gav den ursprungliga hämtningen redan ett kort intervall). */
    private suspend fun refreshRecentIsinWindow(fundId: String, isin: String, since: LocalDate, to: LocalDate, source: IsinPriceHistorySource) {
        val recentFrom = to.minusDays(RECENT_WINDOW_DAYS)
        if (!since.isBefore(recentFrom)) return
        val points = runCatching { source.fetchHistory(isin, recentFrom, to) }
            .onFailure { e -> Log.w(TAG, "Kunde inte förtäta senaste kurshistoriken för $isin", e) }
            .getOrNull()
        if (!points.isNullOrEmpty()) {
            dao.upsertAll(points.map { it.toEntity(fundId) })
        }
    }

    private fun IsinPricePoint.toEntity(fundId: String) =
        FundPriceEntity(fundId = fundId, epochDay = epochDay, nav = nav, currency = currency)

    override suspend fun suggestIsin(fundName: String): String? {
        for (source in isinSources) {
            val isin = runCatching { source.suggestIsin(fundName) }
                .onFailure { e -> Log.w(TAG, "ISIN-förslag misslyckades för \"$fundName\", provar nästa i kedjan", e) }
                .getOrNull()
            if (isin != null) return isin
        }
        return null
    }

    override suspend fun findFundByIsin(isin: String): Fund? {
        for (source in isinSources) {
            val info = runCatching { source.findFund(isin) }
                .onFailure { e -> Log.w(TAG, "ISIN-uppslag misslyckades för $isin, provar nästa i kedjan", e) }
                .getOrNull()
            if (info != null) {
                return Fund(fundId = isin, name = info.name, currency = info.currency, isin = isin)
            }
        }
        return null
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

        /**
         * Kort, färskt fönster som alltid hämtas utöver [refresh]/[refreshSince]s långa
         * intervall (issue #35) — se [refreshRecentHandelsbankenWindow]/[refreshRecentIsinWindow].
         * Marginal utöver de periodfönster [se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc]
         * behöver (upp till 30 dagar) plus helger/röda dagar.
         */
        const val RECENT_WINDOW_DAYS = 60L
    }
}
