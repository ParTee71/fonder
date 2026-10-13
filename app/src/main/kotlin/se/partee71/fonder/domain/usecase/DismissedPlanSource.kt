package se.partee71.fonder.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.SuggestionDismissalRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.SuggestionDismissal
import se.partee71.fonder.domain.model.SuggestionKind
import java.time.LocalDate
import javax.inject.Inject

/**
 * De avstådda **planbytenas** inspelade rader (HEM-14) — en källa för Beslut
 * ([PendingDecisionsSource]) och Fonddetalj (ANA-10), så de två aldrig räknar olika.
 *
 * Raderna hämtas ur den **senaste inspelningen per par**, inte ur den visade planen: sedan
 * omräkningen tar avstådda par ur kandidatmängden (HEM-8) finns de inte i den senaste körningen,
 * och utan den här vägen försvann "Ångra" vid nästa skanning. Bara de par som faktiskt är avstådda
 * just nu frågas efter ([SuggestionRecordRepository.observeLatestPlanRecordsFor]) — historiken
 * materialiseras aldrig i det alltid aktiva märkesflödet (NAV-9). Ett genomfört förslag räknas
 * aldrig som avstått ([SuggestionDismissals.hides]).
 *
 * Ingen egen färskhetsgräns på raderna, med flit: raden visas för att **avståendet** gäller
 * ([SuggestionDismissals.TTL_DAYS], 30 dagar) och ska gå att ångra hela den tiden — inte för att
 * planen fortfarande ger bytet (det gör den per definition inte, paret är uteslutet). Raden kan inte
 * vara uråldrig: ett avstående görs på en visad planrad, som är högst [SwitchPlanCalc.PLAN_TTL_DAYS]
 * gammal, så den senaste raden för paret är som mest 30 + 7 dagar gammal när avståendet löper ut.
 * Med `PLAN_TTL_DAYS` som gräns hade "Ångra" försvunnit efter en vecka medan avståendet fortsatte
 * dölja paret i tre.
 *
 * Avståendena följer med i **samma** emission som raderna: läsaren delar planen i visade och
 * avstådda med dem, och hade de kommit ur ett eget flöde fanns ett mellanläge där ett nyss avstått
 * byte varken låg i planen eller under "Avstådda".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DismissedPlanSource internal constructor(
    private val suggestionRecordRepository: SuggestionRecordRepository,
    private val suggestionDismissalRepository: SuggestionDismissalRepository,
    private val preferencesRepository: PreferencesRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val today: () -> LocalDate,
) {
    @Inject
    constructor(
        suggestionRecordRepository: SuggestionRecordRepository,
        suggestionDismissalRepository: SuggestionDismissalRepository,
        preferencesRepository: PreferencesRepository,
        fundMetadataRepository: FundMetadataRepository,
    ) : this(suggestionRecordRepository, suggestionDismissalRepository, preferencesRepository, fundMetadataRepository, LocalDate::now)

    /**
     * Alla avståenden (även utgångna, som [SuggestionDismissalRepository.observeAll]) och de avstådda
     * planbytena **resolvade** ([SwitchPlanResolver.resolveRecord], namn ur metadatacachen) — tomma
     * utan ISK/KF (SET-4), samma grind som planen, så Beslut och Fonddetalj läser samma resultat.
     *
     * En rad vars fonder saknas i metadatacachen faller ur listan — **samma regel som för den
     * visade planen**, vars rader resolvas likadant: cachen fylls av skanningen som spelade in
     * raden och töms bara med allt annat (SET-1), så fallet är ett tömt eller återställt
     * läge där planen själv inte heller visas. Avståendet ligger kvar i tabellen och i
     * säkerhetskopian; bara presentationen saknar ett namn att visa.
     */
    data class DismissedPlan(
        val dismissals: List<SuggestionDismissal>,
        val suggestions: List<SwitchPlanResolver.Suggestion>,
    )

    fun observe(): Flow<DismissedPlan> =
        suggestionDismissalRepository.observeAll().flatMapLatest { dismissals ->
            // Frågan ställs för paren som var aktiva när tabellen emitterade; giltigheten räknas
            // om vid **varje** emission (som partition gör), så ett avstående som löper ut utan
            // att tabellen rörs faller ur nästa gång något annat emitterar — inte först vid nästa skrivning.
            val pairs = SuggestionDismissals.activeKeys(dismissals, today())
                .filter { it.kind == SuggestionKind.RISK_PLAN }
                .mapTo(mutableSetOf()) { it.sellIsin to it.buyIsin }
            combine(suggestionRecordRepository.observeLatestPlanRecordsFor(pairs), preferencesRepository.accountType) { records, accountType ->
                records to accountType
            }.mapLatest { (records, accountType) ->
                val activeKeys = SuggestionDismissals.activeKeys(dismissals, today())
                val dismissed = records.filter { SuggestionDismissals.hides(activeKeys, it.kind, it.sellIsin, it.buyIsin, it.followed == true) }
                val suggestions = if (accountType == AccountType.ISK_KF && dismissed.isNotEmpty()) {
                    val metadata = fundMetadataRepository.cachedMetadataFor(dismissed.flatMap { listOf(it.sellIsin, it.buyIsin) })
                    dismissed.mapNotNull { SwitchPlanResolver.resolveRecord(it, metadata) }
                } else {
                    emptyList()
                }
                DismissedPlan(dismissals = dismissals, suggestions = suggestions)
            }
        }
}
