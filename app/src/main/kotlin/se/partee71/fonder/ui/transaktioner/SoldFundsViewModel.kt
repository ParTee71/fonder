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
import se.partee71.fonder.domain.usecase.RealizedGainCalculator
import se.partee71.fonder.domain.usecase.RealizedSale
import javax.inject.Inject

/** En rad i sålda fonder-vyn — realiserat resultat med fondnamnet redan uppslaget. */
data class SoldFundRad(
    val sale: RealizedSale,
    val fundName: String,
    /** Fondens ISIN, null om okänt — utan det går ingen bevakning att starta (SLD-5, issue #114). */
    val fundIsin: String? = null,
    /**
     * Sant när säljet kan starta en bevakning av ett pågående byte (SLD-5): ISIN känt och ingen
     * öppen bevakning redan startad för just det här säljet.
     */
    val canStartSwitchWatch: Boolean = false,
)

data class SoldFundsUiState(
    val loading: Boolean = true,
    val rows: List<SoldFundRad> = emptyList(),
    /** Summan av [RealizedSale.realizedGain] över alla rader (SLD-3, issue #21). */
    val totalRealizedGain: Double = 0.0,
    /** [totalRealizedGain] som andel av summerat [RealizedSale.costBasis], eller null om den summan är 0 (SLD-3). */
    val totalRealizedGainFraction: Double? = null,
    /** Sätts när en bevakning just startats (SLD-5), så skärmen kan navigera dit. Kvitteras med [SoldFundsViewModel.onSwitchWatchOpened]. */
    val startedSwitchWatchId: Long? = null,
) {
    val isEmpty: Boolean get() = !loading && rows.isEmpty()
}

/**
 * Realiserat resultat per säljtransaktion (FIFO, se [RealizedGainCalculator], issue #10) —
 * en egen vy separat från Transaktioner, som bara visar orealiserad utveckling (POR-3).
 */
@HiltViewModel
class SoldFundsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val switchWatchRepository: SwitchWatchRepository,
    private val fundMetadataRepository: FundMetadataRepository,
) : ViewModel() {

    /** Se [SoldFundsUiState.startedSwitchWatchId] — engångshändelse, inte ett bestående tillstånd. */
    private val startedSwitchWatchId = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<SoldFundsUiState> =
        combine(
            repository.observeTransactions(),
            repository.observeFunds(),
            // Nyckeln är fond **och** säljdag: en fond kan säljas i omgångar, och bara det sälj
            // som redan bevakas ska sluta erbjuda en bevakning (SLD-5).
            switchWatchRepository.observeOpen().map { watches ->
                watches.mapTo(mutableSetOf()) { it.sellIsin to it.soldAtEpochDay }
            },
            startedSwitchWatchId,
        ) { transactions, funds, watchedSales, startedId ->
            val fundsById = funds.associateBy { it.fundId }
            val sales = RealizedGainCalculator.compute(transactions)
            val rows = sales.map { sale ->
                val fund = fundsById[sale.fundId]
                val isin = fund?.isin
                SoldFundRad(
                    sale = sale,
                    fundName = fund?.name ?: sale.fundId,
                    fundIsin = isin,
                    canStartSwitchWatch = isin != null && (isin to sale.epochDay) !in watchedSales,
                )
            }
            val totalCostBasis = sales.sumOf { it.costBasis }
            SoldFundsUiState(
                loading = false,
                rows = rows,
                totalRealizedGain = sales.sumOf { it.realizedGain },
                totalRealizedGainFraction = if (totalCostBasis == 0.0) null else sales.sumOf { it.realizedGain } / totalCostBasis,
                startedSwitchWatchId = startedId,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SoldFundsUiState(),
        )

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
     * nätverksfråga görs för att fylla luckan — Sålda fonder är en lokal vy.
     */
    fun startSwitchWatch(row: SoldFundRad) {
        val isin = row.fundIsin ?: return
        viewModelScope.launch {
            if (switchWatchRepository.hasOpenFor(isin, row.sale.epochDay)) return@launch
            startedSwitchWatchId.value = switchWatchRepository.start(
                SwitchWatch(
                    sellIsin = isin,
                    sellFundName = row.fundName,
                    soldAtEpochDay = row.sale.epochDay,
                    proceedsKr = row.sale.proceeds - row.sale.fee,
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
