package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/** Hur långt fram användaren räknar med att kunna använda pengarna — en av tre frågor i riskprofilen (SET-3, issue #68). */
enum class TimeHorizon { UNDER_3_AR, TRE_TILL_7_AR, SJU_TILL_15_AR, OVER_15_AR }

/** Hur användaren tror sig reagera vid en kraftig nedgång (30 %) — en av tre frågor i riskprofilen (SET-3, issue #68). */
enum class DownturnReaction { SALJER_ALLT, SALJER_DEL, GOR_INGET, KOPER_MER }

/** Sparandets primära mål — en av tre frågor i riskprofilen (SET-3, issue #68). */
enum class PrimaryGoal { BEVARA, BALANSERAD, MAXIMAL_TILLVAXT }

/**
 * Svaren på riskprofilens tre frågor — sparas separat från [RiskProfile.targetRiskLevel] så en
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
 * Användarens riskprofil (SET-3, issue #68). [targetRiskLevel] är på källans egen riskskala
 * ([FundMetadata.risk], TP-21) och är alltid det användaren själv valt eller godkänt — aldrig
 * bara ett oöversynt enkätförslag. [answers] är svaren som låg bakom ett ev. förslag, eller
 * null om nivån satts direkt utan att enkäten besvarats.
 *
 * Genuin användardata, till skillnad från
 * [se.partee71.fonder.data.datastore.PreferencesRepository.lastPriceSyncEpochMillis]/
 * `fundFilterVocabulary` som är ren cache-metadata — ska ingå i backup-kontraktet (NFR-1) när
 * Drive-backup (TP-7) byggs, se [se.partee71.fonder.data.repository.StubBackupRepository].
 */
@Serializable
data class RiskProfile(
    val targetRiskLevel: Int,
    val answers: RiskProfileAnswers? = null,
)
