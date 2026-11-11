package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.SwitchWatchRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.SwitchWatch
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

    /** Profilen och bevakningarna, lästa tillsammans — se [observeContext]. */
    class Context(
        val riskProfile: RiskProfile?,
        /** **Alla** bevakningar, även stängda — deras köp får inte förbrukas igen ([SwitchPlanCalc.pendingProceedsByLevel]). */
        internal val watches: List<SwitchWatch>,
        internal val buyIsinByRecordId: Map<Long, String>
    )

    /**
     * Riskprofilen och bevakningarna. Emitterar om när profilen eller en öppen bevakning ändras
     * (ett sälj, ett köp som avslutar den, en stängning). Hela tabellen läses vid varje ändring: en
     * stängd bevaknings köp undantas från förbrukningen, och tabellen är liten.
     */
    fun observeContext(): Flow<Context> = combine(preferencesRepository.riskProfile, switchWatchRepository.observeOpen()) { profile, _ -> profile }
        .mapLatest { profile ->
            val watches = switchWatchRepository.getAll()
            val sourceIds = watches.filter { it.isOpen }.mapNotNullTo(mutableSetOf()) { it.sourceRecordId }
            val buyIsinByRecordId = suggestionRecordRepository.observeByIds(sourceIds).first().associate { it.id to it.buyIsin }
            Context(profile, watches, buyIsinByRecordId)
        }

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
        val pending = SwitchPlanCalc.pendingProceedsByLevel(context.watches, transactions, funds, context.buyIsinByRecordId, metadataByIsin, today)
        return RiskBase(profile, PortfolioRiskCalc.valueByLevel(byRiskLevel), pending)
    }

    companion object {
        /**
         * Riskkortet med planens byten lagda på. Sålda byten (likviden ligger redan på målnivån,
         * BYT-3) och genomförda (syns redan i innehaven) tas bort — annars räknades de två gånger.
         * Ordningen behålls: planen är sekventiell.
         */
        fun summary(base: RiskBase, plan: List<SwitchPlanResolver.Suggestion>): RiskSummary? {
            val moves = plan
                .filterNot { it.inProgress || it.followed }
                .sortedBy { it.planIndex }
                .map { SwitchEffectCalc.Move(it.fromLevel, it.toLevel, it.switchValueKr) }
            val effect = SwitchEffectCalc.compute(base.valueByLevelKr, base.pendingProceedsByLevel, base.riskProfile.effectiveAllocation, moves) ?: return null
            return RiskSummary(base.riskProfile, effect)
        }
    }
}

/** Riskkortet redo för visning: profilen och [SwitchEffectCalc]s resultat. */
data class RiskSummary(val riskProfile: RiskProfile, val effect: SwitchEffectCalc.Result)
