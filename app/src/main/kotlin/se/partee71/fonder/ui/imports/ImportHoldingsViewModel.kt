package se.partee71.fonder.ui.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.partee71.fonder.data.imports.HoldingsImportParser
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.ImportedHoldingRow
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FundNameMatcher
import se.partee71.fonder.domain.usecase.PurchaseDateEstimator
import java.time.LocalDate
import javax.inject.Inject

/** En importerad rad + användarens (eventuellt korrigerade) matchning/datum/val (issue #8). */
data class ImportRowUiState(
    val row: ImportedHoldingRow,
    val matchedFund: Fund?,
    val matchConfidence: Double?,
    val date: LocalDate,
    val dateConfident: Boolean,
    val included: Boolean = true,
)

enum class ImportError { PARSE_FAILED, EMPTY_FILE }

data class ImportHoldingsUiState(
    val loading: Boolean = false,
    val fileSelected: Boolean = false,
    val rows: List<ImportRowUiState> = emptyList(),
    val catalogFunds: List<Fund> = emptyList(),
    val imported: Boolean = false,
    val error: ImportError? = null,
) {
    val canImport: Boolean get() = rows.any { it.included && it.matchedFund != null }
}

/**
 * Importerar befintliga innehav från Handelsbankens "Innehav Fonder"-Excel-export
 * (issue #8). Föreslår automatiskt fondmatchning ([FundNameMatcher]) och inköpsdatum
 * ([PurchaseDateEstimator]) per rad — användaren bekräftar/korrigerar innan import.
 */
@HiltViewModel
class ImportHoldingsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportHoldingsUiState())
    val uiState: StateFlow<ImportHoldingsUiState> = _uiState.asStateFlow()

    /** [bytes] är hela filens innehåll — läst av anropande skärm (Storage Access Framework). */
    fun onFileSelected(bytes: ByteArray) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, fileSelected = true, error = null, rows = emptyList()) }

            val parsedRows = runCatching {
                HoldingsImportParser.parse(bytes.inputStream())
            }.getOrElse {
                _uiState.update { s -> s.copy(loading = false, error = ImportError.PARSE_FAILED) }
                return@launch
            }
            if (parsedRows.isEmpty()) {
                _uiState.update { it.copy(loading = false, error = ImportError.EMPTY_FILE) }
                return@launch
            }

            val catalog = fundPriceRepository.fetchFundCatalog()
            val rowStates = parsedRows.map { row -> buildRowState(row, catalog.funds) }
            _uiState.update { it.copy(loading = false, rows = rowStates, catalogFunds = catalog.funds) }
        }
    }

    private suspend fun buildRowState(row: ImportedHoldingRow, catalogFunds: List<Fund>): ImportRowUiState {
        val fund = FundNameMatcher.bestMatch(row.fundName, catalogFunds)
        var date = LocalDate.now()
        var dateConfident = false

        if (fund != null) {
            if (fundPriceRepository.latestPrice(fund.fund.fundId) == null) {
                fundPriceRepository.refresh(fund.fund.fundId)
            }
            val to = LocalDate.now()
            val history = fundPriceRepository.priceHistory(fund.fund.fundId, to.minusYears(1).toEpochDay(), to.toEpochDay())
            PurchaseDateEstimator.estimate(row.averageCostPerShare, history)?.let { estimate ->
                date = LocalDate.ofEpochDay(estimate.epochDay)
                dateConfident = estimate.confident
            }
        }

        return ImportRowUiState(
            row = row,
            matchedFund = fund?.fund,
            matchConfidence = fund?.confidence,
            date = date,
            dateConfident = dateConfident,
        )
    }

    fun onFundOverride(row: ImportedHoldingRow, fund: Fund?) {
        updateRow(row) { it.copy(matchedFund = fund, matchConfidence = null) }
    }

    fun onDateOverride(row: ImportedHoldingRow, date: LocalDate) {
        updateRow(row) { it.copy(date = date, dateConfident = true) }
    }

    fun onIncludedChange(row: ImportedHoldingRow, included: Boolean) {
        updateRow(row) { it.copy(included = included) }
    }

    private fun updateRow(row: ImportedHoldingRow, transform: (ImportRowUiState) -> ImportRowUiState) {
        _uiState.update { state ->
            state.copy(rows = state.rows.map { if (it.row == row) transform(it) else it })
        }
    }

    fun import() {
        viewModelScope.launch {
            _uiState.value.rows
                .filter { it.included && it.matchedFund != null }
                .forEach { rowState ->
                    val fund = requireNotNull(rowState.matchedFund)
                    transactionRepository.upsertFund(fund)
                    transactionRepository.addTransaction(
                        Transaction(
                            fundId = fund.fundId,
                            type = TransactionType.KOP,
                            epochDay = rowState.date.toEpochDay(),
                            shares = rowState.row.shares,
                            pricePerShare = rowState.row.averageCostPerShare,
                        ),
                    )
                }
            _uiState.update { it.copy(imported = true) }
        }
    }
}
