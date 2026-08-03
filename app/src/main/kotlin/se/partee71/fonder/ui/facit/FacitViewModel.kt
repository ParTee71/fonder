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
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.usecase.SwitchOutcomeCalc
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
    /** Utfallet över **alla** inspelade förslag — hur bra rådet var. */
    val allSummary: SwitchOutcomeCalc.Summary = SwitchOutcomeCalc.Summary(),
    /** Utfallet över enbart de användaren markerat som genomförda — vad rådet faktiskt gav. */
    val followedSummary: SwitchOutcomeCalc.Summary = SwitchOutcomeCalc.Summary(),
    /** Snitt per plats i planen (SET-5) — underlaget för om `MAX_SWITCHES_PER_PLAN` kan höjas. */
    val byPlanIndex: List<SwitchOutcomeCalc.PlanIndexSummary> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && rows.isEmpty()
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

                val outcomes = rows.map { it.outcome }
                FacitUiState(
                    loading = false,
                    rows = rows,
                    allSummary = SwitchOutcomeCalc.summarize(outcomes),
                    followedSummary = SwitchOutcomeCalc.summarize(rows.filter { it.followed }.map { it.outcome }),
                    byPlanIndex = SwitchOutcomeCalc.byPlanIndex(outcomes),
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FacitUiState(),
        )

    /** Markerar ett förslag som genomfört eller ej (HEM-8/SET-5) — flödet ovan speglar ändringen. */
    fun setFollowed(recordId: Long, followed: Boolean) {
        viewModelScope.launch { suggestionRecordRepository.setFollowed(recordId, followed) }
    }
}
