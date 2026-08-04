package se.partee71.fonder.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.network.AvanzaFundListParser
import se.partee71.fonder.data.network.AvanzaFundListRequestBuilder
import se.partee71.fonder.data.network.AvanzaSource
import se.partee71.fonder.data.room.daos.FundMetadataDao
import se.partee71.fonder.data.room.entities.FundMetadataEntity
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.model.FundScreenSortDirection
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.FundMetadataFreshness
import se.partee71.fonder.domain.usecase.FundNameKey
import se.partee71.fonder.domain.usecase.FundNameMatcher
import se.partee71.fonder.domain.usecase.FundScreenFilter
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kontrakt för fondmetadata (avgift, kategori, fondtyp, risk, se KRAVLISTA TP-21) — sökbart
 * via Avanzas odokumenterade fond-API (samma källa som TP-14), skrivs igenom till en lokal
 * cache som samma fråga kan besvaras ur offline (ÖV-6). Rent datalager, ingen UI ännu — se
 * issue #57.
 */
interface FundMetadataRepository {
    /**
     * Kör [query] mot källan och skriver igenom resultatet till cachen. Nätverksfel eller ett
     * ur funktion oduglig källa (fel/tomt svar) faller tillbaka på att filtrera den lokala
     * cachen med samma semantik ([FundScreenFilter]) — krascha aldrig, degradera till cache
     * (samma princip som [FundPriceRepository]).
     */
    suspend fun query(query: FundScreenQuery): List<FundMetadata>

    /**
     * Köpbarhet hos Handelsbanken för en fond som redan finns i cachen (via [isin]) — null om
     * fonden inte är känd sedan tidigare (ingen namn/ISIN att slå upp med). Verifierar en
     * namnkandidat i fondlista-katalogen mot [isin] (samma princip som `ImportFundMatcher`,
     * TP-13) i stället för att lita på namnmatchning ensam (andelsklassfamiljer, issue #45).
     * Resultatet (träff eller miss) cachas med en egen TTL ([FundMetadataFreshness]) — så
     * samma fond inte slås upp om vid varje anrop.
     */
    suspend fun resolveHandelsbankenAvailability(isin: String): Boolean?

    /** Senast kända filtervokabulär, se [se.partee71.fonder.data.datastore.PreferencesRepository.fundFilterVocabulary]. */
    fun observeFilterVocabulary(): Flow<FundFilterVocabulary>

    /**
     * Föreslår billigare, ISIN-verifierat köpbara alternativ till innehavet med [isin]
     * (ANA-9, issue #59) — se [FeeComparisonCalc] för matchningsregeln (identisk
     * exponering, strikt lägre avgift). [holdingValue] är innehavets nuvarande värde
     * (netShares × senaste NAV), används för att räkna årsbesparingen i kronor.
     *
     * Null om [isin] inte kan slås upp i källans universum eller saknar känd avgift —
     * inget att jämföra med (UI visar "kunde inte jämföras"). Tom lista om inga
     * kvalificerade, köpbara alternativ hittades. Köpbarheten verifieras budgeterat (ett
     * fåtal kandidater visas, ett begränsat antal prövas) och kan därför ta flera
     * nätverksanrop — körs asynkront, blockerar aldrig UI.
     */
    suspend fun suggestCheaperAlternatives(isin: String, holdingValue: Double): List<FeeComparisonCalc.Alternative>?

    /**
     * Fondmetadata för [isins] (HEM-5, issue #60) — cache-först: en färsk cachad rad
     * (yngre än [FundMetadataFreshness.FEE_TTL_DAYS]) kostar inget nätanrop, en saknad eller
     * inaktuell rad hämtas om. Isin som inte kan slås upp i källans universum saknas i
     * resultatkartan — anroparen ska räkna det innehavet som "okänd avgift", aldrig gissa.
     */
    suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata>

