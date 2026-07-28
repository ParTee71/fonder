package se.partee71.fonder.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import se.partee71.fonder.data.network.FondlistaFundPageSource
import se.partee71.fonder.data.network.FondlistaHtmlSource
import se.partee71.fonder.data.network.FxRateSource
import se.partee71.fonder.data.network.HandelsbankenHtmlParser
import se.partee71.fonder.data.network.IsinPriceHistorySource
import se.partee71.fonder.data.room.daos.FundDao
import se.partee71.fonder.data.room.daos.FundPriceDao
import se.partee71.fonder.data.room.daos.FxRateDao
import se.partee71.fonder.data.room.entities.FundPriceEntity
import se.partee71.fonder.data.room.entities.FxRateEntity
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.IsinPricePoint
import se.partee71.fonder.domain.usecase.CurrencyConverter
import se.partee71.fonder.domain.usecase.FundNameMatcher
import se.partee71.fonder.domain.usecase.NavCalendar
import java.time.Duration
import java.time.Instant
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
     * Hämtar kurser från fondlista-källan och cachar dem. Fel loggas, kraschar aldrig.
     *
     * [since] är hur långt bak historiken behövs (normalt fondens första köp). Källan har
     * **inget** femårstak — den levererar hela fondens historik i ett anrop (KRAVLISTA TP-18)
     * — men ett fullt spann är också ett stort svar, så det hämtas bara som **backfill**: när
     * cachen inte redan når tillbaka till [since]. Annars hämtas ett kort, färskt fönster.
     * [since] = null betyder "ingen känd historikhorisont" (t.ex. en bevakad men aldrig köpt
     * fond) och ger alltid bara det korta fönstret.
     *
     * @return true om hämtningen mot källan lyckades (oavsett om den gav nya priser), false
     *   vid fel — används av [se.partee71.fonder.worker.FundPriceUpdateWorker] för att avgöra
     *   om jobbet bör köras om.
     */
    suspend fun refresh(fundId: String, since: LocalDate? = null): Boolean

    /**
     * Hämtar kurshistorik sedan [since] och cachar den under [fundId]. Provar **fondlista
     * först** (KRAVLISTA TP-18) och faller tillbaka till den ISIN-baserade källkedjan
     * (Avanza m.fl., TP-14) om fondlista inte kan leverera.
     *
     * Är [fundId] ett ISIN saknar fonden plattformskod (matchad via [findFundByIsin]) — då
     * slås ett `fondlistaFundId` upp först och sparas på fonden (issue #39). Utan det steget
     * hamnade hela importerade portföljer permanent på Avanza-grenen, som ligger en handelsdag
     * efter. Kurserna cachas alltid under [fundId], som är fondens identitet.
     *
     * Källorna provas i prioritetsordning, nästa tas vid fel/tomt resultat. Fel loggas,
     * kraschar aldrig, cache behålls.
     * @return true om någon källa gav historik, false om alla misslyckades/var tomma.
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

    /**
     * Slår upp fondens **ISIN** i fondlista-källan (fondsidan, KRAVLISTA TP-18), eller null om
     * sidan inte bär ett entydigt ISIN. Ger en maskinell koppling `FundId` → ISIN som tidigare
     * saknades helt — används för att fylla [Fund.isin] utan att gissa på fondnamn.
     */
    suspend fun lookupIsin(fundId: String): String?

    /** Alla fondbolag + **hela plattformens** fondkatalog (en hämtning) för fondsök-UI. */
    suspend fun fetchFundCatalog(): FundCatalog

    /**
     * Fonderna som fondbolaget [companyId] har på plattformen — källans eget filter
     * (KRAVLISTA TP-18/TP-11), inte en approximation i appen. **Null vid fel**, så anroparen
     * kan behålla sin nuvarande lista i stället för att visa en tom (samma "krascha aldrig,
     * degradera till det du har"-princip som resten av datalagret).
     */
    suspend fun fetchFundsForCompany(companyId: String): List<Fund>?
}

/**
 * Uppdaterar en fonds kurscache via rätt källa: har fonden ett känt ISIN provas
 * [FundPriceRepository.refreshSince] (fondlista först, ISIN-kedjan som reserv — sådana fonder
 * kan sakna Handelsbanken-FundId och nås då aldrig av enbart [FundPriceRepository.refresh]),
 * annars [FundPriceRepository.refresh] direkt. Samma gren behövdes tidigare separat i flera
 * ViewModels (Portfölj, båda importflödena) — samlad här för att undvika ytterligare en kopia
 * (regel 4, issue #19). [se.partee71.fonder.ui.fond.FondDetaljViewModel] har en egen variant
 * med en extra gate (bara om fonden faktiskt köpts) och lämnas orörd.
 */
