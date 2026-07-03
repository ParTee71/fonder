package se.partee71.fonder.ui.transaktioner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.TransactionFormValidator
import java.time.LocalDate
import javax.inject.Inject

data class TransactionFormUiState(
    val funds: List<Fund> = emptyList(),
    val selectedFund: Fund? = null,
    val type: TransactionType = TransactionType.KOP,
    val date: LocalDate = LocalDate.now(),
    val sharesText: String = "",
    val priceText: String = "",
    val valid: Boolean = false,
    val saved: Boolean = false,
)

private data class FormFields(
    val fund: Fund?,
    val type: TransactionType,
    val date: LocalDate,
    val sharesText: String,
    val priceText: String,
)

/** Formulär för att registrera en fondtransaktion mot en redan bevakad fond (issue #4). */
@HiltViewModel
class TransactionFormViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
) : ViewModel() {

    private val funds = MutableStateFlow<List<Fund>>(emptyList())
    private val selectedFund = MutableStateFlow<Fund?>(null)
    private val type = MutableStateFlow(TransactionType.KOP)
    private val date = MutableStateFlow(LocalDate.now())
    private val sharesText = MutableStateFlow("")
    private val priceText = MutableStateFlow("")
    private val saved = MutableStateFlow(false)

    private val formFields = combine(selectedFund, type, date, sharesText, priceText) { fund, type, date, shares, price ->
        FormFields(fund, type, date, shares, price)
    }

    val uiState: StateFlow<TransactionFormUiState> =
        combine(funds, formFields, saved) { funds, fields, saved ->
            val shares = fields.sharesText.toDoubleOrNull()
            val price = fields.priceText.toDoubleOrNull()
            TransactionFormUiState(
                funds = funds,
                selectedFund = fields.fund,
                type = fields.type,
                date = fields.date,
                sharesText = fields.sharesText,
                priceText = fields.priceText,
                valid = TransactionFormValidator.isValid(fields.fund?.fundId, shares, price, fields.date),
                saved = saved,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TransactionFormUiState(),
        )

    init {
        transactionRepository.observeFunds().onEach { funds.value = it }.launchIn(viewModelScope)
    }

    fun onFundSelected(fund: Fund) {
        selectedFund.value = fund
        viewModelScope.launch {
            fundPriceRepository.latestPrice(fund.fundId)?.let { priceText.value = it.nav.toString() }
        }
    }

    fun onTypeChange(newType: TransactionType) {
        type.value = newType
    }

    fun onDateChange(newDate: LocalDate) {
        date.value = newDate
    }

    fun onSharesTextChange(text: String) {
        sharesText.value = text
    }

    fun onPriceTextChange(text: String) {
        priceText.value = text
    }

    fun save() {
        val fund = selectedFund.value ?: return
        val shares = sharesText.value.toDoubleOrNull() ?: return
        val price = priceText.value.toDoubleOrNull() ?: return
        if (!TransactionFormValidator.isValid(fund.fundId, shares, price, date.value)) return
        viewModelScope.launch {
            transactionRepository.addTransaction(
                Transaction(
                    fundId = fund.fundId,
                    type = type.value,
                    epochDay = date.value.toEpochDay(),
                    shares = shares,
                    pricePerShare = price,
                ),
            )
            saved.value = true
        }
    }
}
