package se.partee71.fonder.ui.transaktioner

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SwitchWatchRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.RealizedGainCalculator
import se.partee71.fonder.domain.usecase.RealizedSale
import se.partee71.fonder.domain.usecase.RealizedTotal
import se.partee71.fonder.domain.usecase.RecordTransaction
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import se.partee71.fonder.domain.usecase.TaxCalc
import se.partee71.fonder.ui.common.RetrySignal
import se.partee71.fonder.ui.common.catchAsListError
import se.partee71.fonder.ui.common.errorOrNull
import se.partee71.fonder.ui.common.itemsOrEmpty
import se.partee71.fonder.ui.common.restartOn
import se.partee71.ui.common.ListUiState
import se.partee71.ui.common.UiError
import se.partee71.ui.common.toListUiState

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
     * öppen bevakning redan kopplad till just den här säljtransaktionen (BYT-1). En öppen bevakning
     * utan känd transaktion för samma fond och dag kopplas i stället när knappen trycks.
     */
    val canStartSwitchWatch: Boolean = false
)

@Immutable
data class TransaktionerUiState(
    /** Alla transaktioner, senaste först (DAO:ns ordning) — laddning, tomt, fel eller rader (NFR-6). */
    val list: ListUiState<TransaktionRad> = ListUiState.Loading,
    val filter: HandelseFilter = HandelseFilter.ALLA,
    /** Realiserat resultat för sälj gjorda i år (TRX-4). */
    val yearRealized: RealizedTotal = EMPTY_TOTAL,
    /** Realiserat resultat över alla sälj (SLD-3), visat i säljfiltret. */
    val totalRealized: RealizedTotal = EMPTY_TOTAL,
    /** Årets vinster och förluster var för sig och den ungefärliga skatten på nettot (TRX-8) — visas bara i depå/AF. */
    val yearRealizedTax: TaxCalc.RealizedTax = TaxCalc.RealizedTax(gains = 0.0, losses = 0.0, tax = 0.0),
    /** Kontotypen är depå/AF (SET-4): årskortet visar den ungefärliga skatten (TRX-8). */
    val depa: Boolean = false,
    /** Skatten på schablonintäkten för fonderna den 1 januari (TRX-8), null utan depå/AF, utan innehav vid årets början eller utan årets första kurs. */
    val standardIncomeTax: TaxCalc.StandardIncomeTax? = null,
    /** Sätts när en bevakning just startats (SLD-5), så skärmen kan navigera dit. Kvitteras med [TransaktionerViewModel.onSwitchWatchOpened]. */
    val startedSwitchWatchId: Long? = null
) {
    val loading: Boolean get() = list is ListUiState.Loading

    /** Alla transaktioner, senaste först; inga medan listan laddas eller inte gick att läsa. */
    val allRows: ImmutableList<TransaktionRad> get() = list.itemsOrEmpty()

    /** Läsfelet som visas i stället för listan (NFR-6). */
    val error: UiError? get() = list.errorOrNull()

    /** Raderna i det valda filtret (TRX-3). */
    val rows: List<TransaktionRad>
        get() = when (filter) {
            HandelseFilter.ALLA -> allRows
            HandelseFilter.KOP -> allRows.filter { it.transaction.type == TransactionType.KOP }
            HandelseFilter.SALJ -> allRows.filter { it.transaction.type == TransactionType.SALJ }
        }

    /** Inga transaktioner alls — då visas bara tomt tillstånd, inget filter. */
    val isEmpty: Boolean get() = list is ListUiState.Empty

    /**
     * Kortet med realiserat resultat (TRX-4) visas när det finns något sälj och filtret inte är Köp —
     * i depå/AF även utan sälj när årets schablonintäkt är känd (TRX-8).
     */
    val showRealizedCard: Boolean get() = filter != HandelseFilter.KOP && (totalRealized.saleCount > 0 || standardIncomeTax != null)

    /** Raden "Realiserat resultat i år" (TRX-4) — när något sälj alls finns. */
    val showRealizedRow: Boolean get() = totalRealized.saleCount > 0

    /** Raden "Varav skatt" (TRX-8) — i depå/AF när något sälj gjorts i år. */
    val showRealizedTax: Boolean get() = depa && yearRealized.saleCount > 0

    private companion object {
        val EMPTY_TOTAL = RealizedTotal(realizedGain = 0.0, costBasis = 0.0, saleCount = 0, uncertain = false)
    }
}