suspend fun FundPriceRepository.refreshFund(fund: Fund, since: LocalDate): Boolean {
    val isin = fund.isin
    return if (isin != null) refreshSince(fund.fundId, isin, since) else refresh(fund.fundId, since)
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
    private val fundPageClient: FondlistaFundPageSource,
    private val dao: FundPriceDao,
    private val fundDao: FundDao,
    private val fxRateDao: FxRateDao,
    private val fxRateSource: FxRateSource,
    private val isinSources: List<@JvmSuppressWildcards IsinPriceHistorySource>,
) : FundPriceRepository {

    /** Katalogen memoiserad en kort stund — se [catalogFunds]. */
    private var cachedCatalog: Pair<Instant, List<Fund>>? = null

    /** Fonder som saknar motsvarighet i katalogen — se [fondlistaKeyFor]. */
    private val unresolvableFundIds = mutableSetOf<String>()

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

    override suspend fun refresh(fundId: String, since: LocalDate?): Boolean {
        val to = LocalDate.now()
        val from = historyStart(fundId, since, to)
        return fetchAndCacheFromFondlista(sourceFundId = fundId, from = from, to = to) != null
    }

    /**
     * Hur långt bak [refresh] hämtar. Källan har inget femårstak (KRAVLISTA TP-18) men ett
     * fullt spann är också ett stort svar (~3,6 MB för en fond med 30+ års historik), så det
     * hämtas bara när det faktiskt behövs: cachen är tom, eller når inte tillbaka till [since].
     * I övrigt räcker ett kort, färskt fönster.
     *
     * Marginalfall: har källan **mindre** historik än [since] (fonden köptes innan plattformens
     * data börjar) når cachen aldrig ända fram, och varje uppdatering hämtar om det spann som
     * faktiskt finns. Svaret är då lika stort som fondens hela historik, inte större — medvetet
     * avvägt mot att införa separat cache-metadata bara för det fallet.
     */
    private suspend fun historyStart(fundId: String, since: LocalDate?, to: LocalDate): LocalDate {
        val recentFrom = to.minusDays(RECENT_WINDOW_DAYS)
        if (since == null || !since.isBefore(recentFrom)) return recentFrom
        val oldestCached = dao.getOldest(fundId)?.epochDay ?: return since
        return if (since.toEpochDay() < oldestCached) since else recentFrom
    }

    /**
     * Hämtar och cachar kurser från fondlista-källan. [sourceFundId] är nyckeln mot källan,
     * [cacheFundId] den nyckel kurserna cachas under — de skiljer sig för fonder vars
     * identitet är ett ISIN men som nås i katalogen via ett uppslaget `fondlistaFundId`
     * (issue #39). Null vid nätverksfel eller ett brott i sidans format — behåll senast
     * cachade kurs, krascha aldrig UI:t (riskavsnittet i #2/#3). En tom lista är *inte* ett
     * fel: fonden kan sakna kurser i intervallet.
     */
    private suspend fun fetchAndCacheFromFondlista(
        sourceFundId: String,
        cacheFundId: String = sourceFundId,
        from: LocalDate,
        to: LocalDate,
    ): List<FundPrice>? =
        runCatching {
            val html = client.fetchHistoryPage(fundId = sourceFundId, company = null, from = from, to = to)
            toValueCurrency(HandelsbankenHtmlParser.parseHistory(html, cacheFundId))
        }.onSuccess { prices ->
            if (prices.isNotEmpty()) {
                dao.upsertAll(prices.map(FundPriceEntity::fromDomain))
            }
        }.onFailure { e ->
            Log.w(TAG, "Kunde inte uppdatera kurser för fund $cacheFundId, behåller cache", e)
        }.getOrNull()

    /**
     * Räknar om kurser som inte är i [FundPrice.VALUE_CURRENCY] till kronor (KRAVLISTA
     * TP-19/TP-20, issue #41/#43).
     *
     * Fondlista noterar varje fond i **fondens egen valuta** — en USD-fond får NAV i dollar.
     * Appen räknar hela värdekedjan i kronor, så en sådan kurs skulle tyst tolkas som kronor
     * om den lämnades orörd: CPR Invest Global Gold Mines gick från 14 462 kr till 1 490 kr när
     * 1878,75 (SEK, via Avanza) ersattes av 193,48 (USD, via fondlista) — fel med hela
     * växelkursen.
     *
     * Växelkurser hämtas från Riksbanken ([fxRateSource]) och cachas ([fxRateDao]) — samma
     * "krascha aldrig, degradera"-princip som resten av datalagret: går hämtningen inte kan
     * de dagarna inte konverteras och utelämnas i stället för att gissas ([CurrencyConverter]).
     * Blir resultatet tomt (t.ex. helt nätverksfel) faller [refreshSince] tillbaka på
     * ISIN-kedjan, som levererar värdet i kronor direkt.
     */
    private suspend fun toValueCurrency(prices: List<FundPrice>): List<FundPrice> {
        if (prices.isEmpty()) return prices
        val (inSek, foreign) = prices.partition { it.currency.equals(FundPrice.VALUE_CURRENCY, ignoreCase = true) }
        if (foreign.isEmpty()) return inSek

        val converted = foreign.groupBy { it.currency }.flatMap { (currency, points) ->
            // Marginal bakåt så CurrencyConverter kan återanvända en närliggande kurs för
            // fondkursens första dag, om just den dagen saknar egen valutanotering.
            val from = LocalDate.ofEpochDay(points.minOf { it.epochDay }).minusDays(CurrencyConverter.MAX_RATE_AGE_DAYS)
            val to = LocalDate.ofEpochDay(points.maxOf { it.epochDay })
            ensureRatesCached(currency, from, to)
            val rates = fxRateDao.getRange(currency, from.toEpochDay(), to.toEpochDay())
                .associate { it.epochDay to it.rate }
            CurrencyConverter.toValueCurrency(points, rates)
        }
        return inSek + converted
    }

    /**
     * Fyller på [fxRateDao] med det som saknas för [currency] inom [from]..[to]. Växelkurser
     * är historiskt oföränderliga — en gång hämtad dag hämtas aldrig om — så bara den
     * inledande och/eller avslutande luckan hämtas, inte hela intervallet varje gång.
     */
    private suspend fun ensureRatesCached(currency: String, from: LocalDate, to: LocalDate) {
        val oldest = fxRateDao.getOldest(currency)?.epochDay
        val latest = fxRateDao.getLatest(currency)?.epochDay
        if (oldest == null || latest == null) {
            fetchAndCacheRates(currency, from, to)
            return
        }
        if (from.toEpochDay() < oldest) {
            fetchAndCacheRates(currency, from, LocalDate.ofEpochDay(oldest - 1))
        }
        if (to.toEpochDay() > latest) {
            fetchAndCacheRates(currency, LocalDate.ofEpochDay(latest + 1), to)
        }
    }

    private suspend fun fetchAndCacheRates(currency: String, from: LocalDate, to: LocalDate) {
        val rates = runCatching { fxRateSource.fetchRates(currency, from, to) }
            .onFailure { e -> Log.w(TAG, "Kunde inte hämta växelkurser för $currency, behåller cache", e) }
            .getOrNull()
        if (!rates.isNullOrEmpty()) {
            fxRateDao.upsertAll(rates.map { FxRateEntity(currency = currency, epochDay = it.epochDay, rate = it.rate) })
        }
    }

    /**
     * Nyckeln att hämta kurser med mot fondlista, eller null om fonden inte kan nås där.
     *
     * Är [fundId] inte ett ISIN är den redan plattformens egen kod. Annars är fonden matchad
     * enbart via ISIN (`findFundByIsin`, TP-13/TP-14) och saknar kod — då används ett tidigare
     * uppslaget `fondlistaFundId`, eller görs ett nytt uppslag via [resolveFondlistaFundId].
     * En träff sparas på fonden så uppslaget bara görs en gång (issue #39).
     */
    private suspend fun fondlistaKeyFor(fundId: String, isin: String): String? {
        if (fundId != isin) return fundId
        val fund = fundDao.getByFundId(fundId) ?: return null
        fund.fondlistaFundId?.let { return it }
        if (fundId in unresolvableFundIds) return null

        val resolved = resolveFondlistaFundId(fund.name, isin)
        if (resolved == null) {
            // Minns misslyckandet för processens livstid — annars kostar varje uppdatering av
            // en fond som inte finns i katalogen ett nytt katalog- och sidanrop.
            unresolvableFundIds.add(fundId)
            Log.w(TAG, "Hittade ingen fondlista-motsvarighet för $fundId, använder ISIN-källkedjan")
            return null
        }
        fundDao.upsert(fund.copy(fondlistaFundId = resolved))
        return resolved
    }

    /**
     * Letar upp fondlista-plattformens kod för en fond som bara är känd via namn + [isin]:
     * bästa namnkandidat i katalogen, **verifierad mot ISIN** på fondens egen sida (TP-18).
     * Null om ingen kandidat finns eller om ISIN inte stämmer — hellre Avanza-kedjan än fel
     * fond (samma princip som importmatchningen, `ImportFundMatcher`).
     */
    private suspend fun resolveFondlistaFundId(fundName: String, isin: String): String? {
        val candidate = FundNameMatcher.bestMatch(fundName, catalogFunds()) ?: return null
        return candidate.fund.fundId.takeIf { lookupIsin(it) == isin }
    }

    /**
     * Hela katalogen, memoiserad en kort stund: en worker-körning som löser upp flera fonder
     * ska hämta den en gång, inte en gång per fond (svaret är ~125 kB).
     */
    private suspend fun catalogFunds(): List<Fund> {
        val cached = cachedCatalog
        if (cached != null && Duration.between(cached.first, Instant.now()) < CATALOG_CACHE_TTL) {
            return cached.second
        }
        val funds = fetchCatalogPage(company = null)?.let(HandelsbankenHtmlParser::parseFundCatalog).orEmpty()
        if (funds.isNotEmpty()) cachedCatalog = Instant.now() to funds
        return funds
    }

    override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate): Boolean {
        val to = LocalDate.now()
        // Fondlista först (KRAVLISTA TP-18): daglig, luckfri historik utan datumtak och utan
        // nedsamplingen ISIN-kedjan behövde skyddas mot (TP-14). Är `fundId` ett ISIN saknar
        // fonden plattformskod och måste slås upp först (issue #39) — kurserna cachas ändå
        // alltid under appens `fundId`, som är fondens identitet.
        val fondlistaKey = fondlistaKeyFor(fundId, isin)
        if (fondlistaKey != null) {
            val prices = fetchAndCacheFromFondlista(
                sourceFundId = fondlistaKey,
                cacheFundId = fundId,
                from = historyStart(fundId, since, to),
                to = to,
            )
            if (prices?.isNotEmpty() == true) return true
        }
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

    /**
     * Kompletterande hämtning av ett kort, färskt fönster ovanpå ISIN-kedjans långa intervall
     * (issue #35): Avanza samplar ner långa spann, vilket annars kan lämna en lucka i de
     * senaste dagarnas historik — synligt som att "En dag" och "Senaste veckan" råkade visa
     * exakt samma tal (samma, för gamla, kurs valdes för bådas måldag, se
     * [se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc]). Behövs inte för
     * fondlista-källan, som är daglig och luckfri även över 30+ år (KRAVLISTA TP-18).
     * Hoppas över om [since] redan ligger inom det korta fönstret (då gav den ursprungliga
     * hämtningen redan ett kort intervall). Bästa-försök: fel loggas och ignoreras.
     */
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

    override suspend fun lookupIsin(fundId: String): String? =
        runCatching { HandelsbankenHtmlParser.parseIsin(fundPageClient.fetchFundPage(fundId)) }
            .onFailure { e -> Log.w(TAG, "Kunde inte slå upp ISIN för fund $fundId", e) }
            .getOrNull()

    // Utan `company` levererar källan hela plattformens katalog, inte bara Handelsbankens egna
    // fonder (KRAVLISTA TP-18) — bolagslistan finns med i samma svar.
    override suspend fun fetchFundCatalog(): FundCatalog =
        fetchCatalogPage(company = null)
            ?.let { html ->
                FundCatalog(
                    companies = HandelsbankenHtmlParser.parseFundCompanies(html),
                    funds = HandelsbankenHtmlParser.parseFundCatalog(html),
                )
            }
            ?: FundCatalog(companies = emptyList(), funds = emptyList())

    override suspend fun fetchFundsForCompany(companyId: String): List<Fund>? =
        fetchCatalogPage(company = companyId)?.let(HandelsbankenHtmlParser::parseFundCatalog)

    /** Katalogsidan för ett (eller inget) fondbolag. Null vid fel — anroparen behåller då sin nuvarande lista. */
    private suspend fun fetchCatalogPage(company: String?): String? =
        runCatching {
            val today = LocalDate.now()
            client.fetchHistoryPage(fundId = null, company = company, from = today, to = today)
        }.onFailure { e ->
            Log.w(TAG, "Kunde inte hämta fondkatalogen (fondbolag=$company)", e)
        }.getOrNull()

    private companion object {
        const val TAG = "FundPriceRepository"

        /**
         * Kort, färskt fönster som räcker för rutinuppdateringar — se [historyStart] och
         * [refreshRecentIsinWindow]. Marginal utöver de periodfönster
         * [se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc] behöver (upp till
         * 30 dagar) plus helger/röda dagar.
         */
        const val RECENT_WINDOW_DAYS = 60L

        /** Hur länge katalogen återanvänds i minnet — se [catalogFunds]. */
        val CATALOG_CACHE_TTL: Duration = Duration.ofMinutes(10)
    }
}
