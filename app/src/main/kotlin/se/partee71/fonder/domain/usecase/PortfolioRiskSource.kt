package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.SwitchWatchRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.Transaction

/**
 * Riskkortets underlag (HEM-7, HEM-17) i två steg: [RiskBase] ur portföljen och [summary] när
 * bytesplanen kommer till. Delat av Hem och (issue #240) Beslut, så de två aldrig räknar
 * avvikelsen på olika sätt — Hem räknade den tidigare själv, utan likviden som väntar på köp, och
 * kunde då visa ett gap som planen redan ansåg finansierat.
 *
 * Innehaven, kurserna och metadatan lämnas in av anroparen i stället för att läsas här: Hem slår
 * upp metadatan mot källan i ett eget flöde (nätverk per okänd ISIN, med kortets väntesnurra), och
 * en andra läsväg hade antingen gått till nätet en gång till eller räknat på en äldre cache än
 * resten av skärmen. Det källan själv äger är det bara risken behöver: profilen och bevakningarna
 * med deras källförslag (BYT-3), via [observeContext].
 *
 * Planen läggs på i ett eget steg ([summary]) av samma skäl som Hem håller förslagen i en egen
 * gren: ett nytt förslag ska inte räkna om portföljen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioRiskSource @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val switchWatchRepository: SwitchWatchRepository,
    private val suggestionRecordRepository: SuggestionRecordRepository
) {

    /** Profilen och bevakningarna, lästa tillsammans — se [observeContext]. Jämförs på värde. */
    data class Context(val riskProfile: RiskProfile?, internal val watches: WatchInputs)

    /**
     * Riskprofilen och bevakningarna, samma läsväg som planen ([SwitchPlanInputs.loadWatches]).
     * Läses om när profilen eller en öppen bevakning ändras, men **emitterar bara när något likviden
     * vilar på ändrats**: kandidaternas kurser skrivs av bakgrundskörningen hela tiden, och Hem läser
     * kontexten i samma gren som portföljen — varje sådan skrivning hade annars räknat om hela Översikten.
     */
    fun observeContext(): Flow<Context> = combine(
        preferencesRepository.riskProfile,
        preferencesRepository.switchWatchTtlDays,
        switchWatchRepository.observeOpen()
    ) { profile, ttlDays, _ -> profile to ttlDays }
        .mapLatest { (profile, ttlDays) ->
            val loaded = SwitchPlanInputs.loadWatches(switchWatchRepository, suggestionRecordRepository, ttlDays)
            Context(profile, loaded.copy(watches = loaded.watches.map { it.forPendingProceeds() }))
        }
        .distinctUntilChanged()

    /** Portföljens del av underlaget — null utan profil (då finns inget kort). */
    data class RiskBase(
        val riskProfile: RiskProfile,
        val valueByLevelKr: Map<Int, Double>,
        val pendingProceedsByLevel: Map<Int, Double>
    )

    /**
     * @param byRiskLevel innehavens fördelning per nivå ([PortfolioExposureCalc.Result.byRiskLevel]),
     *   räknad på innehaven med aktuellt värde — samma läge som bytesplanen ser.
     */
    fun base(
        context: Context,
        funds: List<Fund>,
        transactions: List<Transaction>,
        byRiskLevel: PortfolioExposureCalc.Dimension,
        metadataByIsin: Map<String, FundMetadata>,
        today: LocalDate
    ): RiskBase? {
        val profile = context.riskProfile ?: return null
        if (profile.effectiveAllocation.isEmpty()) return null
        val pending = SwitchPlanCalc.pendingProceedsByLevel(context.watches.watches, transactions, funds, context.watches.buyIsinByRecordId, metadataByIsin, today, context.watches.ttlDays)
        return RiskBase(profile, PortfolioRiskCalc.valueByLevel(byRiskLevel), pending)
    }

    companion object {
        /**
         * Riskkortet med planens byten lagda på. Sålda byten (likviden ligger redan på målnivån,
         * BYT-3) och genomförda (syns redan i innehaven) tas bort — annars räknades de två gånger.
         * Ordningen behålls: planen är sekventiell.
         *
         * [plan] är den visade planen, utan avstådda byten (HEM-14). Ett senare byte är räknat under
         * antagandet att de före det görs, så från första lucka i `planIndex` går stegen inte att
         * räkna — de får okänt belopp, och kortet nämner då ingen summa i stället för en som inte går ihop.
         */
        fun summary(base: RiskBase, plan: List<SwitchPlanResolver.Suggestion>): RiskSummary? {
            val sorted = plan.sortedBy { it.planIndex }
            val unbroken = sorted.withIndex().takeWhile { (position, suggestion) -> suggestion.planIndex == position }.count()
            val counted = sorted.withIndex().filterNot { (_, suggestion) -> suggestion.inProgress || suggestion.followed }
            val moves = counted.map { (position, suggestion) ->
                SwitchEffectCalc.Move(suggestion.fromLevel, suggestion.toLevel, suggestion.switchValueKr.takeIf { position < unbroken })
            }
            val effect = SwitchEffectCalc.compute(base.valueByLevelKr, base.pendingProceedsByLevel, base.riskProfile.effectiveAllocation, moves) ?: return null
            // Stegen i samma ordning som [moves] — Beslut visar varje bytes effekt på sin egen rad (issue #240).
            val stepByRecordId = counted.zip(effect.steps).mapNotNull { (indexed, step) -> step?.let { indexed.value.recordId to it } }.toMap()
            return RiskSummary(base.riskProfile, effect, stepByRecordId)
        }
    }
}

/**
 * Riskkortet redo för visning: profilen och [SwitchEffectCalc]s resultat.
 *
 * @param stepByRecordId effekten per nivå av varje planbyte, nyckad på förslagets
 *   [SwitchPlanResolver.Suggestion.recordId] — saknas för sålda, genomförda och steg som inte går att
 *   räkna (okänt belopp eller efter en lucka i planen). Beslut läser den per rad (issue #240).
 */
data class RiskSummary(
    val riskProfile: RiskProfile,
    val effect: SwitchEffectCalc.Result,
    val stepByRecordId: Map<Long, SwitchEffectCalc.StepEffect> = emptyMap()
)

/**
 * Bevakningen med bara det likviden (BYT-3) räknas på — kandidaternas kurser och position tas bort,
 * så att en ny kursnotering hos en kandidat inte ser ut som en ändrad bevakning.
 */
private fun SwitchWatch.forPendingProceeds(): SwitchWatch = copy(candidates = candidates.map { SwitchWatchCandidate(isin = it.isin, name = "") })
