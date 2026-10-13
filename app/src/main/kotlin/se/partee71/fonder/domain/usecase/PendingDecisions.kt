package se.partee71.fonder.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.SwitchWatchRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.SuggestionDismissal
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
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
 * Det som väntar på ett beslut (NAV-9, HEM-13): bytesplanen (HEM-8) och de billigare alternativen
 * (ANA-9), båda utan avstådda förslag (HEM-14), och de öppna pågående bytena (ANA-12). En källa för
 * fliken Beslut, Översiktens "Att besluta"-rad och flikradens kopparmärke, så de tre aldrig kan visa
 * olika antal.
 */
data class PendingDecisions(
    /**
     * Den senast inspelade planen, resolvad med [SwitchPlanResolver] — tom utan ISK/KF eller med en
     * för gammal plan — **utan** de byten användaren avstått från (HEM-14).
     */
    val switchPlan: List<SwitchPlanResolver.Suggestion> = emptyList(),
    /**
     * Planens byten som användaren avstått från och som fortfarande gäller ([SuggestionDismissals]).
     * Räknas inte i [count]; Beslut listar dem längst ned så ett avstående kan ångras. Hämtas ur
     * den **senaste inspelningen per par** ([DismissedPlanSource]), inte ur den visade planen: ett
     * avstått par tas ur planen vid nästa omräkning (HEM-8/HEM-14), och raden att ångra ska finnas kvar ändå.
     */
    val dismissedSuggestions: List<SwitchPlanResolver.Suggestion> = emptyList(),
    /** De inspelade billigare alternativen ([CheaperAlternatives]) utan de avstådda. */
    val cheaperAlternatives: List<CheaperAlternative> = emptyList(),
    /** Billigare alternativ användaren avstått från — som [dismissedSuggestions]. */
    val dismissedCheaperAlternatives: List<CheaperAlternative> = emptyList(),
    /** Öppna bevakningar som inte passerat [SwitchWatchCalc.WATCH_TTL_DAYS], i repositoriets ordning. */
    val openWatches: List<OpenSwitchWatch> = emptyList(),
    /**
     * Säljfonder som redan har en öppen bevakning, **även** utgångna — styr om ett kvitterat byte
     * erbjuder "Bevaka alternativ". Planradens [SwitchPlanResolver.Suggestion.inProgress] sätts
     * inte härifrån utan av bevakningens källförslag, se [PendingDecisionsSource]. En utgången bevakning visas inte men blockerar en ny för samma
     * sälj tills bakgrundskörningen stängt den; annars hade samma byte fått två rader.
     */
    val watchedSellIsins: Set<String> = emptySet(),
) {
    /**
     * Planens byten som väntar på ett beslut: varken kvitterade (SET-5) eller sålda (BYT-1). En
     * pågående rad räknas inte — dess bevakning räknas redan i [openWatches], och samma byte ska
     * vara **ett** på märket, inte två.
     */
    val openSwitchCount: Int get() = switchPlan.count { !it.followed && !it.inProgress }

    /** Billigare alternativ som varken är kvitterade eller avstådda. */
    val openCheaperAlternativeCount: Int get() = cheaperAlternatives.count { !it.suggestion.followed }

    /** Öppna förslag av båda sorterna — ett avstått förslag (HEM-14) ligger inte i listorna. */
    val openSuggestionCount: Int get() = openSwitchCount + openCheaperAlternativeCount

    /** Antalet på Beslut-flikens märke och i "Att besluta": öppna förslag plus pågående byten. */
    val count: Int get() = openSuggestionCount + openWatches.size

    /** Avstådda förslag av båda sorterna — "Avstådda (N)" på Beslut. */
    val dismissedCount: Int get() = dismissedSuggestions.size + dismissedCheaperAlternatives.size

    /** Ingenting att besluta på Beslut — varken ett förslag som inte avståtts eller ett pågående byte. */
    val isEmpty: Boolean get() = switchPlan.isEmpty() && cheaperAlternatives.isEmpty() && openWatches.isEmpty()
}

