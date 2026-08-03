package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.Holding
import kotlin.math.ceil

/**
 * Rangordnad bytesplan mot riskprofilens målfördelning (SET-3/#71) — HEM-8, issue #70. Ren,
 * testbar domänlogik: nätverksanrop, källfrågor och budgeterad köpbarhetsverifiering sköts av
 * [se.partee71.fonder.data.repository.FundMetadataRepository.findSwitchCandidates].
 *
 * Beräkningen är **girig och sekventiell**: föreslå bästa bytet, simulera att det genomförts,
 * räkna om gapet, föreslå nästa — annars kan två byten fylla samma hink och tillsammans skjuta
 * över målet. Säljkandidaten tas ur den mest **överviktade** nivån (högst avgift bland flera
 * innehav på samma nivå), köpkandidaten ur den mest **underviktade** — bland
 * ISIN-verifierat köpbara fonder (redan filtrerat av repository-lagret): översta kvartilen på
 * 12-månadersavkastning, därefter lägst avgift (se issue #70/#72 för underlaget — riskjustering
 * och kortare/längre lookback-fönster testades och gav ingen förbättring).
 *
 * Ingen plan ges (tom [Plan]) om ingen nivå avviker minst [MIN_GAP_PP], eller om ingen
 * kvalificerad köp-/säljkandidat finns för den mest avvikande nivån.
 */
object SwitchPlanCalc {

    /** En köpkandidat på [metadata]s risknivå, med källans 12-månadersavkastning ([twelveMonthReturn], t.ex. 0.083 = 8,3 %). */
    data class Candidate(val metadata: FundMetadata, val twelveMonthReturn: Double)

    /** Ett enskilt föreslaget byte: sälj hela [sellFund]s position, köp [buyIsin] för samma belopp. [feeDelta] = köpkandidatens avgift minus säljfondens (positivt = dyrare). */
    data class Switch(
        val sellFund: Fund,
        val sellValueKr: Double,
        val buyIsin: String,
        val buyName: String,
        val fromLevel: Int,
        val toLevel: Int,
        val feeDelta: Double,
    )

    /** [switches] rangordnade så att bara det första ensamt är en giltig handling. [gapClosedPp] är hur stor andel av portföljen (i procentenheter) planens byten omfattar. */
    data class Plan(val switches: List<Switch>, val gapClosedPp: Double)

    private val EMPTY_PLAN = Plan(emptyList(), 0.0)

    /**
     * Under den här avvikelsen (procentenheter av portföljens totala värde) ges inget förslag
     * alls — signalen är inte precis nog för finjustering av en portfölj som redan är
     * någorlunda i linje med målet.
     */
    const val MIN_GAP_PP = 5.0

    /**
     * Tak på antal byten per plan — inte av matematiska skäl utan beteendemässiga: byten är
     * gratis i ISK skattemässigt, inte beteendemässigt (Barber & Odean 2000: den mest aktiva
     * femtedelen småsparare underpresterade ~6,5 pp/år). En rangordnad lista där man kan
     * stanna efter det första bytet är medvetet vald framför en stor omplacering.
     */
    const val MAX_SWITCHES_PER_PLAN = 3

    private data class SellSlot(val holding: Holding, val isin: String, val level: Int, val feePercent: Double, val valueKr: Double)

    fun plan(
        holdings: List<Holding>,
        metadataByIsin: Map<String, FundMetadata>,
        candidates: List<Candidate>,
        targetAllocation: Map<Int, Double>,
    ): Plan {
        if (targetAllocation.isEmpty()) return EMPTY_PLAN

        val sellSlots = holdings.mapNotNull { holding ->
            val isin = holding.fund.isin ?: return@mapNotNull null
            val value = holding.currentValue ?: return@mapNotNull null
            val metadata = metadataByIsin[isin] ?: return@mapNotNull null
            val level = metadata.risk ?: return@mapNotNull null
            val fee = metadata.totalFee ?: return@mapNotNull null
            SellSlot(holding, isin, level, fee, value)
        }.toMutableList()

        val totalValueKr = sellSlots.sumOf { it.valueKr }
        if (totalValueKr <= 0.0) return EMPTY_PLAN

        val levelValues = sellSlots.groupBy { it.level }.mapValues { (_, s) -> s.sumOf { it.valueKr } }.toMutableMap()
        val usedBuyIsins = sellSlots.mapTo(mutableSetOf()) { it.isin }
        val switches = mutableListOf<Switch>()

        while (switches.size < MAX_SWITCHES_PER_PLAN) {
            val gapsPp = (targetAllocation.keys + levelValues.keys).associateWith { level ->
                ((targetAllocation[level] ?: 0.0) * totalValueKr - (levelValues[level] ?: 0.0)) / totalValueKr * 100.0
            }
            val underEntry = gapsPp.filterValues { it >= MIN_GAP_PP }.maxByOrNull { it.value } ?: break
            val overEntry = gapsPp.filter { it.key != underEntry.key && it.value <= -MIN_GAP_PP }.minByOrNull { it.value } ?: break

            val sellSlot = sellSlots.filter { it.level == overEntry.key }.maxByOrNull { it.feePercent } ?: break
            val buyCandidate = pickBuyCandidate(candidates, underEntry.key, usedBuyIsins) ?: break

            val switchValueKr = sellSlot.valueKr
            switches += Switch(
                sellFund = sellSlot.holding.fund,
                sellValueKr = switchValueKr,
                buyIsin = buyCandidate.metadata.isin,
                buyName = buyCandidate.metadata.name,
                fromLevel = sellSlot.level,
                toLevel = underEntry.key,
                feeDelta = buyCandidate.metadata.totalFee!! - sellSlot.feePercent,
            )
            usedBuyIsins += buyCandidate.metadata.isin
            sellSlots.remove(sellSlot)
            levelValues[sellSlot.level] = (levelValues[sellSlot.level] ?: 0.0) - switchValueKr
            levelValues[underEntry.key] = (levelValues[underEntry.key] ?: 0.0) + switchValueKr
        }

        if (switches.isEmpty()) return EMPTY_PLAN
        val gapClosedPp = switches.sumOf { it.sellValueKr / totalValueKr * 100.0 }
        return Plan(switches, gapClosedPp)
    }

    /** Översta kvartilen på [Candidate.twelveMonthReturn], därefter lägst avgift — se klassens KDoc. */
    private fun pickBuyCandidate(candidates: List<Candidate>, level: Int, excludeIsins: Set<String>): Candidate? {
        val atLevel = candidates.filter { it.metadata.risk == level && it.metadata.isin !in excludeIsins && it.metadata.totalFee != null }
        if (atLevel.isEmpty()) return null
        val sortedByReturn = atLevel.sortedByDescending { it.twelveMonthReturn }
        val quartileCount = ceil(sortedByReturn.size * 0.25).toInt().coerceAtLeast(1)
        return sortedByReturn.take(quartileCount).minByOrNull { it.metadata.totalFee!! }
    }
}
