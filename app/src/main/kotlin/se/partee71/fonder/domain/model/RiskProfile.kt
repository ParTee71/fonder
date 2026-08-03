package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/** Hur långt fram användaren räknar med att kunna använda pengarna — en av tre frågor i riskprofilen (SET-3, issue #68). */
enum class TimeHorizon { UNDER_3_AR, TRE_TILL_7_AR, SJU_TILL_15_AR, OVER_15_AR }

/** Hur användaren tror sig reagera vid en kraftig nedgång (30 %) — en av tre frågor i riskprofilen (SET-3, issue #68). */
enum class DownturnReaction { SALJER_ALLT, SALJER_DEL, GOR_INGET, KOPER_MER }

/** Sparandets primära mål — en av tre frågor i riskprofilen (SET-3, issue #68). */
enum class PrimaryGoal { BEVARA, BALANSERAD, MAXIMAL_TILLVAXT }

/**
 * Svaren på riskprofilens tre frågor — sparas separat från [RiskProfile.targetAllocation] så en
 * framtida ändring av [se.partee71.fonder.domain.usecase.RiskProfileCalc]s poängsättning inte
 * tyst skriver om en gammal slutsats (SET-3, issue #68).
 */
@Serializable
data class RiskProfileAnswers(
    val horizon: TimeHorizon,
    val reaction: DownturnReaction,
    val goal: PrimaryGoal,
)

/**
 * Användarens riskprofil (SET-3, issue #68 → målfördelning issue #71). [targetAllocation] är en
 * **målfördelning** över risknivåer (källans egen riskskala, [FundMetadata.risk], TP-21) —
 * nivå till andel (0.0..1.0). En enda nivå är specialfallet `{N: 1.0}`. Alltid det användaren
 * själv valt eller godkänt — aldrig bara ett oöversynt enkätförslag.
 *
 * [targetRiskLevel] är kvar som ett **legacy-fält** (#68:s ursprungliga skalärmodell) — läs det
 * bara via [effectiveAllocation], och bara när [targetAllocation] är tom. Ny kod skriver aldrig
 * det här fältet. Utan det skulle en redan sparad #68-profil sluta avkodas
 * (`PreferencesRepository.riskProfile` sväljer avkodningsfel tyst via `runCatching`) och
 * försvinna spårlöst utan felmeddelande — en verklig dataförlustbugg, se issue #71.
 *
 * [answers] är svaren som låg bakom ett ev. förslag, eller null om fördelningen satts direkt
 * utan att enkäten besvarats.
 *
 * Genuin användardata, till skillnad från
 * [se.partee71.fonder.data.datastore.PreferencesRepository.lastPriceSyncEpochMillis]/
 * `fundFilterVocabulary` som är ren cache-metadata — ska ingå i backup-kontraktet (NFR-1) när
 * Drive-backup (TP-7) byggs, se [se.partee71.fonder.data.repository.StubBackupRepository].
 */
@Serializable
data class RiskProfile(
    val targetAllocation: Map<Int, Double> = emptyMap(),
    val answers: RiskProfileAnswers? = null,
    val targetRiskLevel: Int? = null,
) {
    /**
     * [targetAllocation], eller en migrerad `{N: 1.0}` ur [targetRiskLevel] om fördelningen
     * saknas — den faktiska källan att läsa målfördelningen ur, aldrig fälten direkt.
     */
    val effectiveAllocation: Map<Int, Double>
        get() = targetAllocation.ifEmpty { targetRiskLevel?.let { mapOf(it to 1.0) }.orEmpty() }

    /**
     * Värdeviktat snitt av [effectiveAllocation] (`Σ nivå × andel`) — samma precisionsprincip
     * som [se.partee71.fonder.domain.usecase.PortfolioRiskCalc]s motsvarande faktiska snitt, så
     * de två kan visas som en jämförbar sammanfattning (HEM-7). Null bara om ingen fördelning
     * alls kan härledas (ingen profil satt).
     */
    val weightedTargetLevel: Double?
        get() = effectiveAllocation.takeIf { it.isNotEmpty() }?.entries?.sumOf { it.key * it.value }
}
