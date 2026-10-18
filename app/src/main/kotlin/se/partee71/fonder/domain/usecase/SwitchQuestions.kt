package se.partee71.fonder.domain.usecase

import javax.inject.Inject
import kotlinx.coroutines.flow.first
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.TransactionType

/**
 * Ett alternativ i bytesväljaren så som formuläret (NAV-4) och PDF-importen (IMP-6) visar det
 * (BYT-2, issue #144): ett svar som [RecordTransaction] skriver direkt ([Decide]), eller frågan om
 * säljet hör till en okopplad bevakning, samma eller en annan dag ([LinkOtherDay]) — den kopplas bara efter ett
 * uttryckligt ja, i samma skrivning som säljet ([SwitchDecision.LinksWatch]).
 */
sealed interface SwitchOption {

    /** Beslutet [RecordTransaction] skriver. */
    val decision: SwitchDecision

    data class Decide(override val decision: SwitchDecision) : SwitchOption

    /** "Säljet hör till bytet du bevakar" (okopplad bevakning, samma eller annan dag) — skrivs som [SwitchDecision.LinksWatch]. */
    data class LinkOtherDay(val watchId: Long) : SwitchOption {
        override val decision: SwitchDecision get() = SwitchDecision.LinksWatch(watchId)
    }
}

/**
 * Bytesväljaren för en transaktion: [options] i visningsordning och förvalet, null när frågan
 * måste besvaras ([required]) innan transaktionen sparas.
 */
data class SwitchQuestion(val options: List<SwitchOption>, val preselected: SwitchOption?) {

    val required: Boolean get() = preselected == null

    /** Bara "Inget byte" — väljaren visas inte. */
    val isTrivial: Boolean get() = options.size <= 1

    /** Användarens val om det fortfarande finns bland alternativen, annars förvalet (null = obesvarad). */
    fun resolve(chosen: SwitchOption?): SwitchOption? = chosen?.takeIf { it in options } ?: preselected

    companion object {
        /** Ingenting att fråga om — "Inget byte" förvalt. */
        val NONE = SwitchQuestion(listOf(SwitchOption.Decide(SwitchDecision.None)), SwitchOption.Decide(SwitchDecision.None))
    }
}

/**
 * Förslaget som **Följ** öppnade formuläret för (BYT-2) — det säljet registreras för. [planIndex]
 * null för ett billigare alternativ (ANA-9). [inProgress] sant när raden redan är såld (BYT-1);
 * den erbjuds då inte igen.
 */
data class FollowedSuggestion(
    val recordId: Long,
    val sellIsin: String,
    val buyIsin: String,
    val sellFundName: String,
    val buyFundName: String,
    val toLevel: Int,
    val switchValueKr: Double?,
    val planIndex: Int?,
    val inProgress: Boolean = false
) {
    val decision: SwitchDecision.ForSuggestion get() = SwitchDecision.ForSuggestion(recordId, toLevel)

    companion object {
        fun of(row: SwitchPlanResolver.Suggestion) = FollowedSuggestion(
            recordId = row.recordId,
            sellIsin = row.sellIsin,
            buyIsin = row.buyIsin,
            sellFundName = row.sellFundName,
            buyFundName = row.buyFundName,
            toLevel = row.toLevel,
            switchValueKr = row.switchValueKr,
            planIndex = row.planIndex.takeIf { row.kind == SuggestionKind.RISK_PLAN },
            inProgress = row.inProgress
        )
    }
}

/**
 * Slår upp förslaget Följ pekar ut **på radens id** — reserven när raden inte står i Besluts källa
 * ([SwitchChoicesSource.Inputs]): Fonddetaljs avgiftsrader filtreras inte som Besluts billigare
 * alternativ, och Följ därifrån får aldrig tappa förslaget tyst. Målnivån och köpfondens namn ur
 * metadatacachen (som [SwitchPlanResolver]); saknas nivån finns inget förslag att starta
 * bevakningen ur, och null ges hellre än en gissad nivå (ANA-4-principen). Bara cachen.
 */
