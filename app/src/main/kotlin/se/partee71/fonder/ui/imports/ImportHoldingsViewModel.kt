package se.partee71.fonder.ui.imports

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.partee71.fonder.data.common.AppErrors
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.data.imports.HoldingsImportParser
import se.partee71.fonder.data.imports.ImportFileReader
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.data.repository.isPriceStale
import se.partee71.fonder.data.repository.refreshFund
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.ImportedHoldingRow
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.FundNameMatcher
import se.partee71.fonder.domain.usecase.ImportFundMatcher
import se.partee71.fonder.domain.usecase.PurchaseDateEstimator
import se.partee71.fonder.domain.usecase.RecordTransaction
import se.partee71.fonder.domain.usecase.SwedishNumberFormat
import se.partee71.ui.common.UiError

private const val SHARES_MATCH_TOLERANCE = 1e-6

/**
 * Hur långt tillbaka kurshistoriken hämtas/söks vid import — se KRAVLISTA (TP-13/TP-18).
 *
 * Var tidigare två fönster: fem år för fonder utan ISIN (troddes vara Handelsbankens fasta
 * tak) och trettio för ISIN-matchade (Avanza). Femårstaket fanns aldrig i källan — fondlista
 * levererar hela fondens historik i ett anrop (issue #37) — så bägge flödena använder nu
 * samma djupa fönster, och `PurchaseDateEstimator` har samma chans att hitta äldre köp oavsett
 * hur fonden matchades.
 */
private const val PRICE_HISTORY_YEARS = 30L

/**
 * Ett enskilt inköpstillfälle för en importerad rad (issue #8-uppföljning: en rad i
 * exporten är ofta en aggregerad post byggd av flera köp vid olika tillfällen — hela
 * innehavet behöver därför kunna delas upp i flera transaktioner med egna datum/antal,
 * i stället för att tvinga in allt i en enda syntetisk transaktion).
 *
 * [dateEstimated] (IMP-2, issue #222): datumet är importens uppskattning — ur kurshistoriken
 * ([PurchaseDateEstimator]), reservdatumet eller dagens datum för ett tillagt tillfälle — och inte
 * valt av användaren. Följer med till [Transaction.dateEstimated], så transaktionen kan märkas
 * "uppskattat datum" tills någon rättar det. Skilt från [dateConfident], som bara säger hur säker
 * uppskattningen är: även en säker uppskattning är en uppskattning.
 */
data class ImportOccasion(
    val date: LocalDate,
    val dateConfident: Boolean,
    val dateEstimated: Boolean,
    val sharesText: String,
    // Standard Köp — exportens innehav är i praktiken alltid köpta andelar, men en importrad
    // kan sättas till Sälj (t.ex. en manuellt tillagd rad för en avyttring) i stället för att
    // alltid hårdkodas till köp (issue #10, KRAVLISTA IMP-5).
    val type: TransactionType = TransactionType.KOP
) {
    val shares: Double? get() = SwedishNumberFormat.parse(sharesText)
}

/** En importerad rad + användarens (eventuellt korrigerade) matchning/inköpstillfällen (issue #8). */
@Immutable
data class ImportRowUiState(
    val row: ImportedHoldingRow,
    val matchedFund: Fund?,
    val matchConfidence: Double?,
    val occasions: ImmutableList<ImportOccasion>,
    val included: Boolean = true,
    /** Sant om ett försök att uppdatera fondens kurscache under import misslyckades (issue #19) — importen fortsätter ändå, men markerad, inte tyst. */
    val priceFetchFailed: Boolean = false
) {
    val occasionSharesTotal: Double get() = occasions.sumOf { it.shares ?: 0.0 }
    val sharesMismatch: Boolean get() = abs(occasionSharesTotal - row.shares) > SHARES_MATCH_TOLERANCE

    private val occasionsValid: Boolean
        get() = occasions.isNotEmpty() && occasions.all { (it.shares ?: 0.0) > 0.0 } && !sharesMismatch

    val readyToImport: Boolean get() = included && matchedFund != null && occasionsValid
}

