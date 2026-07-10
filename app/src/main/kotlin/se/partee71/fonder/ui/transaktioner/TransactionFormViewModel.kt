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
import se.partee71.fonder.domain.usecase.SwedishNumberFormat
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
    val feeText: String = "",
    val valid: Boolean = false,
    val saved: Boolean = false,
)

private data class FormFields(
    val fund: Fund?,
    val type: TransactionType,
    val date: LocalDate,
    val sharesText: String,
    val priceText: String,
    val feeText: String,
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
    private val feeText = MutableStateFlow("")
    private val saved = MutableStateFlow(false)

    // combine() saknar en 6-parametersvariant — kombinerar därför i två steg.
    private val baseFields = combine(selectedFund, type, date) { fund, type, date -> Triple(fund, type, date) }
    private val amountFields = combine(sharesText, priceText, feeText) { shares, price, fee -> Triple(shares, price, fee) }
    private val formFields = combine(baseFields, amountFields) { (fund, type, date), (shares, price, fee) ->
        FormFields(fund, type, date, shares, price, fee)
    }

    val uiState: StateFlow<TransactionFormUiState> =
        combine(funds, formFields, saved) { funds, fields, saved ->
            val shares = SwedishNumberFormat.parse(fields.sharesText)
            val price = SwedishNumberFormat.parse(fields.priceText)
            val fee = parseFee(fields.feeText)
            TransactionFormUiState(
                funds = funds,
                selectedFund = fields.fund,
                type = fields.type,
                date = fields.date,
                sharesText = fields.sharesText,
                priceText = fields.priceText,
                feeText = fields.feeText,
                valid = TransactionFormValidator.isValid(fields.fund?.fundId, shares, price, fields.date, fee),
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

    fun onFeeTextChange(text: String) {
        feeText.value = text
    }

    fun save() {
        val fund = selectedFund.value ?: return
        val shares = SwedishNumberFormat.parse(sharesText.value) ?: return
        val price = SwedishNumberFormat.parse(priceText.value) ?: return
        val fee = parseFee(feeText.value) ?: return
        if (!TransactionFormValidator.isValid(fund.fundId, shares, price, date.value, fee)) return
        viewModelScope.launch {
            transactionRepository.addTransaction(
                Transaction(
                    fundId = fund.fundId,
                    type = type.value,
                    epochDay = date.value.toEpochDay(),
                    shares = shares,
                    pricePerShare = price,
                    fee = fee,
                ),
            )
            saved.value = true
        }
    }

    /** Tomt avgiftsfält tolkas som 0.0 (ingen känd avgift) — annars måste texten vara ett giltigt tal. */
    private fun parseFee(text: String): Double? = if (text.isBlank()) 0.0 else SwedishNumberFormat.parse(text)
}
