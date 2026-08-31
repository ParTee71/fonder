package se.partee71.fonder.ui.facit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.usecase.SwitchOutcomeCalc
import se.partee71.fonder.worker.BackgroundWork
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import javax.inject.Inject

/**
 * En rad i facit — ett inspelat bytesförslag med fondnamnen uppslagna och utfallet räknat.
 *
 * Fondnamnen kommer ur den **cachade** metadatan; saknas raden visas ISIN:et i stället för ett
 * gissat namn (samma princip som [se.partee71.fonder.ui.transaktioner.SoldFundsViewModel] som
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
    val outcome: SwitchOutcomeCalc.Outcome,
)

data class FacitUiState(
    val loading: Boolean = true,
    val rows: List<FacitRad> = emptyList(),
    /** Utfallet över **alla** inspelade förslag ur bytesplanen (HEM-8) — hur bra rådet var. */
    val planAllSummary: SwitchOutcomeCalc.Summary = SwitchOutcomeCalc.Summary(),
    /** Utfallet över enbart de bytesplansförslag användaren markerat som genomförda — vad rådet faktiskt gav. */
    val planFollowedSummary: SwitchOutcomeCalc.Summary = SwitchOutcomeCalc.Summary(),
    /** Samma två mått för avgiftsbytena (ANA-9, issue #91) — redovisade **separat**, se klassens KDoc. */
    val feeAllSummary: SwitchOutcomeCalc.Summary = SwitchOutcomeCalc.Summary(),
    val feeFollowedSummary: SwitchOutcomeCalc.Summary = SwitchOutcomeCalc.Summary(),
    /** Snitt per plats i planen (SET-5) — underlaget för om `MAX_SWITCHES_PER_PLAN` kan höjas. Bara bytesplanens rader. */
    val byPlanIndex: List<SwitchOutcomeCalc.PlanIndexSummary> = emptyList(),
    /**
     * Bytesplanen skannas just nu ([BackgroundWork.SWITCH_PLAN_SCAN]) — det är den körningen som
     * spelar in nya rader och fyller på utfallens NAV, alltså precis den data kortet redovisar.
     */
    val switchPlanWorking: Boolean = false,
) {
    val isEmpty: Boolean get() = !loading && rows.isEmpty()

    /** Summeringskortets väntesnurra (NAV-6) — härlett här, så kopplingen är enhetstestbar. */
    val summaryWorking: Boolean get() = loading || switchPlanWorking
}

/**
 * Facit för bytesplanen (SET-5, issue #80) — redovisar utfallet av HEM-8:s inspelade förslag
 * ([SuggestionRecord]) mot att ha behållit innehavet, se [SwitchOutcomeCalc].
 *
 * **Läser bara cachen.** Varken kurser eller fondnamn hämtas här: NAV kommer ur den lokala
 * kurscachen (bevakade fonder fylls av `FundPriceUpdateWorker.refreshAll`, köpkandidaterna av
 * dess `scanOutcomeNavs`) och namnen ur [FundMetadataRepository.cachedMetadataFor]. Att öppna
 * en redovisningsvy ska aldrig kosta en burst av nätverksanrop — samma avgränsning som HEM-5
 * och POR-9 redan gör.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FacitViewModel @Inject constructor(
    private val suggestionRecordRepository: SuggestionRecordRepository,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val fundPriceRefreshScheduler: FundPriceRefreshScheduler,
) : ViewModel() {

    private val historyAndFunds: Flow<Pair<List<SuggestionRecord>, List<Fund>>> =
        combine(suggestionRecordRepository.observeHistory(), transactionRepository.observeFunds()) { history, funds ->
            history to funds
        }

    val uiState: StateFlow<FacitUiState> =
        historyAndFunds.flatMapLatest { (history, funds) ->
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
                            buyNavNow = navByIsin[record.buyIsin],
                        ),
                    )
                }

                // De två sorterna summeras var för sig (issue #91) och slås aldrig ihop till ett
                // snitt: ett avgiftsbyte görs för en **känd** besparing, ett riskplansbyte för ett
                // förväntat utfall. Ett gemensamt tal hade dolt att den ena sortens råd är
                // säkrare än den andra. `byPlanIndex` räknar bara planens rader — den finns för
                // att avgöra om MAX_SWITCHES_PER_PLAN kan höjas, och en avgiftsrad har ingen
                // meningsfull plats i planen.
                val planRows = rows.filter { it.kind == SuggestionKind.RISK_PLAN }
                val feeRows = rows.filter { it.kind == SuggestionKind.FEE }
                FacitUiState(
                    loading = false,
                    rows = rows,
                    planAllSummary = SwitchOutcomeCalc.summarize(planRows.map { it.outcome }),
                    planFollowedSummary = SwitchOutcomeCalc.summarize(planRows.filter { it.followed }.map { it.outcome }),
                    feeAllSummary = SwitchOutcomeCalc.summarize(feeRows.map { it.outcome }),
                    feeFollowedSummary = SwitchOutcomeCalc.summarize(feeRows.filter { it.followed }.map { it.outcome }),
                    byPlanIndex = SwitchOutcomeCalc.byPlanIndex(planRows.map { it.outcome }),
                )
            }
        }.combine(fundPriceRefreshScheduler.observeRunningWork()) { state, running ->
            // Eget led, inte en gren i combinen ovan: körstatusen kommer från WorkManager och
            // ska inte kunna räkna om facit bara för att ett jobb startade (samma gräns som
            // HemViewModel drar).
            state.copy(switchPlanWorking = BackgroundWork.SWITCH_PLAN_SCAN in running)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FacitUiState(),
        )

    /**
     * Dra ned för att uppdatera (UI-11) — startar bytesplansskanningen, den körning som spelar
     * in nya rader och fyller på utfallens NAV. Facit läser bara cachen; utan skanningen finns
     * det ingenting nytt att räkna om. Eget unikt arbetsnamn med `KEEP`, så upprepade
     * dragningar aldrig ger två parallella (dyra) skanningar.
     */
    fun refresh() {
        fundPriceRefreshScheduler.triggerSwitchPlanScan()
    }

    /** Markerar ett förslag som genomfört eller ej (HEM-8/SET-5) — flödet ovan speglar ändringen. */
    fun setFollowed(recordId: Long, followed: Boolean) {
        viewModelScope.launch { suggestionRecordRepository.setFollowed(recordId, followed) }
    }
}