    /**
     * Som [metadataFor], men **enbart ur cachen** — läser aldrig nätverket, ens för en saknad
     * eller inaktuell rad (SET-5/facit, issue #80). Facit slår upp fondnamn för varje inspelat
     * förslag, och historiken kan innehålla hundratals ISIN: en cache-först-läsning hade blivit
     * en burst av uppslag varje gång skärmen öppnas, exakt den kostnad HEM-5/HEM-8 redan lagt
     * på bakgrundsworkerns backstop. Saknas raden utelämnas ISIN:et ur kartan — anroparen ska
     * visa det som okänt, aldrig gissa.
     */
    suspend fun cachedMetadataFor(isins: List<String>): Map<String, FundMetadata>

    /**
     * Risknivå per **normaliserat fondnamn** ([FundNameKey]) ur cachen, aldrig nätverket
     * (UI-10, issue #85). Finns för fondsök, vars träffar kommer ur fondlista-katalogen och
     * saknar ISIN (`HandelsbankenHtmlParser.parseFundCatalog`) — utan ett ISIN att slå upp är
     * namnet den enda kopplingen till metadatan.
     *
     * Bara cachen, av samma skäl som [cachedMetadataFor]: katalogen är ~1500 fonder, och ett
     * nätverksuppslag per rad vore en burst varje gång listan filtreras om. Fonder som inte
     * hunnit hamna i cachen saknas därför i kartan och visas som okänd risk — cachen fylls på
     * av innehavens (HEM-5/POR-9) och bytesplanens (HEM-8) egna uppslag över tid.
     */
    suspend fun cachedRiskByFundName(): Map<String, Int>

    /**
     * Kända risknivåer på källans skala (TP-21, SET-3/issue #68) — unionen av senast kända
     * filtervokabulär ([se.partee71.fonder.data.datastore.PreferencesRepository.fundFilterVocabulary],
     * som bara fylls av Fondsök/ANA-9:s frågeflöden och därför kan vara tom för en användare
     * som aldrig öppnat dem) och de redan cachade fondernas egen `risk` (fylld redan av
     * HEM-5/POR-9:s [metadataFor]-anrop, mer pålitligt populerad för en användare med
     * innehav). Läser aldrig nätverket. Tom lista om ingen fondmetadata alls hämtats än —
     * aldrig en hårdkodad skala.
     */
    suspend fun knownRiskLevels(): List<Int>

    /**
     * Köpkandidater på [level] för bytesplanen (HEM-8, issue #70) — samma
     * köpbarhetsverifieringsmekanik som [suggestCheaperAlternatives]/ANA-9 (issue #59): frågar
     * källan för risknivån **sorterad på högst 12-månadersavkastning** (källans sida rymmer
     * bara 20 träffar, TP-21 — sorteringen avgör därför vilken ände av nivån som blir synlig,
     * se issue #75), behåller bara kandidater med känd [FundMetadata.developmentOneYear]
     * (annars ingen signal att rangordna på, se [SwitchPlanCalc]) och känd avgift, och
     * ISIN-verifierar köpbarhet budgeterat. Resultatet är rangordnat på avkastning, fallande.
     * [excludeIsins] hoppas över innan verifiering (redan sålda/köpta i samma plan, eller
     * redan innehavda fonder). Tom lista om inga kvalificerade, köpbara kandidater hittades —
     * aldrig en gissad kandidat.
     */
    suspend fun findSwitchCandidates(level: Int, excludeIsins: Set<String>): List<SwitchPlanCalc.Candidate>
}

