package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import java.time.LocalDate
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
 * Ingen plan ges (tom lista) om ingen nivå avviker minst [MIN_GAP_PP], eller om ingen
 * kvalificerad köp-/säljkandidat finns för den mest avvikande nivån.
 *
 * **Avstådda par** (HEM-14): ett avstått byte Sälj X → Köp Y tas ur kandidatmängden innan den
 * giriga loopen, så planen numreras 1..n utan luckor i stället för att ett avstått steg lämnar
 * ett hål som nästa byte räknats fram under antagandet att det gjorts.
 *
 * **Likvid som väntar på köp** (BYT-3, issue #144): ett sälj som gjorts men vars pengar ännu inte
 * köpt nästa fond räknas som värde på bevakningens målnivå ([pendingProceedsByLevel]) — i både
 * nämnaren och nivåns vikt — så ett gap som redan är finansierat inte fyller ett nytt sälj.
 * Likviden kan inte säljas: den är inte en position.
 */
object SwitchPlanCalc {

    /** En köpkandidat på [metadata]s risknivå, med källans 12-månadersavkastning ([twelveMonthReturn], t.ex. 0.083 = 8,3 %). */
    data class Candidate(val metadata: FundMetadata, val twelveMonthReturn: Double)

    /**
     * Utfallet av en kandidatsökning på en nivå (issue #149) — [candidates] är de verifierat
     * köpbara, [lookupFailed] sant när minst en prövad kandidats köpbarhet inte gick att avgöra
     * (katalogen kunde inte hämtas). Skiljer en tom lista som betyder "inga köpbara" från en
     * som betyder "vi vet inte" — två olika besked till användaren.
     */
    data class CandidateSearch(val candidates: List<Candidate>, val lookupFailed: Boolean)

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
        pendingProceedsByLevel: Map<Int, Double> = emptyMap(),
    ): List<Int> {
        if (targetAllocation.isEmpty()) return emptyList()
        val positions = positionsOf(holdings, metadataByIsin)
        val levelValues = levelValuesOf(positions, pendingProceedsByLevel)
        val totalValueKr = levelValues.values.sum()
        if (totalValueKr <= 0.0) return emptyList()

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

    /** Nivåernas värde ur innehaven plus likviden som väntar på köp (BYT-3). */
    private fun levelValuesOf(positions: List<Position>, pendingProceedsByLevel: Map<Int, Double>): MutableMap<Int, Double> {
        val values = positions.groupBy { it.level }.mapValuesTo(mutableMapOf()) { (_, p) -> p.sumOf { it.valueKr } }
        pendingProceedsByLevel.forEach { (level, kr) -> if (kr > 0.0) values[level] = (values[level] ?: 0.0) + kr }
        return values
    }

    /**
     * Likvid som väntar på köp, per målnivå (BYT-3, issue #144): för varje öppen bevakning med
     * känt belopp, känd målnivå och en säljtransaktion som finns — inte utgången — beloppet minus
     * de köp som redan gjorts för det. Ett köp **sedan säljdagen** i en bevakad kandidat, i
     * källförslagets köpfond eller i en fond på målnivån förbrukar likviden (köpets belopp
     * inklusive avgift).
     *
     * Ett köp förbrukas **högst en gång totalt**: bevakningarna gås igenom äldst först (säljdag,
     * sedan id), köpen i tidsordning, och ett köp som tagits av en bevakning är borta för nästa —
     * även när två bevakningar har samma målnivå. Ett köp större än det som återstår förbrukas
     * helt; överskottet förs inte vidare. Likviden kan alltså bara räknas **för lågt**, aldrig
     * dubbelt: en underräkning ger på sin höjd ett förslag för ett gap som redan är fyllt, en
     * dubbelräkning hade dolt ett gap som finns.
     *
     * Ett köp som redan **avslutat** en bevakning (dess `buyTransactionId`, öppen eller stängd)
     * är det bytets pengar och förbrukar aldrig en annan bevaknings likvid — annars räknades den
     * öppna bevakningen för lågt och planen föreslog ett nytt sälj för ett gap som är fyllt.
     *
     * Bevakningar utan känd säljtransaktion ("Bevaka alternativ" ur ett förslag) räknas inte —
     * utan transaktion är pengarna inte bevisligen fria. Fonder utan ISIN kan inte matchas.
     *
     * @param watches **alla** bevakningar, även stängda — de stängdas köptransaktioner undantas
     *   från förbrukningen; bara öppna, inte utgångna räknas som likvid.
     * @param buyIsinByRecordId källförslagens köpfond per rad-id, se [SwitchTransitions.suggestFor].
     * @param metadataByIsin risknivå per ISIN för "fond på målnivån" — de innehav som finns;
     *   en köpt fond som saknas här matchas bara som kandidat eller föreslagen fond.
     */
    fun pendingProceedsByLevel(
        watches: List<SwitchWatch>,
        transactions: List<Transaction>,
        funds: List<Fund>,
        buyIsinByRecordId: Map<Long, String>,
        metadataByIsin: Map<String, FundMetadata>,
        today: LocalDate,
    ): Map<Int, Double> {
        val transactionIds = transactions.mapTo(mutableSetOf()) { it.id }
        val isinByFundId = funds.associate { it.fundId to it.isin }
        val purchases = transactions
            .filter { it.type == TransactionType.KOP }
            .sortedWith(compareBy({ it.epochDay }, { it.id }))
        val consumed = watches.mapNotNullTo(mutableSetOf()) { it.buyTransactionId }
        val result = mutableMapOf<Int, Double>()

        watches
            .filter { it.isOpen && !SwitchWatchCalc.isExpired(it, today) }
            .filter { it.sellTransactionId != null && it.sellTransactionId in transactionIds }
            .sortedWith(compareBy({ it.soldAtEpochDay }, { it.id }))
            .forEach { watch ->
                val level = watch.targetLevel ?: return@forEach
                var remaining = watch.proceedsKr ?: return@forEach
                if (remaining <= 0.0) return@forEach
                val expectedIsins = watch.candidates.mapTo(mutableSetOf()) { it.isin }
                watch.sourceRecordId?.let { buyIsinByRecordId[it] }?.let { expectedIsins += it }

                for (purchase in purchases) {
                    if (remaining <= 0.0) break
                    if (purchase.id in consumed || purchase.epochDay < watch.soldAtEpochDay) continue
                    val isin = isinByFundId[purchase.fundId] ?: continue
                    if (isin !in expectedIsins && metadataByIsin[isin]?.risk != level) continue
                    consumed += purchase.id
                    remaining -= purchase.amount + purchase.fee
                }
                if (remaining > 0.0) result[level] = (result[level] ?: 0.0) + remaining
            }
        return result
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

    /**
     * Bytena rangordnade så att bara det första ensamt är en giltig handling — se klassens KDoc.
     *
     * @param excludedPairs avstådda byten som (säljISIN, köpISIN) (HEM-14) — tas ur
     *   kandidatmängden innan loopen, så listan blir 1..n utan luckor.
     * @param pendingProceedsByLevel likvid som väntar på köp per målnivå (BYT-3,
     *   [pendingProceedsByLevel]) — räknas som värde på nivån men kan aldrig säljas.
     */
    fun plan(
        holdings: List<Holding>,
        metadataByIsin: Map<String, FundMetadata>,
        candidates: List<Candidate>,
        targetAllocation: Map<Int, Double>,
        excludedPairs: Set<Pair<String, String>> = emptySet(),
        pendingProceedsByLevel: Map<Int, Double> = emptyMap(),
    ): List<Switch> {
        if (targetAllocation.isEmpty()) return emptyList()

        // Klassificerbart = känd risknivå och känt värde. Avgiften krävs bara för att *sälja*.
        // Tidigare föll ett innehav utan känd `totalFee` ur hela beräkningen, alltså även ur
        // nämnaren och ur sin egen nivås vikt — en perfekt balanserad 50/50-portfölj där ena
        // fonden saknade avgift såg då ut som 0/100 och fick ett byte som gjorde den 75/25
        // (issue #75). Nämnaren är portföljens klassificerade värde, samma som
        // [PortfolioRiskCalc.actualAllocation] använder, så procenttalen i planen och i
        // riskkortet ovanför beskriver samma fördelning.
        val positions = positionsOf(holdings, metadataByIsin)
        // Likviden på väg (BYT-3) väger på sin målnivå och i nämnaren, men ligger inte i sellSlots.
        val levelValues = levelValuesOf(positions, pendingProceedsByLevel)
        val totalValueKr = levelValues.values.sum()
        if (totalValueKr <= 0.0) return emptyList()

        val sellSlots = positions.filter { it.feePercent != null }.toMutableList()
        val usedBuyIsins = positions.mapTo(mutableSetOf()) { it.isin }
        val switches = mutableListOf<Switch>()

        while (switches.size < MAX_SWITCHES_PER_PLAN) {
            val gapsPp = gapsPp(targetAllocation, levelValues, totalValueKr)
            val underEntry = gapsPp.filterValues { it >= MIN_GAP_PP }.maxByOrNull { it.value } ?: break
            val overEntry = gapsPp.filter { it.key != underEntry.key && it.value <= -MIN_GAP_PP }.minByOrNull { it.value } ?: break

            // Bara positioner med känd avgift kan säljas — ligger hela överviktens värde i en
            // fond utan avgiftsuppgift ges ingen plan alls, hellre än ett byte på en gissning.
            // Dyrast först; ett avstått par (HEM-14) är inte ett alternativ för just den säljfonden,
            // så saknar den dyraste en kandidat prövas nästa position på nivån — planen numreras om
            // 1..n i stället för att tömmas av ett avstående.
            val match = sellSlots
                .filter { it.level == overEntry.key }
                .sortedByDescending { it.feePercent!! }
                .firstNotNullOfOrNull { slot ->
                    val excludedForSell = excludedPairs.filter { it.first == slot.isin }.mapTo(mutableSetOf()) { it.second }
                    pickBuyCandidate(candidates, underEntry.key, usedBuyIsins + excludedForSell)?.let { slot to it }
                } ?: break
            val (sellSlot, buyCandidate) = match

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

        return switches
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
