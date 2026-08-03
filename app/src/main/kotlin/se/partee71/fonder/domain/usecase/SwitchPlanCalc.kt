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
 * ISIN-verifierat köpbara fonder (redan filtrerat och **avkastningsrangordnat** av
 * repository-lagret, se [se.partee71.fonder.data.repository.FundMetadataRepository.findSwitchCandidates]):
 * översta kvartilen på 12-månadersavkastning, därefter lägst avgift (se issue #70/#72 för
 * underlaget — riskjustering och kortare/längre lookback-fönster testades och gav ingen
 * förbättring).
 *
 * Varje byte **storleksbestäms till gapet**, inte till hela positionen ([Switch.sellValueKr]):
 * det minsta av positionens värde, underviktens underskott och överviktens överskott. Utan den
 * begränsningen kunde ett enda byte skjuta rakt förbi målet — en 10 pp avvikelse med ett stort
 * innehav på den överviktade nivån blev 50 pp åt andra hållet, och nästa byte sålde tillbaka
 * (issue #75, punkt 1). Den sekventiella omräkningen ovan skyddade bara mot att *två* byten
 * fyllde samma hink, aldrig mot att *ett* gjorde det. En delvis såld position ligger kvar med
 * sitt återstående värde och kan användas till en annan underviktad nivå.
 *
 * Fördelningen räknas mot portföljens **klassificerade** värde — innehav med känd risknivå och
 * känd kurs — precis som [PortfolioRiskCalc.actualAllocation], så planen och riskkortet ovanför
 * den beskriver samma procenttal. Ett innehav som saknar avgiftsuppgift räknas med i sin nivås
 * vikt men kan inte väljas som säljkandidat (avgiften avgör vilken position som säljs).
 *
 * Ingen plan ges (tom [Plan]) om ingen nivå avviker minst [MIN_GAP_PP], eller om ingen
 * kvalificerad köp-/säljkandidat finns för den mest avvikande nivån.
 */
object SwitchPlanCalc {

    /** En köpkandidat på [metadata]s risknivå, med källans 12-månadersavkastning ([twelveMonthReturn], t.ex. 0.083 = 8,3 %). */
    data class Candidate(val metadata: FundMetadata, val twelveMonthReturn: Double)

    /**
     * Ett enskilt föreslaget byte: sälj [sellValueKr] ur [sellFund]s position, köp [buyIsin] för
     * samma belopp. [sellValueKr] är **inte** nödvändigtvis hela positionen — den är begränsad
     * till gapet, se klassens KDoc — så beloppet måste visas för användaren; annars är rådet
     * tvetydigt ("sälj fonden" ≠ "sälj för 4 000 kr"). [feeDelta] = köpkandidatens avgift minus
     * säljfondens (positivt = dyrare).
     */
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

    /**
     * Hur gammal en inspelad plan får vara för att fortfarande visas (HEM-8). Samma princip som
     * HEM-6:s [FundMetadataFreshness.COMPARISON_TTL_DAYS] — *"ett gammalt råd är fel på ett sätt
     * gammal avgiftsdata inte är"* — men snävare: ett bytesförslag är prissatt mot portföljen
     * som den såg ut den dagen, och den rör sig varje handelsdag. Backstopen kör var 12:e timme,
     * så en plan som är äldre än så här betyder att den inte kommit fram på över en vecka
     * (inget nät, batterioptimering, appen inte öppnad) — då är "ingen plan" ärligare än ett
     * inaktuellt "Sälj X → Köp Y" (issue #75, punkt 5).
     */
    const val PLAN_TTL_DAYS = 7L

    /**
     * Ett innehav vars risknivå går att avgöra — alltså med i fördelningen. [feePercent] null
     * betyder att fonden **inte** kan väljas som säljkandidat (avgiften avgör vilken position på
     * nivån som säljs, och ingår i [Switch.feeDelta]), men värdet räknas ändå med i nivåns vikt.
     */
    private data class Position(val holding: Holding, val isin: String, val level: Int, val feePercent: Double?, val valueKr: Double)

    /**
     * Nivåerna som är underviktade med minst [MIN_GAP_PP] — alltså de enda [plan] någonsin
     * kommer att köpa på. Räknas ur redan känd data (innehav + cachad metadata), **utan
     * nätverk**, så anroparen kan hämta köpkandidater bara för dem: en källfråga plus
     * budgeterad köpbarhetsverifiering per nivå är dyrt, och en femnivåfördelning gav tidigare
     * 5 frågor och upp till 50 verifieringar var 12:e timme för nivåer planen ändå aldrig
     * skulle röra (issue #75, punkt 4).
     *
     * Att titta på **utgångsläget** räcker: ett byte storleksbestäms till min(under, över), så
     * den sålda nivån kan aldrig sjunka under sitt eget mål och därmed aldrig bli underviktad
     * av ett byte. Mängden underviktade nivåer kan alltså bara krympa under den giriga loopen,
     * aldrig växa.
     */
    fun underweightedLevels(
        holdings: List<Holding>,
        metadataByIsin: Map<String, FundMetadata>,
        targetAllocation: Map<Int, Double>,
    ): List<Int> {
        if (targetAllocation.isEmpty()) return emptyList()
        val positions = positionsOf(holdings, metadataByIsin)
        val totalValueKr = positions.sumOf { it.valueKr }
        if (totalValueKr <= 0.0) return emptyList()

        val levelValues = positions.groupBy { it.level }.mapValues { (_, p) -> p.sumOf { it.valueKr } }
        return gapsPp(targetAllocation, levelValues, totalValueKr)
            .filterValues { it >= MIN_GAP_PP }
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
    }

    /**
     * Innehav vars risknivå går att avgöra. Se [plan] för varför avgiften **inte** krävs här:
     * den behövs bara för att välja säljkandidat, inte för att räkna med värdet i nivåns vikt.
     */
    private fun positionsOf(holdings: List<Holding>, metadataByIsin: Map<String, FundMetadata>): List<Position> =
        holdings.mapNotNull { holding ->
            val isin = holding.fund.isin ?: return@mapNotNull null
            val value = holding.currentValue ?: return@mapNotNull null
            val metadata = metadataByIsin[isin] ?: return@mapNotNull null
            val level = metadata.risk ?: return@mapNotNull null
            Position(holding, isin, level, metadata.totalFee, value)
        }

    /** Avvikelse per nivå i procentenheter av [totalValueKr] — positiv = underviktad. */
    private fun gapsPp(
        targetAllocation: Map<Int, Double>,
        levelValues: Map<Int, Double>,
        totalValueKr: Double,
    ): Map<Int, Double> =
        (targetAllocation.keys + levelValues.keys).associateWith { level ->
            ((targetAllocation[level] ?: 0.0) * totalValueKr - (levelValues[level] ?: 0.0)) / totalValueKr * 100.0
        }

    fun plan(
        holdings: List<Holding>,
        metadataByIsin: Map<String, FundMetadata>,
        candidates: List<Candidate>,
        targetAllocation: Map<Int, Double>,
    ): Plan {
        if (targetAllocation.isEmpty()) return EMPTY_PLAN

        // Klassificerbart = känd risknivå och känt värde. Avgiften krävs bara för att *sälja*.
        // Tidigare föll ett innehav utan känd `totalFee` ur hela beräkningen, alltså även ur
        // nämnaren och ur sin egen nivås vikt — en perfekt balanserad 50/50-portfölj där ena
        // fonden saknade avgift såg då ut som 0/100 och fick ett byte som gjorde den 75/25
        // (issue #75). Nämnaren är portföljens klassificerade värde, samma som
        // [PortfolioRiskCalc.actualAllocation] använder, så procenttalen i planen och i
        // riskkortet ovanför beskriver samma fördelning.
        val positions = positionsOf(holdings, metadataByIsin)

        val totalValueKr = positions.sumOf { it.valueKr }
        if (totalValueKr <= 0.0) return EMPTY_PLAN

        val sellSlots = positions.filter { it.feePercent != null }.toMutableList()
        val levelValues = positions.groupBy { it.level }.mapValues { (_, p) -> p.sumOf { it.valueKr } }.toMutableMap()
        val usedBuyIsins = positions.mapTo(mutableSetOf()) { it.isin }
        val switches = mutableListOf<Switch>()

        while (switches.size < MAX_SWITCHES_PER_PLAN) {
            val gapsPp = gapsPp(targetAllocation, levelValues, totalValueKr)
            val underEntry = gapsPp.filterValues { it >= MIN_GAP_PP }.maxByOrNull { it.value } ?: break
            val overEntry = gapsPp.filter { it.key != underEntry.key && it.value <= -MIN_GAP_PP }.minByOrNull { it.value } ?: break

            // Bara positioner med känd avgift kan säljas — ligger hela överviktens värde i en
            // fond utan avgiftsuppgift ges ingen plan alls, hellre än ett byte på en gissning.
            val sellSlot = sellSlots.filter { it.level == overEntry.key }.maxByOrNull { it.feePercent!! } ?: break
            val buyCandidate = pickBuyCandidate(candidates, underEntry.key, usedBuyIsins) ?: break

            // Storleksbestäm bytet till gapet — se klassens KDoc. Båda gapen är uttryckta i
            // procentenheter av portföljen, så de räknas om till kronor mot samma total.
            val underShortfallKr = underEntry.value / 100.0 * totalValueKr
            val overExcessKr = -overEntry.value / 100.0 * totalValueKr
            val switchValueKr = minOf(sellSlot.valueKr, underShortfallKr, overExcessKr)
            if (switchValueKr <= 0.0) break

            switches += Switch(
                sellFund = sellSlot.holding.fund,
                sellValueKr = switchValueKr,
                buyIsin = buyCandidate.metadata.isin,
                buyName = buyCandidate.metadata.name,
                fromLevel = sellSlot.level,
                toLevel = underEntry.key,
                feeDelta = buyCandidate.metadata.totalFee!! - sellSlot.feePercent!!,
            )
            usedBuyIsins += buyCandidate.metadata.isin

            // Såldes hela positionen är slotten förbrukad; annars ligger resten kvar och kan
            // fylla en annan underviktad nivå i en senare iteration.
            val remainingKr = sellSlot.valueKr - switchValueKr
            if (remainingKr <= 0.0) {
                sellSlots.remove(sellSlot)
            } else {
                sellSlots[sellSlots.indexOf(sellSlot)] = sellSlot.copy(valueKr = remainingKr)
            }
            levelValues[sellSlot.level] = (levelValues[sellSlot.level] ?: 0.0) - switchValueKr
            levelValues[underEntry.key] = (levelValues[underEntry.key] ?: 0.0) + switchValueKr
        }

        if (switches.isEmpty()) return EMPTY_PLAN
        val gapClosedPp = switches.sumOf { it.sellValueKr / totalValueKr * 100.0 }
        return Plan(switches, gapClosedPp)
    }

    /**
     * Översta kvartilen på [Candidate.twelveMonthReturn], därefter lägst avgift — se klassens
     * KDoc. Regeln förutsätter att [candidates] är nivåns **avkastningsrangordnade** köpbara
     * fonder; hämtas de i avgiftsordning blir avkastningen bara en särskiljare mellan de
     * billigaste och den uppmätta kanten uteblir (issue #75, punkt 3).
     */
    private fun pickBuyCandidate(candidates: List<Candidate>, level: Int, excludeIsins: Set<String>): Candidate? {
        val atLevel = candidates.filter { it.metadata.risk == level && it.metadata.isin !in excludeIsins && it.metadata.totalFee != null }
        if (atLevel.isEmpty()) return null
        val sortedByReturn = atLevel.sortedByDescending { it.twelveMonthReturn }
        val quartileCount = ceil(sortedByReturn.size * 0.25).toInt().coerceAtLeast(1)
        return sortedByReturn.take(quartileCount).minByOrNull { it.metadata.totalFee!! }
    }
}