@Singleton
class AvanzaFundMetadataRepository @Inject constructor(
    private val source: AvanzaSource,
    private val dao: FundMetadataDao,
    private val preferencesRepository: PreferencesRepository,
    private val fundPriceRepository: FundPriceRepository,
) : FundMetadataRepository {

    /**
     * Senast kända `totalNoFunds` för en **obefiltrerad** fråga — baslinjen som avslöjar om
     * källan tyst slutat respektera ett kategoriskt filter (samma antal träffar som
     * obefiltrerat, se KRAVLISTA TP-21). I minnet bara (som `cachedCatalog` i
     * [HandelsbankenFundPriceRepository]) — nollställs vid processomstart, vilket bara gör att
     * den allra första filtrerade frågan i en ny process inte kan avslöja tystnaden, ingen
     * korrekthetsrisk.
     */
    private var lastKnownUnfilteredTotal: Int? = null

    override fun observeFilterVocabulary(): Flow<FundFilterVocabulary> = preferencesRepository.fundFilterVocabulary

    override suspend fun query(query: FundScreenQuery): List<FundMetadata> {
        val live = fetchLive(query) ?: return offlineQuery(query)

        if (live.funds.isNotEmpty()) {
            dao.upsertAll(live.funds.map { it.toEntityPreservingDerivedState() })
        }
        if (live.vocabulary.filters.isNotEmpty()) {
            preferencesRepository.setFundFilterVocabulary(live.vocabulary)
        }

        if (!query.hasCategoricalFilters()) {
            // Bara en fråga helt utan filter (varken kategoriskt, avgiftstak eller fritext) speglar
            // hela universumet — en maxTotalFee-only fråga (t.ex. FeeComparisonCalc.candidateQuery
            // för ett innehav utan egna dimension-taggar) har redan en verifierat respekterad
            // avgränsning och skulle annars förorena baslinjen med sitt eget, mindre antal.
            if (query.maxTotalFee == null && query.nameContains == null) {
                lastKnownUnfilteredTotal = live.totalNoFunds
            }
            return live.funds
        }

        val baseline = lastKnownUnfilteredTotal
        if (baseline != null && live.totalNoFunds == baseline) {
            Log.w(TAG, "Källan verkar ha ignorerat filtret (samma antal träffar som obefiltrerat) — filtrerar lokalt")
            return offlineQuery(query)
        }
        return live.funds
    }

    private suspend fun fetchLive(query: FundScreenQuery): AvanzaFundListParser.ParsedFundListPage? =
        runCatching {
            val payload = AvanzaFundListRequestBuilder.buildPayload(query)
            AvanzaFundListParser.parse(source.fetchFundList(payload))
        }.onFailure { e ->
            Log.w(TAG, "Kunde inte hämta fondmetadata, faller tillbaka på cachen", e)
        }.getOrNull()

    private suspend fun offlineQuery(query: FundScreenQuery): List<FundMetadata> =
        FundScreenFilter.apply(dao.getAll().map { it.toDomain() }, query)

    /**
     * En färsk [FundMetadata] från källan bär aldrig köpbarhet eller jämförelseresultat (de
     * sätts bara av [resolveHandelsbankenAvailability] respektive [suggestCheaperAlternatives])
     * — utan den här sammanslagningen skulle varje ny livehämtning tyst nollställa redan
     * uppslagen köpbarhet/jämförelse för en fond som råkar dyka upp i resultatet igen, i strid
     * med att "både träff och miss cachas" (TP-21) och att en persisterad jämförelse (HEM-6,
     * issue #61) ska överleva tills den blir inaktuell — inte tills nästa oberoende livehämtning.
     */
    private suspend fun FundMetadata.toEntityPreservingDerivedState(): FundMetadataEntity {
        val existing = dao.getByIsin(isin)
        return FundMetadataEntity.fromDomain(this, fetchedAtEpochDay = LocalDate.now().toEpochDay()).copy(
            availableAtHandelsbanken = existing?.availableAtHandelsbanken,
            availabilityResolvedAtEpochDay = existing?.availabilityResolvedAtEpochDay,
            cheapestAlternativeIsin = existing?.cheapestAlternativeIsin,
            cheapestAlternativeFee = existing?.cheapestAlternativeFee,
            comparisonResolvedAtEpochDay = existing?.comparisonResolvedAtEpochDay,
        )
    }

    override suspend fun resolveHandelsbankenAvailability(isin: String): Boolean? {
        val cached = dao.getByIsin(isin) ?: return null

        val resolvedAt = cached.availabilityResolvedAtEpochDay
        if (cached.availableAtHandelsbanken != null && resolvedAt != null &&
            !FundMetadataFreshness.isStale(resolvedAt, LocalDate.now())
        ) {
            return cached.availableAtHandelsbanken
        }

        // Null = katalogen gick inte att hämta. Då är svaret okänt, inte "nej": skriv ingen
        // cache, så nästa körning försöker igen i stället för att låsa fast en påhittad miss
        // i AVAILABILITY_TTL_DAYS dygn.
        val available = resolveViaFondlistaCatalog(cached.name, isin) ?: return null
        dao.upsert(
            cached.copy(
                availableAtHandelsbanken = available,
                availabilityResolvedAtEpochDay = LocalDate.now().toEpochDay(),
            ),
        )
        return available
    }

    /**
     * ISIN-verifierar upp till [MAX_ISIN_VERIFICATIONS] rankade namnkandidater i
     * Handelsbankens fondlista-katalog mot [isin] — samma princip som
     * `HandelsbankenFundPriceRepository.resolveFondlistaFundId`/`ImportFundMatcher` (regel 4).
     * Ren namnmatchning räcker inte: en andelsklassfamilj kan rangordna fel syskonfond högst
     * (issue #45), så bara en ISIN-bekräftad träff räknas som köpbar.
     *
     * **Null när katalogen inte kunde hämtas** — ett obesvarat "vet inte", skilt från ett
     * verifierat `false`. Tidigare gav en tom katalog vid nätverksfel ett `false` som såg
     * auktoritativt ut, cachades i 30 dygn och tyst slog ut både ANA-9 och hela bytesplanen.
     */
    private suspend fun resolveViaFondlistaCatalog(fundName: String, isin: String): Boolean? {
        val catalog = fundPriceRepository.fetchFundCatalog() ?: return null
        val candidates = FundNameMatcher.rankedMatches(fundName, catalog.funds)
        return candidates
            .take(MAX_ISIN_VERIFICATIONS)
            .any { fundPriceRepository.lookupIsin(it.fund.fundId) == isin }
    }

    override suspend fun suggestCheaperAlternatives(isin: String, holdingValue: Double): List<FeeComparisonCalc.Alternative>? {
        val held = findByIsin(isin) ?: return null
        if (held.totalFee == null) return null

        val candidates = query(FeeComparisonCalc.candidateQuery(held))
        val ranked = FeeComparisonCalc.rank(held, candidates, holdingValue)

        val verified = mutableListOf<FeeComparisonCalc.Alternative>()
        for (alternative in ranked.take(MAX_VERIFICATION_ATTEMPTS)) {
            if (verified.size >= MAX_VERIFIED_ALTERNATIVES) break
            if (resolveHandelsbankenAvailability(alternative.candidate.isin) == true) {
                verified += alternative
            }
        }
        persistComparisonResult(isin, verified.firstOrNull())
        return verified
    }

    /**
     * Sparar resultatet av en jämförelse för portföljens samlade besparingspotential
     * (HEM-6, issue #61) — [best] är redan rankad på störst besparing (samma sak som lägst
     * avgift, eftersom `annualSavingsKr` är monotont avtagande i kandidatens avgift för ett
     * givet innehav och värde), så `verified.firstOrNull()` i anroparen är det billigaste
     * verifierade alternativet. Kronbesparingen sparas medvetet inte — bara avgiften, så den
     * kan räknas om ur innehavets aktuella värde vid visning i stället för att bli fel så
     * fort NAV rör sig. [best] null (ingen kandidat kvalificerade eller verifierades) sparas
     * som "jämfört, inget billigare hittades" — skilt från att aldrig ha jämförts alls, se
     * [FundMetadata.comparisonResolvedAtEpochDay].
     */
    private suspend fun persistComparisonResult(isin: String, best: FeeComparisonCalc.Alternative?) {
        val cached = dao.getByIsin(isin) ?: return
        dao.upsert(
            cached.copy(
                cheapestAlternativeIsin = best?.candidate?.isin,
                cheapestAlternativeFee = best?.candidateFeePercent,
                comparisonResolvedAtEpochDay = LocalDate.now().toEpochDay(),
            ),
        )
    }

    /**
     * Slår upp en enskild fonds metadata exakt via ISIN (källans `name`-filter accepterar
     * ett ISIN och ger då en enda träff, TP-21) — medvetet **inte** via [query]: dess
     * offline-fallback matchar [FundScreenQuery.nameContains] mot fondens **namn**
     * ([FundScreenFilter]), inte ISIN, och dess baslinjelogik för "källan ignorerar
     * filtret tyst" är bara meningsfull för kategoriska frågor — ett ISIN-uppslag skulle
     * annars förorena [lastKnownUnfilteredTotal] med totalen för en enda fond. Faller
     * tillbaka på cachen direkt vid nätverksfel, precis som [query].
     *
     * Returnerar alltid den **lagrade** raden, aldrig livesvaret rakt av: källan känner inte
     * till appens härledda fält ([FundMetadata.availableAtHandelsbanken],
     * `cheapestAlternative*`, [FundMetadata.comparisonResolvedAtEpochDay]), så ett livesvar
     * bär dem alltid som null. [toEntityPreservingDerivedState] bevarar dem i databasen —
     * men returnerades livesvaret tappades de ändå på vägen ut, och HEM-6 rapporterade
     * "0 av N jämförda" trots en färsk sparad jämförelse.
     */
    private suspend fun findByIsin(isin: String): FundMetadata? {
        val live = fetchLive(FundScreenQuery(nameContains = isin))
        if (live != null && live.funds.isNotEmpty()) {
            dao.upsertAll(live.funds.map { it.toEntityPreservingDerivedState() })
        }
        return dao.getByIsin(isin)?.toDomain()
    }

    override suspend fun metadataFor(isins: List<String>): Map<String, FundMetadata> {
        val today = LocalDate.now()
        val result = mutableMapOf<String, FundMetadata>()
        for (isin in isins.distinct()) {
            val cached = dao.getByIsin(isin)
            val metadata = if (cached != null && !FundMetadataFreshness.isStale(cached.fetchedAtEpochDay, today, FundMetadataFreshness.FEE_TTL_DAYS)) {
                cached.toDomain()
            } else {
                findByIsin(isin)
            }
            if (metadata != null) result[isin] = metadata
        }
        return result
    }

    override suspend fun cachedMetadataFor(isins: List<String>): Map<String, FundMetadata> {
        val distinct = isins.distinct()
        if (distinct.isEmpty()) return emptyMap()
        // Ingen färskhetsgate här, till skillnad från metadataFor: en inaktuell rad är fortfarande
        // rätt *namn* på fonden, och namnet är allt facit behöver. Att kasta den och hämta om vore
        // just nätverkskostnaden metoden finns för att undvika.
        return dao.getByIsins(distinct).associate { it.isin to it.toDomain() }
    }

    override suspend fun cachedRiskByFundName(): Map<String, Int> =
        // Sista raden vinner vid namnkollision (andelsklasser med identiskt normaliserat namn).
        // Ett godtyckligt men konsekvent val: alternativet vore att utelämna kollisioner helt,
        // men två andelsklasser av samma fond har i praktiken samma risknivå — det är den
        // siffran som visas, oavsett vilken rad den lästes ur.
        dao.getKnownRisks().associate { FundNameKey.of(it.name) to it.risk }

    override suspend fun knownRiskLevels(): List<Int> {
        // Källans egen `type`-sträng för risk-dimensionen är "risk" (verifierat live
        // 2026-08-01, samma `filterCounts`-svar som TP-21 i övrigt bygger vokabulären ur).
        val fromVocabulary = preferencesRepository.fundFilterVocabulary.first().filters[RISK_VOCABULARY_KEY].orEmpty().mapNotNull { it.toIntOrNull() }
        val fromCache = dao.getAll().mapNotNull { it.risk }
        return (fromVocabulary + fromCache).distinct().sorted()
    }

    override suspend fun findSwitchCandidates(level: Int, excludeIsins: Set<String>): List<SwitchPlanCalc.Candidate> {
        // Källans sida är hårt låst till 20 träffar (TP-21), så sorteringen avgör *vilken ände*
        // av nivån som ens blir synlig. Tidigare hämtades den billigaste änden, varpå
        // SwitchPlanCalcs kvartilregel bara kunde särskilja de billigaste fonderna inbördes och
        // den uppmätta avkastningskanten (#72) aldrig applicerades — issue #75, punkt 3.
        val results = query(
            FundScreenQuery(
                risk = listOf(level.toString()),
                sortField = SORT_FIELD_DEVELOPMENT_ONE_YEAR,
                sortDirection = FundScreenSortDirection.DESCENDING,
            ),
        )
        // Rangordna om lokalt oavsett vad källan gav: ett okänt sortField ignoreras tyst av
        // källan ("fail open", samma verifierade beteende som filtren i [query]), och då vore
        // sidan sorterad på något helt annat utan att något syntes.
        val eligible = results
            .filter { it.isin !in excludeIsins && it.developmentOneYear != null && it.totalFee != null }
            .sortedByDescending { it.developmentOneYear }
        if (!results.isSortedByOneYearDescending()) {
            Log.w(TAG, "Källan verkar ha ignorerat sorteringen på $SORT_FIELD_DEVELOPMENT_ONE_YEAR — rangordnar kandidaterna lokalt")
        }

        val verified = mutableListOf<SwitchPlanCalc.Candidate>()
        for (metadata in eligible.take(MAX_SWITCH_VERIFICATION_ATTEMPTS)) {
            if (verified.size >= MAX_SWITCH_CANDIDATES) break
            if (resolveHandelsbankenAvailability(metadata.isin) == true) {
                verified += SwitchPlanCalc.Candidate(metadata, metadata.developmentOneYear!!)
            }
        }
        return verified
    }

    /**
     * Sant om sidan faktiskt kom avkastningssorterad (fallande) från källan — rader utan känd
     * `developmentOneYear` hoppas över, de säger inget om ordningen. Bara en signal till loggen:
     * rangordningen görs lokalt ändå, se [findSwitchCandidates].
     */
    private fun List<FundMetadata>.isSortedByOneYearDescending(): Boolean {
        val known = mapNotNull { it.developmentOneYear }
        return known.zipWithNext().all { (first, second) -> first >= second }
    }

    private companion object {
        const val TAG = "FundMetadataRepository"
        const val MAX_ISIN_VERIFICATIONS = 5
        const val MAX_VERIFIED_ALTERNATIVES = 3
        const val MAX_VERIFICATION_ATTEMPTS = 10
        const val RISK_VOCABULARY_KEY = "risk"

        /**
         * Källans sorteringsnyckel för 12-månadersavkastning — samma namn som fältet i svaret
         * ([AvanzaFundListParser]). En **transportnyckel**, i samma kategori som filternamnen i
         * [se.partee71.fonder.data.network.AvanzaFundListRequestBuilder]: skulle källan inte
         * känna igen den ignoreras den tyst, vilket [findSwitchCandidates] både loggar och
         * kompenserar för genom att rangordna lokalt. Delas med [FundScreenFilter], som
         * sorterar på samma nyckel när frågan besvaras ur cachen (ÖV-6).
         */
        const val SORT_FIELD_DEVELOPMENT_ONE_YEAR = FundScreenFilter.SORT_FIELD_DEVELOPMENT_ONE_YEAR

        /** Tak på antal verifierat köpbara kandidater per risknivå (HEM-8, issue #70) — samma budgetprincip som ANA-9. */
        const val MAX_SWITCH_CANDIDATES = 5

        /** Tak på antal prövade kandidater innan verifieringen ger upp för nivån, även om inga bekräftas. */
        const val MAX_SWITCH_VERIFICATION_ATTEMPTS = 10
    }
}
