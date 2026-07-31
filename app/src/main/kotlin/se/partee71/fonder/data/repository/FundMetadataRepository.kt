package se.partee71.fonder.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.network.AvanzaFundListParser
import se.partee71.fonder.data.network.AvanzaFundListRequestBuilder
import se.partee71.fonder.data.network.AvanzaSource
import se.partee71.fonder.data.room.daos.FundMetadataDao
import se.partee71.fonder.data.room.entities.FundMetadataEntity
import se.partee71.fonder.domain.model.FundFilterVocabulary
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.usecase.FeeComparisonCalc
import se.partee71.fonder.domain.usecase.FundMetadataFreshness
import se.partee71.fonder.domain.usecase.FundNameMatcher
import se.partee71.fonder.domain.usecase.FundScreenFilter
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
            dao.upsertAll(live.funds.map { it.toEntityPreservingAvailability() })
        }
        if (live.vocabulary.filters.isNotEmpty()) {
            preferencesRepository.setFundFilterVocabulary(live.vocabulary)
        }

        if (!query.hasCategoricalFilters()) {
            lastKnownUnfilteredTotal = live.totalNoFunds
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
     * En färsk [FundMetadata] från källan bär aldrig köpbarhet (den sätts bara av
     * [resolveHandelsbankenAvailability]) — utan den här sammanslagningen skulle varje ny
     * livehämtning tyst nollställa en redan uppslagen köpbarhet för en fond som råkar dyka upp
     * i resultatet igen, i strid med att "både träff och miss cachas" (TP-21).
     */
    private suspend fun FundMetadata.toEntityPreservingAvailability(): FundMetadataEntity {
        val existing = dao.getByIsin(isin)
        return FundMetadataEntity.fromDomain(this, fetchedAtEpochDay = LocalDate.now().toEpochDay()).copy(
            availableAtHandelsbanken = existing?.availableAtHandelsbanken,
            availabilityResolvedAtEpochDay = existing?.availabilityResolvedAtEpochDay,
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

        val available = resolveViaFondlistaCatalog(cached.name, isin)
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
     */
    private suspend fun resolveViaFondlistaCatalog(fundName: String, isin: String): Boolean {
        val catalog = fundPriceRepository.fetchFundCatalog()
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
        return verified
    }

    /**
     * Slår upp en enskild fonds metadata exakt via ISIN (källans `name`-filter accepterar
     * ett ISIN och ger då en enda träff, TP-21) — medvetet **inte** via [query]: dess
     * offline-fallback matchar [FundScreenQuery.nameContains] mot fondens **namn**
     * ([FundScreenFilter]), inte ISIN, och dess baslinjelogik för "källan ignorerar
     * filtret tyst" är bara meningsfull för kategoriska frågor — ett ISIN-uppslag skulle
     * annars förorena [lastKnownUnfilteredTotal] med totalen för en enda fond. Faller
     * tillbaka på cachen direkt vid nätverksfel, precis som [query].
     */
    private suspend fun findByIsin(isin: String): FundMetadata? {
        val live = fetchLive(FundScreenQuery(nameContains = isin))
        if (live != null && live.funds.isNotEmpty()) {
            dao.upsertAll(live.funds.map { it.toEntityPreservingAvailability() })
        }
        return live?.funds?.firstOrNull { it.isin == isin } ?: dao.getByIsin(isin)?.toDomain()
    }

    private companion object {
        const val TAG = "FundMetadataRepository"
        const val MAX_ISIN_VERIFICATIONS = 5
        const val MAX_VERIFIED_ALTERNATIVES = 3
        const val MAX_VERIFICATION_ATTEMPTS = 10
    }
}