/**
 * Läser [PendingDecisions] ur repositorierna — **bara cachen**, aldrig nätverket. Fondnamn, avgifter
 * och risknivåer slås upp med [FundMetadataRepository.cachedMetadataFor]: köpkandidaterna cachades
 * när bakgrundskörningen spelade in förslagen och säljfonderna är innehav, så uppslaget ger samma
 * förslag som förut utan att märket i flikraden kan utlösa en hämtning.
 *
 * Kontotypen läses **i** flödet (inte med `first()`), så SET-4-gaten följer en ändring i
 * Inställningar direkt (issue #75). De billigare alternativen gatas inte på kontotypen — det gör
 * inte ANA-9 heller.
 *
 * De avstådda planbytena kommer ur [DismissedPlanSource] (senaste raden per avstått par, riktad
 * fråga), inte ur den visade planen, och de pågående raderna märks av [InProgressSwitches] — båda
 * delade med Fonddetalj, så de två skärmarna aldrig säger olika om samma rad.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingDecisionsSource @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val suggestionRecordRepository: SuggestionRecordRepository,
    private val switchWatchRepository: SwitchWatchRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val dismissedPlanSource: DismissedPlanSource,
    private val inProgressSwitches: InProgressSwitches,
) {

    fun observe(): Flow<PendingDecisions> =
        combine(suggestions(), switchWatches()) { resolved, watches ->
            PendingDecisions(
                switchPlan = watches.inProgress.mark(resolved.plan),
                dismissedSuggestions = resolved.dismissedPlan,
                cheaperAlternatives = resolved.cheaper,
                dismissedCheaperAlternatives = resolved.dismissedCheaper,
                openWatches = watches.visible,
                watchedSellIsins = watches.sellIsins,
            )
        }

    /** Förslagen resolvade mot samma metadatauppslag, redan delade i visade och avstådda (HEM-14). */
    private class Resolved(
        val plan: List<SwitchPlanResolver.Suggestion>,
        val dismissedPlan: List<SwitchPlanResolver.Suggestion>,
        val cheaper: List<CheaperAlternative>,
        val dismissedCheaper: List<CheaperAlternative>,
    )

    private class SuggestionInput(
        val accountType: AccountType?,
        val emptiedAtEpochMillis: Long?,
        val batch: List<SuggestionRecord>,
        val feeRecords: List<SuggestionRecord>,
        val dismissedPlan: List<SwitchPlanResolver.Suggestion>,
        val dismissals: List<SuggestionDismissal>,
    )

    /** Bytesplanen och de billigare alternativen, resolvade mot samma metadatauppslag. */
    private fun suggestions(): Flow<Resolved> {
        val feeSince = LocalDate.now().minusDays(CheaperAlternatives.TTL_DAYS).toEpochDay()
        val planGate = combine(preferencesRepository.accountType, preferencesRepository.switchPlanEmptiedAtEpochMillis) { type, emptied -> type to emptied }
        return combine(
            planGate,
            suggestionRecordRepository.observeLatestBatch(),
            suggestionRecordRepository.observeRecent(SuggestionKind.FEE, feeSince),
            dismissedPlanSource.observe(),
        ) { (accountType, emptiedAt), batch, feeRecords, dismissedPlan ->
            SuggestionInput(accountType, emptiedAt, batch, feeRecords, dismissedPlan.suggestions, dismissedPlan.dismissals)
        }
            .mapLatest { input ->
                val today = LocalDate.now()
                val isins = (input.batch + input.feeRecords).flatMap { listOf(it.sellIsin, it.buyIsin) }
                val metadata = fundMetadataRepository.cachedMetadataFor(isins)

                val plan = SwitchPlanResolver.resolve(input.accountType, input.batch, metadata, today, input.emptiedAtEpochMillis)
                val (visible, _) = SuggestionDismissals.partition(plan, input.dismissals, today)
                val dismissedPlan = input.dismissedPlan

                val cheaper = CheaperAlternatives.resolve(input.feeRecords, metadata, today)
                val (visibleCheaper, dismissedCheaper) = SuggestionDismissals.partition(cheaper, input.dismissals, today) { it.suggestion }
                Resolved(visible, dismissedPlan, visibleCheaper, dismissedCheaper)
            }
    }

    /** De öppna bevakningarna som Beslut visar, och vilka planrader de gör pågående — ur **en** läsning av tabellen. */
    private class Watches(
        val visible: List<OpenSwitchWatch>,
        /** Säljfonder med öppen bevakning, **även** utgångna — se [PendingDecisions.watchedSellIsins]. */
        val sellIsins: Set<String>,
        val inProgress: InProgressSwitches.Keys,
    )

    private fun switchWatches(): Flow<Watches> =
        switchWatchRepository.observeOpen().flatMapLatest { watches ->
            val today = LocalDate.now()
            val sellIsins = watches.mapTo(mutableSetOf()) { it.sellIsin }
            val visible = watches.filterNot { SwitchWatchCalc.isExpired(it, today) }
            val inProgress = inProgressSwitches.keysFor(watches, today)
            if (visible.isEmpty()) return@flatMapLatest inProgress.map { Watches(emptyList(), sellIsins, it) }

            val candidateIsins = visible.flatMap { watch -> watch.candidates.map { it.isin } }.distinct()
            combine(fundPriceRepository.observeLatestPrices(candidateIsins), inProgress) { prices, keys ->
                Watches(visible = visible.map { it.toOpen(prices, today) }, sellIsins = sellIsins, inProgress = keys)
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