/** Användarens handlingar på Händelser, till [TransaktionerViewModel.onEvent]. */
sealed interface TransaktionerEvent {
    /** "Försök igen" efter ett läsfel (NFR-6). */
    data object Retry : TransaktionerEvent

    /** Filtret Alla/Köp/Sälj (TRX-3). */
    data class FilterSelected(val filter: HandelseFilter) : TransaktionerEvent

    /** Tar bort transaktionen (TRX-2). */
    data class DeleteTransaction(val id: Long) : TransaktionerEvent

    /** Startar en fristående bevakning för säljet (SLD-5). */
    data class StartSwitchWatch(val row: TransaktionRad) : TransaktionerEvent

    /** Kvitterar navigeringen till en nystartad bevakning. */
    data object SwitchWatchOpened : TransaktionerEvent
}

/**
 * Fliken Händelser (NAV-1): alla transaktioner i en lista med filtret Alla/Köp/Sälj (TRX-3), årets
 * realiserade resultat (TRX-4) och, per sälj, det realiserade resultatet ur
 * [RealizedGainCalculator] (SLD-1…SLD-5) — tidigare en egen vy, Sålda fonder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransaktionerViewModel internal constructor(
    private val repository: TransactionRepository,
    switchWatchRepository: SwitchWatchRepository,
    private val recordTransaction: RecordTransaction,
    private val preferencesRepository: PreferencesRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val today: () -> LocalDate
) : ViewModel() {

    @Inject
    constructor(
        repository: TransactionRepository,
        switchWatchRepository: SwitchWatchRepository,
        recordTransaction: RecordTransaction,
        preferencesRepository: PreferencesRepository,
        fundPriceRepository: FundPriceRepository
    ) : this(repository, switchWatchRepository, recordTransaction, preferencesRepository, fundPriceRepository, LocalDate::now)

    /** Depå/AF-delen av årskortet (TRX-8): null i ISK/KF eller utan vald kontotyp. */
    private class DepaTax(val standardIncome: TaxCalc.StandardIncomeTax?)

    /**
     * Kontotypen och, i depå/AF, schablonintäkten ur innehavet före årsskiftet och kurserna kring
     * det — reaktivt på kurscachen, så raden dyker upp när kursen hämtats. Får [transactions] och
     * [year] av huvudflödet, så listan och skatten aldrig räknar på olika transaktioner eller år.
     * Ett läsfel här (kontotyp, kurser) ger ingen skatt, men fäller aldrig listan (NFR-6).
     */
    private fun depaTax(transactions: List<Transaction>, year: Int, today: LocalDate): Flow<DepaTax?> = preferencesRepository.accountType.flatMapLatest { type ->
        if (type != AccountType.DEPA_AF) {
            flowOf(null)
        } else {
            val yearStart = LocalDate.of(year, 1, 1)
            val heldFundIds = transactions.filter { it.epochDay < yearStart.toEpochDay() }.map { it.fundId }.distinct().sorted()
            fundPriceRepository.observeHistories(heldFundIds, yearStart.minusDays(TaxCalc.PERIOD_START_LOOKBACK_DAYS).toEpochDay()).map { history ->
                DepaTax(TaxCalc.fundStandardIncomeTaxForYear(year, today, transactions, history))
            }
        }
    }.catch { emit(null) }

    /** Läsfelets "Försök igen" (NFR-6) – se [retry]. */
    private val retrySignal = RetrySignal()

    private val filter = MutableStateFlow(HandelseFilter.ALLA)

    /** Se [TransaktionerUiState.startedSwitchWatchId] — engångshändelse, inte ett bestående tillstånd. */
    private val startedSwitchWatchId = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<TransaktionerUiState> =
        repository.observeTransactions().flatMapLatest { transactions ->
            // Ett "idag" per emission: årets resultat, årets skatt och schablonintäkten gäller samma år.
            val today = today()
            combine(
                repository.observeFunds(),
                switchWatchRepository.observeOpen(),
                filter,
                startedSwitchWatchId,
                depaTax(transactions, today.year, today)
            ) { funds, openWatches, selectedFilter, startedId, depa ->
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
                        canStartSwitchWatch = sale != null && isin != null && openWatches.none { it.covers(tx, isin) }
                    )
                }
                TransaktionerUiState(
                    list = rows.toListUiState(),
                    filter = selectedFilter,
                    yearRealized = RealizedGainCalculator.totalForYear(sales, today.year),
                    totalRealized = RealizedGainCalculator.total(sales),
                    yearRealizedTax = TaxCalc.realizedTax(sales, today.year),
                    depa = depa != null,
                    standardIncomeTax = depa?.standardIncome,
                    startedSwitchWatchId = startedId
                )
            }
        }.catchAsListError { TransaktionerUiState(list = it) }.restartOn(retrySignal).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TransaktionerUiState()
        )

    fun onEvent(event: TransaktionerEvent) {
        when (event) {
            TransaktionerEvent.Retry -> retry()
            is TransaktionerEvent.FilterSelected -> onFilterSelected(event.filter)
            is TransaktionerEvent.DeleteTransaction -> deleteTransaction(event.id)
            is TransaktionerEvent.StartSwitchWatch -> startSwitchWatch(event.row)
            TransaktionerEvent.SwitchWatchOpened -> onSwitchWatchOpened()
        }
    }

    /** "Försök igen" efter ett läsfel (NFR-6): läser om transaktionerna från början. */
    private fun retry() {
        retrySignal.retry()
    }

    private fun onFilterSelected(selected: HandelseFilter) {
        filter.value = selected
    }

    /** Tar bort transaktionen (TRX-2) via [RecordTransaction.delete]; bytets länk nollas, bevakningen ligger kvar. */
    private fun deleteTransaction(id: Long) {
        viewModelScope.launch { recordTransaction.delete(id) }
    }

    /**
     * Startar en fristående bevakning av ett pågående byte för säljet (SLD-5, issue #114) — för
     * ett byte gjort på eget initiativ, utanför bytesplanen (HEM-8), via
     * [RecordTransaction.startWatchForSale] (BYT-2): säljdag och likvid ur transaktionen, målnivå
     * ur säljfondens egen risknivå, och en bevakning per säljtransaktion.
     */
    private fun startSwitchWatch(row: TransaktionRad) {
        if (row.fundIsin == null || row.sale == null) return
        viewModelScope.launch {
            recordTransaction.startWatchForSale(row.transaction, today())
                ?.let { startedSwitchWatchId.value = it }
        }
    }

    /**
     * Sant när bevakningen redan är säljet [tx]:s (BYT-1): länkad till transaktionen — eller utan
     * känd transaktion för samma fond och **samma dag** men utgången och ännu inte stängd, som
     * blockerade före 0.67.0 (fond + dag). En öppen, inte utgången bevakning utan transaktion
     * samma dag blockerar **inte** — knappen kopplar den till säljet i stället för att starta en
     * ny ([RecordTransaction.startWatchForSale]).
     */
    private fun SwitchWatch.covers(tx: Transaction, isin: String?): Boolean = sellTransactionId == tx.id ||
        (sellTransactionId == null && sellIsin == isin && soldAtEpochDay == tx.epochDay && SwitchWatchCalc.isExpired(this, today()))

    /** Kvitterar navigeringen till en nystartad bevakning, så den inte upprepas vid nästa emission. */
    private fun onSwitchWatchOpened() {
        startedSwitchWatchId.value = null
    }
}
