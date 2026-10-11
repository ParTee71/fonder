package se.partee71.fonder.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.SwitchWatchRepository
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.SwitchWatch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Ett öppet pågående byte, redo för visning (ANA-12, NAV-9) — den enda vägen tillbaka till
 * bevakningen när man lämnat skärmen.
 *
 * Ledaren räknas ur **cachade** kurser (fyllda budgeterat av bakgrundskörningen), aldrig ur ett
 * nätverksanrop: Beslut och Översikten ska rendera direkt, och den dyra hämtningen hör hemma på
 * bevakningens egen skärm. Är ingen kandidat utvärderad än är [leaderName] null, och det sägs ut i
 * stället för att en tom rad läses som "inget har hänt".
 */
data class OpenSwitchWatch(
    val watchId: Long,
    val sellFundName: String,
    val daysWaiting: Long,
    val candidateCount: Int,
    val leaderName: String? = null,
    val leaderChangeFraction: Double? = null,
)

/**
 * Det som väntar på ett beslut (NAV-9, HEM-13): bytesplanen (HEM-8) och de öppna pågående bytena
 * (ANA-12). En källa för fliken Beslut, Översiktens "Att besluta"-rad och flikradens kopparmärke,
 * så de tre aldrig kan visa olika antal.
 */
data class PendingDecisions(
    /** Den senast inspelade planen, resolvad med [SwitchPlanResolver] — tom utan ISK/KF eller med en för gammal plan. */
    val switchPlan: List<SwitchPlanResolver.Suggestion> = emptyList(),
    /** Öppna bevakningar som inte passerat [SwitchWatchCalc.WATCH_TTL_DAYS], i repositoriets ordning. */
    val openWatches: List<OpenSwitchWatch> = emptyList(),
    /**
     * Säljfonder som redan har en öppen bevakning, **även** utgångna — styr om ett kvitterat byte
     * erbjuder "Bevaka alternativ". En utgången bevakning visas inte men blockerar en ny för samma
     * sälj tills bakgrundskörningen stängt den; annars hade samma byte fått två rader.
     */
    val watchedSellIsins: Set<String> = emptySet(),
) {
    /** Förslag användaren ännu inte markerat som genomförda (SET-5) — ett kvitterat byte är redan beslutat. */
    val openSuggestionCount: Int get() = switchPlan.count { !it.followed }

    /** Antalet på Beslut-flikens märke och i "Att besluta": öppna förslag plus pågående byten. */
    val count: Int get() = openSuggestionCount + openWatches.size

    /** Ingenting att visa på Beslut — varken en plan eller ett pågående byte. */
    val isEmpty: Boolean get() = switchPlan.isEmpty() && openWatches.isEmpty()
}

/**
 * Läser [PendingDecisions] ur repositorierna — **bara cachen**, aldrig nätverket. Bytesplanens
 * fondnamn och risknivåer slås upp med [FundMetadataRepository.cachedMetadataFor]: köpkandidaterna
 * cachades när bakgrundskörningen spelade in planen och säljfonderna är innehav, så uppslaget
 * ger samma plan som förut utan att märket i flikraden kan utlösa en hämtning.
 *
 * Kontotypen läses **i** flödet (inte med `first()`), så SET-4-gaten följer en ändring i
 * Inställningar direkt (issue #75).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingDecisionsSource @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val suggestionRecordRepository: SuggestionRecordRepository,
    private val switchWatchRepository: SwitchWatchRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val fundMetadataRepository: FundMetadataRepository,
) {

    fun observe(): Flow<PendingDecisions> =
        combine(switchPlan(), switchWatches()) { plan, (open, watchedSellIsins) ->
            PendingDecisions(switchPlan = plan, openWatches = open, watchedSellIsins = watchedSellIsins)
        }

    private fun switchPlan(): Flow<List<SwitchPlanResolver.Suggestion>> =
        combine(preferencesRepository.accountType, suggestionRecordRepository.observeLatestBatch()) { accountType, batch ->
            accountType to batch
        }.mapLatest { (accountType, batch) ->
            val metadata = fundMetadataRepository.cachedMetadataFor(batch.flatMap { listOf(it.sellIsin, it.buyIsin) })
            SwitchPlanResolver.resolve(accountType, batch, metadata, LocalDate.now())
        }

    private fun switchWatches(): Flow<Pair<List<OpenSwitchWatch>, Set<String>>> =
        switchWatchRepository.observeOpen().flatMapLatest { watches ->
            val today = LocalDate.now()
            val sellIsins = watches.mapTo(mutableSetOf()) { it.sellIsin }
            val visible = watches.filterNot { SwitchWatchCalc.isExpired(it, today) }
            if (visible.isEmpty()) return@flatMapLatest flowOf(emptyList<OpenSwitchWatch>() to sellIsins)

            val candidateIsins = visible.flatMap { watch -> watch.candidates.map { it.isin } }.distinct()
            fundPriceRepository.observeLatestPrices(candidateIsins).map { prices ->
                visible.map { it.toOpen(prices, today) } to sellIsins
            }
        }

    private fun SwitchWatch.toOpen(prices: Map<String, FundPrice>, today: LocalDate): OpenSwitchWatch {
        val leader = SwitchWatchCalc.ranked(
            candidates.map { candidate ->
                SwitchWatchCalc.outcome(
                    candidate = candidate,
                    latestNav = prices[candidate.isin]?.nav,
                    proceedsKr = proceedsKr,
                    soldAtEpochDay = soldAtEpochDay,
                )
            },
        ).firstOrNull { it.isEvaluated }
        return OpenSwitchWatch(
            watchId = id,
            sellFundName = sellFundName,
            daysWaiting = SwitchWatchCalc.daysWaiting(this, today),
            candidateCount = candidates.size,
            leaderName = leader?.candidate?.name,
            leaderChangeFraction = leader?.changeFraction,
        )
    }
}

/**
 * Startar en bevakning av ett pågående byte ur ett kvitterat bytesförslag (ANA-12/HEM-8) — den
 * enda vägen dit för både Beslut och Fonddetalj (ANA-10), så samma förslag aldrig kan ge två
 * olika bevakningar. Säljdagen sätts till [today]: kvitteringen görs när bytet gjorts, och ett
 * påhittat tidigare datum hade mätt kandidaternas utveckling över dagar pengarna inte var fria
 * (ett äldre sälj bevakas i stället från Sålda fonder, SLD-5). Målnivån är förslagets `toLevel`,
 * så de automatiska alternativen (ANA-13) hämtas ur samma pool som planens köpkandidat.
 *
 * Null när säljfonden redan har en öppen bevakning från [today] — annars hade varje tryck startat
 * ytterligare en bevakning av samma byte.
 */
suspend fun SwitchWatchRepository.startFromSuggestion(
    suggestion: SwitchPlanResolver.Suggestion,
    today: LocalDate = LocalDate.now(),
): Long? {
    if (hasOpenFor(suggestion.sellIsin, today.toEpochDay())) return null
    return start(
        SwitchWatch(
            sellIsin = suggestion.sellIsin,
            sellFundName = suggestion.sellFundName,
            soldAtEpochDay = today.toEpochDay(),
            proceedsKr = suggestion.switchValueKr,
            targetLevel = suggestion.toLevel,
            sourceRecordId = suggestion.recordId,
        ),
    )
}
