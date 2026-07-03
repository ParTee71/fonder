package se.partee71.fonder.ui.fond

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val prices: List<FundPrice> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && prices.isEmpty()
}

@HiltViewModel
class FondDetaljViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
) : ViewModel() {

    private val fundId: String = checkNotNull(savedStateHandle["fundId"])

    val uiState: StateFlow<FondDetaljUiState> = combine(
        transactionRepository.observeFunds(),
        fundPriceRepository.observePriceHistory(
            fundId = fundId,
            fromEpochDay = LocalDate.now().minusYears(1).toEpochDay(),
            toEpochDay = LocalDate.now().toEpochDay(),
        ),
    ) { funds, prices ->
        FondDetaljUiState(
            loading = false,
            fundName = funds.firstOrNull { it.fundId == fundId }?.name,
            prices = prices.sortedByDescending { it.epochDay },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FondDetaljUiState(),
    )

    // Engångsuppdatering om fonden saknar cachad kurs helt (samma mönster som
    // PortfoljViewModel, issue #6) — ingen ny bakgrundsjobb-mekanism.
    init {
        viewModelScope.launch {
            if (fundPriceRepository.latestPrice(fundId) == null) {
                fundPriceRepository.refresh(fundId)
            }
        }
    }
}