class FollowedSuggestionLookup @Inject constructor(
    private val suggestionRecordRepository: SuggestionRecordRepository,
    private val fundMetadataRepository: FundMetadataRepository
) {
    suspend fun lookup(recordId: Long): FollowedSuggestion? {
        val record = suggestionRecordRepository.observeByIds(setOf(recordId)).first().firstOrNull() ?: return null
        val metadata = fundMetadataRepository.cachedMetadataFor(listOf(record.sellIsin, record.buyIsin))
        val buy = metadata[record.buyIsin]
        val toLevel = buy?.risk ?: return null
        return FollowedSuggestion(
            recordId = record.id,
            sellIsin = record.sellIsin,
            buyIsin = record.buyIsin,
            sellFundName = metadata[record.sellIsin]?.name ?: record.sellIsin,
            buyFundName = buy.name,
            toLevel = toLevel,
            switchValueKr = record.switchValueKr,
            planIndex = record.planIndex.takeIf { record.kind == SuggestionKind.RISK_PLAN }
        )
    }
}

/** En rad i PDF-importen, sedd av bytesväljaren — i importordningen (sälj före köp). */
data class SwitchImportRow(val type: TransactionType, val fundIsin: String?, val epochDay: Long)

/**
 * Bytesväljarens frågor för formuläret och PDF-importen (BYT-2, issue #144), byggda på
 * [SwitchTransitions]s förval. Här bor bara det som [SwitchTransitions] inte vet om:
 *
 * - **Följ** öppnar formuläret för ett visst förslag — det förslaget är förvalt, även ett
 *   billigare alternativ (ANA-9) som planens förval inte känner till; en pågående planrad
 *   (BYT-1) erbjuds aldrig igen.
 * - **"Köpte den här"** öppnar köpformuläret för en viss bevakning — den är förvald när köpet kan
 *   avsluta den.
 * - En okopplad bevakning av säljfonden, samma eller en annan dag ([SwitchTransitions.linkableWatch]),
 *   blir ett eget alternativ, och frågan är då alltid obligatorisk: kopplingen görs aldrig utan svar.
 *   I importen kopplas en bevakning av högst **en** säljrad — en rad erbjuds inte den bevakning en
 *   tidigare rad redan svarat ja på.
 * - I importen kan ett köp avsluta den bevakning en **tidigare** säljrad startar
 *   ([SwitchDecision.ClosesWatchOfEntry]); den räknas som en bevakning bland de andra när förvalet
 *   avgörs — en enda, och köpet är förslagets köpfond, ger förval; annars frågas det.
 */
object SwitchQuestions {

    fun forTransaction(
        inputs: SwitchChoicesSource.Inputs,
        type: TransactionType,
        fundIsin: String?,
        epochDay: Long,
        followed: FollowedSuggestion? = null,
        closesWatchId: Long? = null,
        /** Bevakningar en tidigare rad i samma import redan kopplar — erbjuds inte igen. */
        linkedWatchIds: Set<Long> = emptySet()
    ): SwitchQuestion {
        val choices = inputs.suggestFor(type, fundIsin, epochDay)
        val options = choices.options.mapTo(mutableListOf<SwitchOption>()) { SwitchOption.Decide(it) }
        var preselected: SwitchOption? = choices.preselected?.let { SwitchOption.Decide(it) }

        if (type == TransactionType.SALJ && followed != null && !followed.inProgress && followed.sellIsin == fundIsin) {
            val option = SwitchOption.Decide(followed.decision)
            if (option !in options) options += option
            preselected = option
        }
        if (type == TransactionType.KOP && closesWatchId != null) {
            val closes = SwitchOption.Decide(SwitchDecision.ClosesWatch(closesWatchId))
            if (closes in options) preselected = closes
        }
        if (type == TransactionType.SALJ) {
            inputs.linkableWatch(fundIsin, epochDay, exclude = linkedWatchIds)?.let { watch ->
                options += SwitchOption.LinkOtherDay(watch.id)
                preselected = null
            }
        }
        return SwitchQuestion(options, preselected)
    }

