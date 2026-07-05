package se.partee71.fonder.ui.fond

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.FundPrice
import java.time.LocalDate
import javax.inject.Inject

data class FondDetaljUiState(
    val loading: Boolean = true,
    val fundName: String? = null,
    val isin: String? = null,
    val suggestedIsin: String? = null,
    val prices: List<FundPrice> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && prices.isEmpty()
}

/**
 * Fonddetalj — kurshistorik sedan första köpet (i diagram och tabell), inte bara senaste
 * året (issue #7-uppföljning: se KRAVLISTA TP-14). Har fonden ett känt ISIN (`Fund.isin`)
 * hämtas historiken från en ISIN-baserad källkedja (Avanza m.fl.) utöver Handelsbankens
 * fasta 5-årsfönster, eftersom äldre köp annars aldrig kan täckas fullt ut. Saknas ISIN
 * föreslås ett via namnsökning — användaren bekräftar/rättar innan det sparas (samma
 * "föreslå men kräv bekräftelse"-princip som importflödet, IMP-2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FondDetaljViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
) : ViewModel() {

    private val fundId: String = checkNotNull(savedStateHandle["fundId"])
    private val suggestedIsin = MutableStateFlow<String?>(null)

    private val earliestPurchase: Flow<LocalDate?> =
        transactionRepository.observeTransactionsForFund(fundId)
            .map { transactions -> transactions.minOfOrNull { it.epochDay }?.let(LocalDate::ofEpochDay) }

    val uiState: StateFlow<FondDetaljUiState> = combine(
        transactionRepository.observeFunds(),
        earliestPurchase,
        suggestedIsin,
    ) { funds, since, suggested -> Triple(funds.firstOrNull { it.fundId == fundId }, since, suggested) }
        .flatMapLatest { (fund, since, suggested) ->
            fundPriceRepository.observePriceHistory(
                fundId = fundId,
                fromEpochDay = (since ?: LocalDate.now().minusYears(1)).toEpochDay(),
                toEpochDay = LocalDate.now().toEpochDay(),
            ).map { prices ->
                FondDetaljUiState(
                    loading = false,
                    fundName = fund?.name,
                    isin = fund?.isin,
                    suggestedIsin = suggested,
                    prices = prices.sortedByDescending { it.epochDay },
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FondDetaljUiState(),
        )

    // Engångsuppdatering per öppning av skärmen — samma "inte en ny bakgrundsjobb"-princip
    // som PortfoljViewModel (issue #6): har fonden ISIN, hämta hela historiken sedan första
    // köpet; annars samma fallback som tidigare (5-årscachen, bara om helt tom).
    init {
        viewModelScope.launch {
            val fund = transactionRepository.observeFunds().first().firstOrNull { it.fundId == fundId }
            val since = earliestPurchase.first()

            if (fund?.isin != null && since != null) {
                fundPriceRepository.refreshSince(fundId, fund.isin, since)
            } else if (fundPriceRepository.latestPrice(fundId) == null) {
                fundPriceRepository.refresh(fundId)
            }

            if (fund != null && fund.isin == null) {
                suggestedIsin.value = fundPriceRepository.suggestIsin(fund.name)
            }
        }
    }

    /** Sparar ett användarbekräftat/rättat ISIN och hämtar direkt historik sedan första köpet med det. */
    fun onIsinConfirmed(isin: String) {
        val trimmed = isin.trim().uppercase()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val fund = transactionRepository.observeFunds().first().firstOrNull { it.fundId == fundId } ?: return@launch
            transactionRepository.upsertFund(fund.copy(isin = trimmed))
            suggestedIsin.value = null
            earliestPurchase.first()?.let { since -> fundPriceRepository.refreshSince(fundId, trimmed, since) }
        }
    }
}
