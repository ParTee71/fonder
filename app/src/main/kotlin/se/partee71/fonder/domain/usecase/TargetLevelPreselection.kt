package se.partee71.fonder.domain.usecase

import kotlinx.coroutines.flow.first
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.SwitchWatchRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.RiskProfile
import java.time.LocalDate
import javax.inject.Inject

/**
 * Läsningen bakom målnivåns förval (SLD-5/BYT-2, ANA-13, issue #150) — regeln själv är
 * [SwitchPlanCalc.preselectTargetLevel]. Två anropare: säljet som startar en fristående
 * bevakning ([RecordTransaction], via [preselect]) och skärmen som visar profilens
 * förslag och fyller i en saknad nivå i efterhand (`SwitchWatchViewModel`, via [profileSuggestion]).
 *
 * Utan riskprofil läses ingenting mer än profilen. Riskprofilen läses men **inte** kontotypen —
 * planen visas bara för ISK/KF (SET-4), men var likviden hör hemma beror på målfördelningen, inte
 * på hur ett byte beskattas.
 */
class TargetLevelPreselection internal constructor(
    private val riskProfile: suspend () -> RiskProfile?,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val switchWatchRepository: SwitchWatchRepository,
    private val suggestionRecordRepository: SuggestionRecordRepository,
) {
    @Inject
    constructor(
        preferencesRepository: PreferencesRepository,
        transactionRepository: TransactionRepository,
        fundPriceRepository: FundPriceRepository,
        switchWatchRepository: SwitchWatchRepository,
        suggestionRecordRepository: SuggestionRecordRepository,
    ) : this(
        { preferencesRepository.riskProfile.first() },
        transactionRepository,
        fundPriceRepository,
        switchWatchRepository,
        suggestionRecordRepository,
    )

    /**
     * Förvald nivå för en bevakning som startas av ett sälj ([RecordTransaction]) —
     * [SwitchPlanCalc.preselectTargetLevel] på planradens nivå, profilens förslag ([profileSuggestion])
     * och säljfondens nivå. Ur en planrad läses ingenting; utan profil inget annat än profilen.
     *
     * @param planToLevel planradens målnivå när säljet gjordes för en rad i bytesplanen, annars null.
     * @param sellLevel säljfondens egen risknivå, reserven utan profil eller utan underviktad nivå.
     * @param proceedsKr säljets likvid — räknas i portföljens total men på ingen nivå.
     * @param metadataFor innehavens metadata — cachen när ett sälj registreras.
     */
    suspend fun preselect(
        planToLevel: Int?,
        sellLevel: Int?,
        proceedsKr: Double?,
        today: LocalDate,
        metadataFor: suspend (List<String>) -> Map<String, FundMetadata>,
    ): Int? = SwitchPlanCalc.preselectTargetLevel(
        planToLevel = planToLevel,
        // Planradens nivå är svaret — profilens förslag behöver då inte räknas.
        profileSuggestion = if (planToLevel == null) profileSuggestion(proceedsKr, today, metadataFor) else null,
        sellLevel = sellLevel,
    )

    /**
     * Nivån riskprofilen saknar mest på för bevakningen [excludeWatchId], med gapet
     * ([SwitchPlanCalc.profileSuggestion]). Null utan profil (då läses inget annat) eller utan
     * underviktad nivå. Skärmen visar den bredvid nivåväljaren och härleder förvalet ur den med
     * [SwitchPlanCalc.preselectTargetLevel] (ANA-13, issue #150) — en läsning, inte två.
     */
    suspend fun profileSuggestion(
        proceedsKr: Double?,
        today: LocalDate,
        metadataFor: suspend (List<String>) -> Map<String, FundMetadata>,
        excludeWatchId: Long? = null,
    ): SwitchPlanCalc.LevelGap? {
        val allocation = riskProfile()?.effectiveAllocation.orEmpty()
        if (allocation.isEmpty()) return null
        val inputs = SwitchPlanInputs.load(
            transactionRepository, fundPriceRepository, switchWatchRepository, suggestionRecordRepository, today,
            metadataFor, excludeWatchId,
        )
        return SwitchPlanCalc.profileSuggestion(
            holdings = inputs.holdings,
            metadataByIsin = inputs.metadataByIsin,
            targetAllocation = allocation,
            proceedsKr = proceedsKr,
            pendingProceedsByLevel = inputs.pendingProceedsByLevel,
        )
    }
}