@Immutable
data class ImportHoldingsUiState(
    val loading: Boolean = false,
    val fileSelected: Boolean = false,
    val rows: ImmutableList<ImportRowUiState> = persistentListOf(),
    val catalogFunds: ImmutableList<Fund> = persistentListOf(),
    val imported: Boolean = false,
    /** Filen gick inte att läsa eller var tom – [DataError.ImportUnreadable] eller [DataError.ImportEmpty] via [AppErrors] (NFR-6). */
    val error: UiError? = null,
    /** Importen pågår — knappen ska vara släckt, se [ImportHoldingsViewModel.import]. */
    val importing: Boolean = false
) {
    val canImport: Boolean get() = rows.any { it.readyToImport } && !importing
}

/** Användarens handlingar på Importera innehav, till [ImportHoldingsViewModel.onEvent]. */
sealed interface ImportHoldingsEvent {
    /** Filen användaren valt i filväljaren. */
    data class FileSelected(val uri: Uri) : ImportHoldingsEvent

    /** Användaren väljer fond för raden själv. */
    data class FundOverride(val row: ImportedHoldingRow, val fund: Fund?) : ImportHoldingsEvent

    /** Raden tas med i importen eller inte. */
    data class IncludedChange(val row: ImportedHoldingRow, val included: Boolean) : ImportHoldingsEvent

    /** Datumet för ett inköpstillfälle. */
    data class OccasionDateChange(val row: ImportedHoldingRow, val index: Int, val date: LocalDate) : ImportHoldingsEvent

    /** Andelarna för ett inköpstillfälle. */
    data class OccasionSharesChange(val row: ImportedHoldingRow, val index: Int, val sharesText: String) : ImportHoldingsEvent

    /** Transaktionstypen för ett inköpstillfälle. */
    data class OccasionTypeChange(val row: ImportedHoldingRow, val index: Int, val type: TransactionType) : ImportHoldingsEvent

    /** Delar upp raden i ytterligare ett inköpstillfälle. */
    data class AddOccasion(val row: ImportedHoldingRow) : ImportHoldingsEvent

    /** Tar bort ett inköpstillfälle. */
    data class RemoveOccasion(val row: ImportedHoldingRow, val index: Int) : ImportHoldingsEvent