    /**
     * Frågorna för importens rader, i samma ordning. [chosen] är användarens val per rad (null =
     * inget val gjort) — en köprads alternativ beror på vad säljraderna före den svarat.
     */
    fun forImport(
        inputs: SwitchChoicesSource.Inputs,
        rows: List<SwitchImportRow>,
        chosen: List<SwitchOption?>
    ): List<SwitchQuestion> {
        val questions = mutableListOf<SwitchQuestion>()
        rows.forEachIndexed { index, row ->
            questions += when (row.type) {
                TransactionType.SALJ -> forTransaction(
                    inputs,
                    row.type,
                    row.fundIsin,
                    row.epochDay,
                    linkedWatchIds = linkedByEarlierRows(questions, chosen)
                )

                TransactionType.KOP -> forImportPurchase(inputs, rows, questions, chosen, index)
            }
        }
        return questions
    }

    /** Bevakningarna raderna före den aktuella redan svarat ja på — en bevakning kopplas av högst en rad. */
    private fun linkedByEarlierRows(earlier: List<SwitchQuestion>, chosen: List<SwitchOption?>): Set<Long> = earlier.indices.mapNotNullTo(mutableSetOf()) { index ->
        (earlier[index].resolve(chosen.getOrNull(index)) as? SwitchOption.LinkOtherDay)?.watchId
    }

    private fun forImportPurchase(
        inputs: SwitchChoicesSource.Inputs,
        rows: List<SwitchImportRow>,
        earlier: List<SwitchQuestion>,
        chosen: List<SwitchOption?>,
        index: Int
    ): SwitchQuestion {
        val row = rows[index]
        val base = forTransaction(inputs, row.type, row.fundIsin, row.epochDay)
        if (row.fundIsin == null) return base

        // Säljrader före köpet som startar en bevakning, sålda senast köpdagen — samma regel som
        // för en befintlig bevakning: pengarna måste ha funnits.
        val started = earlier.indices.mapNotNull { saleIndex ->
            val sale = rows[saleIndex]
            if (sale.type != TransactionType.SALJ || sale.fundIsin == null || sale.epochDay > row.epochDay) return@mapNotNull null
            when (val decision = earlier[saleIndex].resolve(chosen.getOrNull(saleIndex))?.decision) {
                SwitchDecision.Standalone -> saleIndex to null
                is SwitchDecision.ForSuggestion -> saleIndex to buyIsinOf(inputs, decision.recordId)
                else -> null
            }
        }
        if (started.isEmpty()) return base

        val options = base.options + started.map { (saleIndex, _) -> SwitchOption.Decide(SwitchDecision.ClosesWatchOfEntry(saleIndex)) }
        val existing = base.options.size - 1
        val preselected = when {
            existing == 0 && started.size == 1 && started.single().second == row.fundIsin ->
                SwitchOption.Decide(SwitchDecision.ClosesWatchOfEntry(started.single().first))

            else -> null
        }
        return SwitchQuestion(options, preselected)
    }

    /**
     * Förslaget [recordId] som Följ pekar ut: ur Besluts källa när raden står där (då är dess
     * pågående-märkning känd, BYT-1), annars [lookedUp] ur [FollowedSuggestionLookup].
     */
    fun followed(inputs: SwitchChoicesSource.Inputs, recordId: Long, lookedUp: FollowedSuggestion?): FollowedSuggestion? = (inputs.plan + inputs.cheaperAlternatives).firstOrNull { it.recordId == recordId }?.let { FollowedSuggestion.of(it) }
        ?: lookedUp?.takeIf { it.recordId == recordId }

    private fun buyIsinOf(inputs: SwitchChoicesSource.Inputs, recordId: Long): String? = (inputs.plan + inputs.cheaperAlternatives).firstOrNull { it.recordId == recordId }?.buyIsin
        ?: inputs.buyIsinByRecordId[recordId]
}
