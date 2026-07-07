package se.partee71.fonder.ui.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.partee71.fonder.data.imports.AvrakningsnotaPdfParser
import se.partee71.fonder.data.imports.PdfTextExtractor
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.ImportedOrderTransaction
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.usecase.ImportFundMatcher
import java.time.LocalDate
import javax.inject.Inject

/** En parsad transaktion + användarens (eventuellt korrigerade) matchning/fält. */
data class ImportOrderRowUiState(
    val transaction: ImportedOrderTransaction,
    val matchedFund: Fund?,
    val matchConfidence: Double?,
    val date: LocalDate,
    val sharesText: String,
    val priceText: String,
    val included: Boolean = true,
) {
    val readyToImport: Boolean
        get() = included && matchedFund != null && sharesText.toDoubleOrNull() != null && priceText.toDoubleOrNull() != null
}

enum class ImportOrdersError { NONE_PARSED }

data class ImportOrdersUiState(
    val loading: Boolean = false,
    val filesSelected: Boolean = false,
    val rows: List<ImportOrderRowUiState> = emptyList(),
    val catalogFunds: List<Fund> = emptyList(),
    val unparsedFileNames: List<String> = emptyList(),
    val error: ImportOrdersError? = null,
    val imported: Boolean = false,
) {
    val canImport: Boolean get() = rows.any { it.readyToImport }
}

/**
 * Importerar exakta fondtransaktioner från Handelsbankens PDF-avräkningsnotor (order-
 * bekräftelser), en eller flera samtidigt (issue #8-uppföljning). Till skillnad från
 * [ImportHoldingsViewModel] (en aggregerad innehavssnapshot med uppskattat inköpsdatum) är
 * datum/kurs/antal här redan exakta — bara fondmatchningen kan behöva bekräftas/rättas.
 */
@HiltViewModel
class ImportOrdersViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val pdfTextExtractor: PdfTextExtractor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportOrdersUiState())
    val uiState: StateFlow<ImportOrdersUiState> = _uiState.asStateFlow()

    /** [files] är (filnamn, filinnehåll) för varje vald PDF — flera filer väljs samtidigt via SAF. */
    fun onFilesSelected(files: List<Pair<String, ByteArray>>) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(loading = true, filesSelected = true, rows = emptyList(), unparsedFileNames = emptyList())
            }

            val parsedByFile = files.associate { (name, bytes) ->
                name to runCatching {
                    AvrakningsnotaPdfParser.parse(pdfTextExtractor.extractText(bytes), name)
                }.getOrDefault(emptyList())
            }
            val unparsedFileNames = parsedByFile.filterValues { it.isEmpty() }.keys.toList()
            val transactions = parsedByFile.values.flatten()

            if (transactions.isEmpty()) {
                _uiState.update { it.copy(loading = false, unparsedFileNames = unparsedFileNames, error = ImportOrdersError.NONE_PARSED) }
                return@launch
            }

            val catalog = fundPriceRepository.fetchFundCatalog()
            val trackedFunds = transactionRepository.observeFunds().first()
            val rows = transactions.map { tx -> buildRowState(tx, catalog, trackedFunds) }

            _uiState.update {
                it.copy(loading = false, rows = rows, catalogFunds = catalog.funds, unparsedFileNames = unparsedFileNames)
            }
        }
    }

    private suspend fun buildRowState(
        transaction: ImportedOrderTransaction,
        catalog: FundCatalog,
        trackedFunds: List<Fund>,
    ): ImportOrderRowUiState {
        val match = ImportFundMatcher.match(
            isin = transaction.isin,
            fundName = transaction.fundName,
            fundCompanyName = transaction.fundCompanyName,
            catalogFunds = catalog.funds,
            trackedFunds = trackedFunds,
            findFundByIsin = fundPriceRepository::findFundByIsin,
        )
        return ImportOrderRowUiState(
            transaction = transaction,
            matchedFund = match?.fund,
            matchConfidence = match?.confidence,
            date = LocalDate.ofEpochDay(transaction.epochDay),
            sharesText = transaction.shares.toString(),
            priceText = transaction.pricePerShare.toString(),
        )
    }

    fun onFundOverride(transaction: ImportedOrderTransaction, fund: Fund?) {
        updateRow(transaction) { it.copy(matchedFund = fund, matchConfidence = null) }
    }

    fun onIncludedChange(transaction: ImportedOrderTransaction, included: Boolean) {
        updateRow(transaction) { it.copy(included = included) }
    }

    fun onDateChange(transaction: ImportedOrderTransaction, date: LocalDate) {
        updateRow(transaction) { it.copy(date = date) }
    }

    fun onSharesTextChange(transaction: ImportedOrderTransaction, text: String) {
        updateRow(transaction) { it.copy(sharesText = text) }
    }

    fun onPriceTextChange(transaction: ImportedOrderTransaction, text: String) {
        updateRow(transaction) { it.copy(priceText = text) }
    }

    private fun updateRow(transaction: ImportedOrderTransaction, transform: (ImportOrderRowUiState) -> ImportOrderRowUiState) {
        _uiState.update { state ->
            state.copy(rows = state.rows.map { if (it.transaction == transaction) transform(it) else it })
        }
    }

    fun import() {
        viewModelScope.launch {
            _uiState.value.rows
                .filter { it.readyToImport }
                .forEach { rowState ->
                    val fund = requireNotNull(rowState.matchedFund)
                    transactionRepository.upsertFund(fund.copy(isin = rowState.transaction.isin))
                    transactionRepository.addTransaction(
                        Transaction(
                            fundId = fund.fundId,
                            type = rowState.transaction.type,
                            epochDay = rowState.date.toEpochDay(),
                            shares = requireNotNull(rowState.sharesText.toDoubleOrNull()),
                            pricePerShare = requireNotNull(rowState.priceText.toDoubleOrNull()),
                        ),
                    )
                }
            _uiState.update { it.copy(imported = true) }
        }
    }
}
