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
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.ImportedHoldingRow
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FundNameMatcher
import se.partee71.fonder.domain.usecase.PurchaseDateEstimator
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.abs

private const val SHARES_MATCH_TOLERANCE = 1e-6

/** Hur långt tillbaka kurshistoriken hämtas/söks vid import — se KRAVLISTA (TP-13). */
private const val PRICE_HISTORY_YEARS = 5L

/**
 * Ett enskilt inköpstillfälle för en importerad rad (issue #8-uppföljning: en rad i
 * exporten är ofta en aggregerad post byggd av flera köp vid olika tillfällen — hela
 * innehavet behöver därför kunna delas upp i flera transaktioner med egna datum/antal,
 * i stället för att tvinga in allt i en enda syntetisk transaktion).
 */
data class ImportOccasion(
    val date: LocalDate,
    val dateConfident: Boolean,
    val sharesText: String,
) {
    val shares: Double? get() = sharesText.trim().replace(",", ".").toDoubleOrNull()
}

/** En importerad rad + användarens (eventuellt korrigerade) matchning/inköpstillfällen (issue #8). */
data class ImportRowUiState(
    val row: ImportedHoldingRow,
    val matchedFund: Fund?,
    val matchConfidence: Double?,
    val occasions: List<ImportOccasion>,
    val included: Boolean = true,
) {
    val occasionSharesTotal: Double get() = occasions.sumOf { it.shares ?: 0.0 }
    val sharesMismatch: Boolean get() = abs(occasionSharesTotal - row.shares) > SHARES_MATCH_TOLERANCE

    private val occasionsValid: Boolean
        get() = occasions.isNotEmpty() && occasions.all { (it.shares ?: 0.0) > 0.0 } && !sharesMismatch

    val readyToImport: Boolean get() = included && matchedFund != null && occasionsValid
}

enum class ImportError { PARSE_FAILED, EMPTY_FILE }

data class ImportHoldingsUiState(
    val loading: Boolean = false,
    val fileSelected: Boolean = false,
    val rows: List<ImportRowUiState> = emptyList(),
    val catalogFunds: List<Fund> = emptyList(),
    val imported: Boolean = false,
    val error: ImportError? = null,
) {
    val canImport: Boolean get() = rows.any { it.readyToImport }
}

/**
 * Importerar befintliga innehav från Handelsbankens "Innehav Fonder"-Excel-export
 * (issue #8). Föreslår automatiskt fondmatchning ([FundNameMatcher]) och inköpsdatum
 * ([PurchaseDateEstimator]) per rad — användaren bekräftar/korrigerar innan import, och
 * kan dela upp raden i flera inköpstillfällen om innehavet byggts upp vid olika datum.
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
            val rowStates = parsedRows.map { row -> buildRowState(row, catalog) }
            _uiState.update { it.copy(loading = false, rows = rowStates, catalogFunds = catalog.funds) }
        }
    }

    private suspend fun buildRowState(row: ImportedHoldingRow, catalog: FundCatalog): ImportRowUiState {
        val fund = FundNameMatcher.bestMatch(row.fundName, catalog.funds, row.fundCompanyName)
        // Utan en tillförlitlig datumuppskattning (inget kurshistorik-fynd) antas ett gammalt
        // innehav hellre ha köpts för länge sedan än "idag" — samma gräns som kurshistoriken
        // (PRICE_HISTORY_YEARS) söks inom, så gissningen aldrig hamnar utanför sökfönstret.
        var date = LocalDate.now().minusYears(PRICE_HISTORY_YEARS)
        var dateConfident = false

        if (fund != null) {
            // Uppdatera alltid hela kurshistoriken vid import — även om fonden redan bevakas
            // sedan tidigare och har en cachad kurs — så att både inköpsdatum-uppskattningen
            // nedan och den historiska värdeutvecklingen (#7) baseras på fullständig data,
            // inte bara de dagar som råkat cachas via den dagliga bakgrundsuppdateringen.
            fundPriceRepository.refresh(fund.fund.fundId)
            val to = LocalDate.now()
            val history = fundPriceRepository.priceHistory(fund.fund.fundId, to.minusYears(PRICE_HISTORY_YEARS).toEpochDay(), to.toEpochDay())
            PurchaseDateEstimator.estimate(row.averageCostPerShare, history)?.let { estimate ->
                date = LocalDate.ofEpochDay(estimate.epochDay)
                dateConfident = estimate.confident
            }
        }

        return ImportRowUiState(
            row = row,
            matchedFund = fund?.fund,
            matchConfidence = fund?.confidence,
            occasions = listOf(ImportOccasion(date = date, dateConfident = dateConfident, sharesText = row.shares.toString())),
        )
    }

    fun onFundOverride(row: ImportedHoldingRow, fund: Fund?) {
        updateRow(row) { it.copy(matchedFund = fund, matchConfidence = null) }
    }

    fun onIncludedChange(row: ImportedHoldingRow, included: Boolean) {
        updateRow(row) { it.copy(included = included) }
    }

    fun onOccasionDateChange(row: ImportedHoldingRow, index: Int, date: LocalDate) {
        updateOccasion(row, index) { it.copy(date = date, dateConfident = true) }
    }

    fun onOccasionSharesChange(row: ImportedHoldingRow, index: Int, sharesText: String) {
        updateOccasion(row, index) { it.copy(sharesText = sharesText) }
    }

    /** Delar upp raden i ytterligare ett inköpstillfälle — kvarstående andelar måste fördelas manuellt. */
    fun onAddOccasion(row: ImportedHoldingRow) {
        updateRow(row) { it.copy(occasions = it.occasions + ImportOccasion(date = LocalDate.now(), dateConfident = false, sharesText = "")) }
    }

    fun onRemoveOccasion(row: ImportedHoldingRow, index: Int) {
        updateRow(row) { state ->
            if (state.occasions.size <= 1) return@updateRow state
            state.copy(occasions = state.occasions.filterIndexed { i, _ -> i != index })
        }
    }

    private fun updateOccasion(row: ImportedHoldingRow, index: Int, transform: (ImportOccasion) -> ImportOccasion) {
        updateRow(row) { state ->
            state.copy(occasions = state.occasions.mapIndexed { i, occasion -> if (i == index) transform(occasion) else occasion })
        }
    }

    private fun updateRow(row: ImportedHoldingRow, transform: (ImportRowUiState) -> ImportRowUiState) {
        _uiState.update { state ->
            state.copy(rows = state.rows.map { if (it.row == row) transform(it) else it })
        }
    }

    fun import() {
        viewModelScope.launch {
            _uiState.value.rows
                .filter { it.readyToImport }
                .forEach { rowState ->
                    val fund = requireNotNull(rowState.matchedFund)
                    transactionRepository.upsertFund(fund)
                    rowState.occasions.forEach { occasion ->
                        transactionRepository.addTransaction(
                            Transaction(
                                fundId = fund.fundId,
                                type = TransactionType.KOP,
                                epochDay = occasion.date.toEpochDay(),
                                shares = requireNotNull(occasion.shares),
                                pricePerShare = rowState.row.averageCostPerShare,
                            ),
                        )
                    }
                }
            _uiState.update { it.copy(imported = true) }
        }
    }
}