    /** Skriver de importklara raderna. */
    data object Import : ImportHoldingsEvent
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
    private val recordTransaction: RecordTransaction,
    private val fileReader: ImportFileReader
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportHoldingsUiState())
    val uiState: StateFlow<ImportHoldingsUiState> = _uiState.asStateFlow()

    /** Pågående import — se [import]. */
    private var importJob: Job? = null

    fun onEvent(event: ImportHoldingsEvent) {
        when (event) {
            is ImportHoldingsEvent.FileSelected -> onFileSelected(event.uri)
            is ImportHoldingsEvent.FundOverride -> onFundOverride(event.row, event.fund)
            is ImportHoldingsEvent.IncludedChange -> onIncludedChange(event.row, event.included)
            is ImportHoldingsEvent.OccasionDateChange -> onOccasionDateChange(event.row, event.index, event.date)
            is ImportHoldingsEvent.OccasionSharesChange -> onOccasionSharesChange(event.row, event.index, event.sharesText)
            is ImportHoldingsEvent.OccasionTypeChange -> onOccasionTypeChange(event.row, event.index, event.type)
            is ImportHoldingsEvent.AddOccasion -> onAddOccasion(event.row)
            is ImportHoldingsEvent.RemoveOccasion -> onRemoveOccasion(event.row, event.index)
            ImportHoldingsEvent.Import -> import()
        }
    }

    /** Filen användaren valt i filväljaren (Storage Access Framework). Går den inte att öppna händer inget. */
    private fun onFileSelected(uri: Uri) {
        viewModelScope.launch {
            val bytes = fileReader.readBytes(uri) ?: return@launch
            onFileRead(bytes)
        }
    }

    /** [bytes] är hela filens innehåll, läst av [onFileSelected] med en [Uri]. */
    internal fun onFileRead(bytes: ByteArray) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, fileSelected = true, error = null, rows = persistentListOf()) }

            val parsedRows = runCatching {
                HoldingsImportParser.parse(bytes.inputStream())
            }.getOrElse {
                _uiState.update { s -> s.copy(loading = false, error = AppErrors.map(DataError.ImportUnreadable)) }
                return@launch
            }
            if (parsedRows.isEmpty()) {
                _uiState.update { it.copy(loading = false, error = AppErrors.map(DataError.ImportEmpty)) }
                return@launch
            }

            // Tom katalog vid hämtningsfel: raderna matchas då bara mot redan bevakade fonder
            // och användarens egna val, i stället för att importen avbryts (ÖV-6).
            val catalog = fundPriceRepository.fetchFundCatalog() ?: FundCatalog(companies = emptyList(), funds = emptyList())
            val trackedFunds = transactionRepository.observeFunds().first()
            val rowStates = parsedRows.map { row -> buildRowState(row, catalog, trackedFunds) }
            _uiState.update { it.copy(loading = false, rows = rowStates.toImmutableList(), catalogFunds = catalog.funds.toImmutableList()) }
        }
    }

    /** Matchningsordning delad med [se.partee71.fonder.ui.imports.ImportOrdersViewModel] — se [ImportFundMatcher]. */
    private suspend fun matchFund(row: ImportedHoldingRow, catalog: FundCatalog, trackedFunds: List<Fund>): FundNameMatcher.Match? = ImportFundMatcher.match(
        isin = row.isin,
        fundName = row.fundName,
        fundCompanyName = row.fundCompanyName,
        catalogFunds = catalog.funds,
        trackedFunds = trackedFunds,
        findFundByIsin = fundPriceRepository::findFundByIsin,
        lookupIsin = fundPriceRepository::lookupIsin
    )

    private suspend fun buildRowState(row: ImportedHoldingRow, catalog: FundCatalog, trackedFunds: List<Fund>): ImportRowUiState {
        val match = matchFund(row, catalog, trackedFunds)
        val to = LocalDate.now()
        val since = to.minusYears(PRICE_HISTORY_YEARS)

        // Utan en tillförlitlig datumuppskattning (inget kurshistorik-fynd) antas ett gammalt
        // innehav hellre ha köpts för länge sedan än "idag" — samma gräns som kurshistoriken
        // söks inom, så gissningen aldrig hamnar utanför sökfönstret.
        var date = since
        var dateConfident = false
        var priceFetchFailed = false

        if (match != null) {
            val fund = match.fund
            // Hämta bara om kurscachen faktiskt är inaktuell (samma "senaste NAV < 1 dag
            // gammal"-princip som #18) — en redan bevakad fond med färsk kurs har normalt
            // redan full historik cachad sedan tidigare, så en ny hämtning bara gör importet
            // onödigt långsamt utan att ge mer data (issue #19).
            if (fundPriceRepository.isPriceStale(fund.fundId)) {
                // Analysens horisont (issue #214) — här redan sökfönstret på 30 år, som ligger tidigare.
                priceFetchFailed = !fundPriceRepository.refreshFund(fund, FundAnalysisCalc.analysisHistoryStart(since, to))
            }
            val history = fundPriceRepository.priceHistory(fund.fundId, since.toEpochDay(), to.toEpochDay())
            PurchaseDateEstimator.estimate(row.averageCostPerShare, history)?.let { estimate ->
                date = LocalDate.ofEpochDay(estimate.epochDay)
                dateConfident = estimate.confident
            }
        }

        return ImportRowUiState(
            row = row,
            matchedFund = match?.fund,
            matchConfidence = match?.confidence,
            occasions = persistentListOf(ImportOccasion(date = date, dateConfident = dateConfident, dateEstimated = true, sharesText = row.shares.toString())),
            priceFetchFailed = priceFetchFailed
        )
    }

    private fun onFundOverride(row: ImportedHoldingRow, fund: Fund?) {
        updateRow(row) { it.copy(matchedFund = fund, matchConfidence = null) }
    }

    private fun onIncludedChange(row: ImportedHoldingRow, included: Boolean) {
        updateRow(row) { it.copy(included = included) }
    }

    private fun onOccasionDateChange(row: ImportedHoldingRow, index: Int, date: LocalDate) {
        updateOccasion(row, index) { it.copy(date = date, dateConfident = true, dateEstimated = false) }
    }

    private fun onOccasionSharesChange(row: ImportedHoldingRow, index: Int, sharesText: String) {
        updateOccasion(row, index) { it.copy(sharesText = sharesText) }
    }

    private fun onOccasionTypeChange(row: ImportedHoldingRow, index: Int, type: TransactionType) {
        updateOccasion(row, index) { it.copy(type = type) }
    }

    /** Delar upp raden i ytterligare ett inköpstillfälle — kvarstående andelar måste fördelas manuellt. */
    private fun onAddOccasion(row: ImportedHoldingRow) {
        updateRow(row) { it.copy(occasions = (it.occasions + ImportOccasion(date = LocalDate.now(), dateConfident = false, dateEstimated = true, sharesText = "")).toImmutableList()) }
    }

    private fun onRemoveOccasion(row: ImportedHoldingRow, index: Int) {
        updateRow(row) { state ->
            if (state.occasions.size <= 1) return@updateRow state
            state.copy(occasions = state.occasions.filterIndexed { i, _ -> i != index }.toImmutableList())
        }
    }

    private fun updateOccasion(row: ImportedHoldingRow, index: Int, transform: (ImportOccasion) -> ImportOccasion) {
        updateRow(row) { state ->
            state.copy(occasions = state.occasions.mapIndexed { i, occasion -> if (i == index) transform(occasion) else occasion }.toImmutableList())
        }
    }

    private fun updateRow(row: ImportedHoldingRow, transform: (ImportRowUiState) -> ImportRowUiState) {
        _uiState.update { state ->
            state.copy(rows = state.rows.map { if (it.row == row) transform(it) else it }.toImmutableList())
        }
    }

    /**
     * Skriver de importklara raderna. **Idempotent under pågående import:** utan spärren
     * startade ett andra tryck (importen ger ingen synlig återkoppling förrän `imported`
     * slår om, och skrivningarna tar en stund för en fil med många rader) en andra
     * genomkörning av samma lista — varje transaktion lades då in två gånger, med dubbla
     * andelar och dubbelt investerat belopp som följd.
     *
     * Raderna går via [RecordTransaction] (BYT-2) utan bytesväljare: exporten är en historisk
     * ögonblicksbild, inte ett byte.
     */
    private fun import() {
        if (importJob?.isActive == true) return
        _uiState.update { it.copy(importing = true) }
        importJob = viewModelScope.launch {
            val entries = _uiState.value.rows
                .filter { it.readyToImport }
                .flatMap { rowState ->
                    val fund = requireNotNull(rowState.matchedFund)
                    // Exportradens ISIN är auktoritativt (kommer direkt från källfilen, ingen
                    // gissning) — sparas på den matchade fonden så full kurshistorik sedan
                    // köpdatum kan hämtas från ISIN-baserade källor (KRAVLISTA TP-14).
                    transactionRepository.upsertFund(fund.copy(isin = rowState.row.isin))
                    rowState.occasions.map { occasion ->
                        RecordTransaction.Entry(
                            Transaction(
                                fundId = fund.fundId,
                                type = occasion.type,
                                epochDay = occasion.date.toEpochDay(),
                                shares = requireNotNull(occasion.shares),
                                pricePerShare = rowState.row.averageCostPerShare,
                                dateEstimated = occasion.dateEstimated
                            )
                        )
                    }
                }
            recordTransaction.recordAll(entries)
            _uiState.update { it.copy(imported = true, importing = false) }
        }
    }
}
