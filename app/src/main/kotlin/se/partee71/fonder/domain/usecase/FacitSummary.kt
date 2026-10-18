package se.partee71.fonder.domain.usecase

import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.SuggestionKind

/**
 * En rad i facit — ett inspelat bytesförslag med fondnamnen uppslagna och utfallet räknat.
 *
 * Fondnamnen kommer ur den **cachade** metadatan; saknas raden visas ISIN:et i stället för ett
 * gissat namn (samma princip som [se.partee71.fonder.ui.transaktioner.TransaktionerViewModel] som
 * faller tillbaka på fundId).
 */
data class FacitRad(
    val recordId: Long,
    /** Vilken sorts råd raden bär (issue #91) — styr både märkningen i listan och vilken summering den räknas in i. */
    val kind: SuggestionKind,
    val planIndex: Int,
    val suggestedAtEpochDay: Long,
    val sellFundName: String,
    val buyFundName: String,
    val switchValueKr: Double?,
    val followed: Boolean,
    val outcome: SwitchOutcomeCalc.Outcome
)

/**
 * Facit räknat ur cachen (SET-5) — raderna och summeringarna, som Facit-skärmen redovisar och som
 * Beslut-flikens rad "Hur gick det?" (NAV-9) sammanfattar.
 *
 * De två sorterna summeras var för sig (issue #91) och slås aldrig ihop till ett **snitt**: ett
 * avgiftsbyte görs för en känd besparing, ett riskplansbyte för ett förväntat utfall. [byPlanIndex]
 * räknar bara planens rader.
 */
data class FacitSnapshot(
    val rows: List<FacitRad> = emptyList(),
    /** Utfallet över **alla** inspelade förslag ur bytesplanen (HEM-8) — hur bra rådet var. */
    val planAllSummary: SwitchOutcomeCalc.Summary = SwitchOutcomeCalc.Summary(),
    /** Utfallet över enbart de bytesplansförslag användaren markerat som genomförda — vad rådet faktiskt gav. */
    val planFollowedSummary: SwitchOutcomeCalc.Summary = SwitchOutcomeCalc.Summary(),
    /** Samma två mått för avgiftsbytena (ANA-9, issue #91) — redovisade **separat**. */
    val feeAllSummary: SwitchOutcomeCalc.Summary = SwitchOutcomeCalc.Summary(),
    val feeFollowedSummary: SwitchOutcomeCalc.Summary = SwitchOutcomeCalc.Summary(),
    /** Snitt per plats i planen (SET-5) — underlaget för om `MAX_SWITCHES_PER_PLAN` kan höjas. Bara bytesplanens rader. */
    val byPlanIndex: List<SwitchOutcomeCalc.PlanIndexSummary> = emptyList()
) {
    /** Förslag användaren markerat som genomförda, över hela historiken och båda sorterna. */
    val followedCount: Int get() = rows.count { it.followed }

    /**
     * Meravkastningen i kronor för de **genomförda** förslagen, båda sorterna — summan av de två
     * kronbelopp Facit visar på raderna "Genomförda" (bytesplanen respektive avgiftsbytena), alltså
     * det totala resultatet av följda råd mot att ha behållit innehavet, inte skillnaden mot de
     * oföljda. Kronor går att lägga ihop även när procentsnitten inte gör det: de är redan vägda
     * med beloppet, och båda sorterna mäts mot samma nollpunkt (innehavet behållet). Null när
     * ingen genomförd rad har ett mätbart utfall med känt belopp — aldrig 0 (ANA-4-principen).
     */
    val followedExcessKr: Double?
        get() = listOfNotNull(planFollowedSummary.totalExcessKr, feeFollowedSummary.totalExcessKr)
            .takeIf { it.isNotEmpty() }
            ?.sum()
}

/**
 * Läser [FacitSnapshot] ur repositorierna — **bara cachen**, aldrig nätverket (SET-5). NAV kommer
 * ur den lokala kurscachen (bevakade fonder fylls av `FundPriceUpdateWorker.refreshAll`,
 * köpkandidaterna av dess `scanOutcomeNavs`) och namnen ur [FundMetadataRepository.cachedMetadataFor].
 * Den enda platsen facit räknas — Facit-skärmen och Beslut-flikens rad läser båda härifrån.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FacitSummary @Inject constructor(
    private val suggestionRecordRepository: SuggestionRecordRepository,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val fundMetadataRepository: FundMetadataRepository
) {

    fun observe(): Flow<FacitSnapshot> = combine(suggestionRecordRepository.observeHistory(), transactionRepository.observeFunds()) { history, funds ->
        history to funds
    }.flatMapLatest { (history, funds) ->
        val isins = (history.map { it.sellIsin } + history.map { it.buyIsin }).distinct()
        // Säljsidan är en bevakad fond, vars kurser ligger under fondens egen fundId.
        // Köpsidan ägs inte av appen och cachas under ISIN:et självt — så gör
        // `FundPriceRepository.findFundByIsin`, som är vägen workern hämtar den på.
        val fundIdByIsin = funds.mapNotNull { fund -> fund.isin?.let { it to fund.fundId } }.toMap()
        val priceKeys = (funds.map { it.fundId } + isins).distinct()

        fundPriceRepository.observeLatestPrices(priceKeys).map { prices ->
            val metadataByIsin = fundMetadataRepository.cachedMetadataFor(isins)
            val navByIsin = isins.mapNotNull { isin ->
                val nav = prices[fundIdByIsin[isin] ?: isin]?.nav ?: prices[isin]?.nav
                nav?.let { isin to it }
            }.toMap()

            val rows = history.map { record ->
                FacitRad(
                    recordId = record.id,
                    kind = record.kind,
                    planIndex = record.planIndex,
                    suggestedAtEpochDay = record.suggestedAtEpochDay,
                    sellFundName = metadataByIsin[record.sellIsin]?.name ?: record.sellIsin,
                    buyFundName = metadataByIsin[record.buyIsin]?.name ?: record.buyIsin,
                    switchValueKr = record.switchValueKr,
                    followed = record.followed == true,
                    outcome = SwitchOutcomeCalc.evaluate(
                        record = record,
                        sellNavNow = navByIsin[record.sellIsin],
                        buyNavNow = navByIsin[record.buyIsin]
                    )
                )
            }
            summarize(rows)
        }
    }

    companion object {
        /**
         * Summerar [rows] per sort och urval. `byPlanIndex` räknar bara planens rader — den finns
         * för att avgöra om MAX_SWITCHES_PER_PLAN kan höjas, och en avgiftsrad har ingen
         * meningsfull plats i planen.
         */
        fun summarize(rows: List<FacitRad>): FacitSnapshot {
            val planRows = rows.filter { it.kind == SuggestionKind.RISK_PLAN }
            val feeRows = rows.filter { it.kind == SuggestionKind.FEE }
            return FacitSnapshot(
                rows = rows,
                planAllSummary = SwitchOutcomeCalc.summarize(planRows.map { it.outcome }),
                planFollowedSummary = SwitchOutcomeCalc.summarize(planRows.filter { it.followed }.map { it.outcome }),
                feeAllSummary = SwitchOutcomeCalc.summarize(feeRows.map { it.outcome }),
                feeFollowedSummary = SwitchOutcomeCalc.summarize(feeRows.filter { it.followed }.map { it.outcome }),
                byPlanIndex = SwitchOutcomeCalc.byPlanIndex(planRows.map { it.outcome })
            )
        }
    }
}
