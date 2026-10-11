package se.partee71.fonder.ui.transaktioner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.SwitchWatchRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.RealizedGainCalculator
import se.partee71.fonder.domain.usecase.RealizedSale
import se.partee71.fonder.domain.usecase.RealizedTotal
import java.time.LocalDate
import javax.inject.Inject

/** Händelsers filter (TRX-3): alla transaktioner, bara köp eller bara sälj. */
enum class HandelseFilter { ALLA, KOP, SALJ }

/**
 * En rad i Händelser: transaktionen med fondnamnet redan uppslaget och, för ett sälj, dess
 * realiserade resultat (SLD-1) ur [RealizedGainCalculator] — samma motor som innehaven (TP-15).
 */
data class TransaktionRad(
    val transaction: Transaction,
    val fundName: String,
    /** Säljets realiserade resultat (SLD-1), null för köp. */
    val sale: RealizedSale? = null,
    /** Fondens ISIN, null om okänt — utan det går ingen bevakning att starta (SLD-5). */
    val fundIsin: String? = null,
    /**
     * Sant när säljet kan starta en bevakning av ett pågående byte (SLD-5): ISIN känt och ingen
     * öppen bevakning redan startad för just det här säljet.
     */
    val canStartSwitchWatch: Boolean = false,
)

data class TransaktionerUiState(
    val loading: Boolean = true,
    /** Alla transaktioner, senaste först (DAO:ns ordning). */
    val allRows: List<TransaktionRad> = emptyList(),
    val filter: HandelseFilter = HandelseFilter.ALLA,
    /** Realiserat resultat för sälj gjorda i år (TRX-4). */
    val yearRealized: RealizedTotal = EMPTY_TOTAL,
    /** Realiserat resultat över alla sälj (SLD-3), visat i säljfiltret. */
    val totalRealized: RealizedTotal = EMPTY_TOTAL,
    /** Sätts när en bevakning just startats (SLD-5), så skärmen kan navigera dit. Kvitteras med [TransaktionerViewModel.onSwitchWatchOpened]. */
    val startedSwitchWatchId: Long? = null,
) {
    /** Raderna i det valda filtret (TRX-3). */
    val rows: List<TransaktionRad>
        get() = when (filter) {
            HandelseFilter.ALLA -> allRows
            HandelseFilter.KOP -> allRows.filter { it.transaction.type == TransactionType.KOP }
            HandelseFilter.SALJ -> allRows.filter { it.transaction.type == TransactionType.SALJ }
        }

    /** Inga transaktioner alls — då visas bara tomt tillstånd, inget filter. */
    val isEmpty: Boolean get() = !loading && allRows.isEmpty()

    /** Kortet med realiserat resultat (TRX-4) visas när det finns något sälj och filtret inte är Köp. */
    val showRealizedCard: Boolean get() = totalRealized.saleCount > 0 && filter != HandelseFilter.KOP

    private companion object {
        val EMPTY_TOTAL = RealizedTotal(realizedGain = 0.0, costBasis = 0.0, saleCount = 0, uncertain = false)
    }
}

/**
 * Fliken Händelser (NAV-1): alla transaktioner i en lista med filtret Alla/Köp/Sälj (TRX-3), årets
 * realiserade resultat (TRX-4) och, per sälj, det realiserade resultatet ur
 * [RealizedGainCalculator] (SLD-1…SLD-5) — tidigare en egen vy, Sålda fonder.
 */
@HiltViewModel
class TransaktionerViewModel internal constructor(
    private val repository: TransactionRepository,
    private val switchWatchRepository: SwitchWatchRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val today: () -> LocalDate,
) : ViewModel() {

    @Inject
    constructor(
        repository: TransactionRepository,
        switchWatchRepository: SwitchWatchRepository,
        fundMetadataRepository: FundMetadataRepository,
    ) : this(repository, switchWatchRepository, fundMetadataRepository, LocalDate::now)

    private val filter = MutableStateFlow(HandelseFilter.ALLA)

    /** Se [TransaktionerUiState.startedSwitchWatchId] — engångshändelse, inte ett bestående tillstånd. */
    private val startedSwitchWatchId = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<TransaktionerUiState> =
        combine(
            repository.observeTransactions(),
            repository.observeFunds(),
            // Nyckeln är fond **och** säljdag: en fond kan säljas i omgångar, och bara det sälj
            // som redan bevakas ska sluta erbjuda en bevakning (SLD-5).
            switchWatchRepository.observeOpen().map { watches ->
                watches.mapTo(mutableSetOf()) { it.sellIsin to it.soldAtEpochDay }
            },
            filter,
            startedSwitchWatchId,
        ) { transactions, funds, watchedSales, selectedFilter, startedId ->
            val fundsById = funds.associateBy { it.fundId }
            val sales = RealizedGainCalculator.compute(transactions)
            val salesByTransactionId = sales.associateBy { it.transactionId }
            val rows = transactions.map { tx ->
                val fund = fundsById[tx.fundId]
                val sale = salesByTransactionId[tx.id]
                val isin = fund?.isin
                TransaktionRad(
                    transaction = tx,
                    fundName = fund?.name ?: tx.fundId,
                    sale = sale,
                    fundIsin = isin,
                    canStartSwitchWatch = sale != null && isin != null && (isin to sale.epochDay) !in watchedSales,
                )
            }
            TransaktionerUiState(
                loading = false,
                allRows = rows,
                filter = selectedFilter,
                yearRealized = RealizedGainCalculator.totalForYear(sales, today().year),
                totalRealized = RealizedGainCalculator.total(sales),
                startedSwitchWatchId = startedId,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TransaktionerUiState(),
        )

    fun onFilterSelected(selected: HandelseFilter) {
        filter.value = selected
    }

    fun deleteTransaction(id: Long) {
        viewModelScope.launch { repository.deleteTransaction(id) }
    }

    /**
     * Startar en fristående bevakning av ett pågående byte för säljet (SLD-5, issue #114) — för
     * ett byte gjort på eget initiativ, utanför bytesplanen (HEM-8).
     *
     * Säljdatumet och likviden tas ur säljtransaktionen, inte ur dagens datum: här *finns* det
     * verkliga datumet, och utvecklingen ska mätas från den dag pengarna faktiskt frigjordes.
     *
     * Målnivån för de automatiska alternativen (ANA-13) är säljfondens **egen** risknivå ur
     * metadatacachen — det närmaste appen kommer "något likvärdigt" utan en riskprofil att mäta
     * mot. Saknas nivån i cachen startas bevakningen ändå, men utan förslag: en gissad nivå hade
     * gett alternativ på fel risk, vilket är sämre än inga alternativ (ANA-4-principen). Ingen
     * nätverksfråga görs för att fylla luckan — Händelser är en lokal vy.
     */
    fun startSwitchWatch(row: TransaktionRad) {
        val isin = row.fundIsin ?: return
        val sale = row.sale ?: return
        viewModelScope.launch {
            if (switchWatchRepository.hasOpenFor(isin, sale.epochDay)) return@launch
            startedSwitchWatchId.value = switchWatchRepository.start(
                SwitchWatch(
                    sellIsin = isin,
                    sellFundName = row.fundName,
                    soldAtEpochDay = sale.epochDay,
                    proceedsKr = sale.proceeds - sale.fee,
                    targetLevel = fundMetadataRepository.cachedMetadataFor(listOf(isin))[isin]?.risk,
                ),
            )
        }
    }

    /** Kvitterar navigeringen till en nystartad bevakning, så den inte upprepas vid nästa emission. */
    fun onSwitchWatchOpened() {
        startedSwitchWatchId.value = null
    }
}
