package se.partee71.fonder.ui.imports

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.partee71.fonder.data.imports.AvrakningsnotaPdfParser
import se.partee71.fonder.data.imports.ImportFileReader
import se.partee71.fonder.data.imports.PdfTextExtractor
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.data.repository.isPriceStale
import se.partee71.fonder.data.repository.refreshFund
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.ImportedOrderTransaction
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.ImportFundMatcher
import se.partee71.fonder.domain.usecase.RecordTransaction
import se.partee71.fonder.domain.usecase.SwedishNumberFormat
import se.partee71.fonder.domain.usecase.SwitchChoicesSource
import se.partee71.fonder.domain.usecase.SwitchDecision
import se.partee71.fonder.domain.usecase.SwitchImportRow
import se.partee71.fonder.domain.usecase.SwitchOption
import se.partee71.fonder.domain.usecase.SwitchQuestion
import se.partee71.fonder.domain.usecase.SwitchQuestions
import se.partee71.fonder.ui.transaktioner.SwitchFieldUi
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
    /** Sant om ett försök att uppdatera fondens kurscache under import misslyckades (issue #19) — importen fortsätter ändå, men markerad, inte tyst. */
    val priceFetchFailed: Boolean = false,
    /** Användarens svar på bytesväljaren (BYT-2) — null = förvalet gäller. Ett köp som avslutar en säljrads bevakning bär i stället [switchSaleRow]. */
    val switchChoice: SwitchOption? = null,
    /** Säljraden vars bevakning köpet avslutar ([SwitchDecision.ClosesWatchOfEntry]) — pekas ut med raden, inte med ett index som flyttar när rader väljs bort. */
    val switchSaleRow: ImportedOrderTransaction? = null,
    /** Bytesväljaren för raden; null för en rad som inte importeras eller medan väljarens indata läses. */
    val switchField: SwitchFieldUi? = null,
) {
    val readyToImport: Boolean
        get() = included && matchedFund != null &&
            SwedishNumberFormat.parse(sharesText) != null && SwedishNumberFormat.parse(priceText) != null

    /** Raden importeras men bytesfrågan saknar svar — importen är då släckt (BYT-2). */
    val switchUnanswered: Boolean get() = readyToImport && switchField?.unanswered == true

    /** Med i bytesväljarens beräkning: raden ska importeras och har en fond. */
    internal val switchCandidate: Boolean get() = included && matchedFund != null
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
    /**
     * Säljrader vars bevakning ([SwitchDecision.LinksWatch]) inte kunde kopplas — den hann kopplas
     * eller stängas av något annat. Transaktionerna är sparade; antalet sägs i kvittot.
     */
    val linkFailedCount: Int = 0,
    /** Importen pågår — knappen ska vara släckt, se [ImportOrdersViewModel.import]. */
    val importing: Boolean = false,
    /** Bytesväljarens indata är lästa — utan dem går ingen fråga att ställa, och importen väntar. */
    val switchReady: Boolean = false,
) {
    val canImport: Boolean
        get() = rows.any { it.readyToImport } && rows.none { it.switchUnanswered } &&
            rows.none { it.readyToImport && switchSaleRowSkipped(it) } && switchReady && !importing

    /**
     * Sant när [row]s svar är att avsluta bytet som en säljrad i samma import startar, men den
     * säljraden inte importeras (bortvald eller med ett fält som inte går att tolka) — importen är
     * då släckt i stället för att köpet tyst sparas som "Inget byte".
     */
    fun switchSaleRowSkipped(row: ImportOrderRowUiState): Boolean {
        val entryIndex = ((row.switchField?.option as? SwitchOption.Decide)?.decision as? SwitchDecision.ClosesWatchOfEntry)?.entryIndex
            ?: return false
        val sale = rows.filter { it.switchCandidate }.getOrNull(entryIndex) ?: return true
        return !sale.readyToImport
    }

    /** Någon rad har en bytesfråga — skärmen förklarar då att säljen importeras före köpen. */
    val hasSwitchQuestions: Boolean get() = rows.any { it.switchField?.hidden == false }
}

/**
 * Importerar exakta fondtransaktioner från Handelsbankens PDF-avräkningsnotor (order-
 * bekräftelser), en eller flera samtidigt (issue #8-uppföljning). Till skillnad från
 * [ImportHoldingsViewModel] (en aggregerad innehavssnapshot med uppskattat inköpsdatum) är
 * datum/kurs/antal här redan exakta — bara fondmatchningen kan behöva bekräftas/rättas.
 *
 * Varje rad har **bytesväljaren** (BYT-2, issue #144) med samma regler som formuläret
 * ([SwitchQuestions.forImport]). Raderna visas och skrivs **sälj före köp**, så ett köp kan avsluta
 * den bevakning en säljrad i samma import startar ([SwitchDecision.ClosesWatchOfEntry]).
 */
@HiltViewModel
class ImportOrdersViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val pdfTextExtractor: PdfTextExtractor,
    private val recordTransaction: RecordTransaction,
    switchChoicesSource: SwitchChoicesSource,
    private val fileReader: ImportFileReader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportOrdersUiState())
    val uiState: StateFlow<ImportOrdersUiState> = _uiState.asStateFlow()

    private var switchInputs: SwitchChoicesSource.Inputs? = null

    init {
        switchChoicesSource.observe().onEach { inputs ->
            switchInputs = inputs
            _uiState.update { it.withSwitchFields() }
        }.launchIn(viewModelScope)
    }

    /** Pågående import — se [import]. */
    private var importJob: Job? = null

    /** PDF-filerna användaren valt i filväljaren (SAF). Filer som inte går att öppna hoppas över; går ingen att öppna händer inget. */
    fun onFilesSelected(uris: List<Uri>) {
        viewModelScope.launch {
            val files = uris.mapNotNull { uri -> fileReader.readNamed(uri) }
            if (files.isNotEmpty()) onFilesRead(files)
        }
    }

    /** [files] är (filnamn, filinnehåll) för varje vald PDF, lästa av [onFilesSelected]. */
    internal fun onFilesRead(files: List<Pair<String, ByteArray>>) {
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

            // Tom katalog vid hämtningsfel — se motsvarande kommentar i ImportHoldingsViewModel.
            val catalog = fundPriceRepository.fetchFundCatalog() ?: FundCatalog(companies = emptyList(), funds = emptyList())
            val trackedFunds = transactionRepository.observeFunds().first()
            // Sälj före köp (IMP-6): ett köp kan bara avsluta en bevakning som en säljrad före den startar.
            val rows = transactions
                .sortedWith(compareBy({ it.type != TransactionType.SALJ }, { it.epochDay }))
                .map { tx -> buildRowState(tx, catalog, trackedFunds) }

            _uiState.update {
                it.copy(loading = false, rows = rows, catalogFunds = catalog.funds, unparsedFileNames = unparsedFileNames).withSwitchFields()
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
            lookupIsin = fundPriceRepository::lookupIsin,
        )

        // Till skillnad från ImportHoldingsViewModel hämtade det här flödet tidigare aldrig
        // någon kurs alls vid import (issue #19) — en importerad fond saknade därför cachad
        // kurs helt tills något annat (Fonddetaljs init-block, eller nästa dagliga
        // FundPriceUpdateWorker-körning) råkade hämta den, så Portfölj/Hem inte visade något
        // förrän fonden öppnats separat. Samma "bara om inaktuell"-princip och käll-gren
        // (ISIN vs Handelsbanken-FundId) som Excel-flödet nu använder.
        var priceFetchFailed = false
        if (match != null && fundPriceRepository.isPriceStale(match.fund.fundId)) {
            priceFetchFailed = !fundPriceRepository.refreshFund(match.fund, LocalDate.ofEpochDay(transaction.epochDay))
        }

        return ImportOrderRowUiState(
            transaction = transaction,
            matchedFund = match?.fund,
            matchConfidence = match?.confidence,
            date = LocalDate.ofEpochDay(transaction.epochDay),
            sharesText = transaction.shares.toString(),
            priceText = transaction.pricePerShare.toString(),
            priceFetchFailed = priceFetchFailed,
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

    // Andelar och kurs påverkar inte bytesfrågorna — de räknas inte om vid varje tangenttryck.
    fun onSharesTextChange(transaction: ImportedOrderTransaction, text: String) {
        updateRow(transaction, recomputeSwitch = false) { it.copy(sharesText = text) }
    }

    fun onPriceTextChange(transaction: ImportedOrderTransaction, text: String) {
        updateRow(transaction, recomputeSwitch = false) { it.copy(priceText = text) }
    }

    /** Svaret på radens bytesfråga. Ett köp som avslutar en säljrads bevakning sparas som en pekare på säljraden. */
    fun onSwitchSelected(transaction: ImportedOrderTransaction, option: SwitchOption) {
        val candidates = _uiState.value.rows.filter { it.switchCandidate }
        val saleRow = ((option as? SwitchOption.Decide)?.decision as? SwitchDecision.ClosesWatchOfEntry)
            ?.let { candidates.getOrNull(it.entryIndex)?.transaction }
        updateRow(transaction) {
            if (saleRow != null) it.copy(switchChoice = null, switchSaleRow = saleRow) else it.copy(switchChoice = option, switchSaleRow = null)
        }
    }

    /** [recomputeSwitch] bara när det som frågorna beror på ändrats: med/utan, fond, datum eller ett svar. */
    private fun updateRow(
        transaction: ImportedOrderTransaction,
        recomputeSwitch: Boolean = true,
        transform: (ImportOrderRowUiState) -> ImportOrderRowUiState,
    ) {
        _uiState.update { state ->
            val updated = state.copy(rows = state.rows.map { if (it.transaction == transaction) transform(it) else it })
            if (recomputeSwitch) updated.withSwitchFields() else updated
        }
    }

    /** Bytesväljarens frågor för [rows] (i importordningen), med användarens val översatta till index i just den listan. */
    private fun switchQuestions(inputs: SwitchChoicesSource.Inputs, rows: List<ImportOrderRowUiState>): List<SwitchQuestion> =
        SwitchQuestions.forImport(
            inputs,
            rows.map { SwitchImportRow(it.transaction.type, it.transaction.isin, it.date.toEpochDay()) },
            rows.map { row -> chosenFor(row, rows) },
        )

    private fun chosenFor(row: ImportOrderRowUiState, rows: List<ImportOrderRowUiState>): SwitchOption? =
        row.switchSaleRow
            ?.let { sale -> rows.indexOfFirst { it.transaction == sale }.takeIf { it >= 0 } }
            ?.let { SwitchOption.Decide(SwitchDecision.ClosesWatchOfEntry(it)) }
            ?: row.switchChoice

    /** Ett avslut av en säljrads bevakning pekar på raden bland [candidates]; i skrivningen på dess plats bland [ready]. */
    private fun toEntryIndices(
        decision: SwitchDecision,
        candidates: List<ImportOrderRowUiState>,
        ready: List<ImportOrderRowUiState>,
    ): SwitchDecision {
        if (decision !is SwitchDecision.ClosesWatchOfEntry) return decision
        val sale = candidates.getOrNull(decision.entryIndex) ?: return SwitchDecision.None
        val entryIndex = ready.indexOfFirst { it.transaction == sale.transaction }
        return if (entryIndex >= 0) SwitchDecision.ClosesWatchOfEntry(entryIndex) else SwitchDecision.None
    }

    /** Räknar om varje rads bytesväljare — ett köps alternativ beror på vad säljraderna före det svarat. */
    private fun ImportOrdersUiState.withSwitchFields(): ImportOrdersUiState {
        val inputs = switchInputs ?: return this
        val candidates = rows.filter { it.switchCandidate }
        val questions = switchQuestions(inputs, candidates)
        val fields = candidates.mapIndexed { index, row ->
            row.transaction to SwitchFieldUi.of(row.transaction.type, questions[index], chosenFor(row, candidates), inputs, followed = null) { saleIndex ->
                candidates.getOrNull(saleIndex)?.transaction?.fundName
            }
        }.toMap()
        return copy(switchReady = true, rows = rows.map { it.copy(switchField = fields[it.transaction]) })
    }

    /**
     * Idempotent under pågående import — se [ImportHoldingsViewModel.import] för varför. Raderna
     * skrivs via [RecordTransaction] (BYT-2) i **ett** anrop (fonderna läses en gång, inte per
     * rad, och planen räknas om en gång), sälj före köp, var och en med sitt svar på
     * bytesväljaren. Frågorna räknas över samma rader som skärmen visar, och ett köps
     * [SwitchDecision.ClosesWatchOfEntry] översätts till säljradens plats i anropet. Ett "ja" på frågan om en
     * okopplad bevakning ([SwitchDecision.LinksWatch]) skrivs i samma pass, så ett köp efter raden ser
     * länken; en koppling som inte gjordes räknas i [ImportOrdersUiState.linkFailedCount].
     */
    fun import() {
        if (importJob?.isActive == true || !_uiState.value.canImport) return
        val inputs = switchInputs ?: return
        _uiState.update { it.copy(importing = true) }
        importJob = viewModelScope.launch {
            // Frågorna räknas över samma rader som skärmen visar dem för; ett köps
            // ClosesWatchOfEntry översätts sedan till säljradens plats bland de rader som skrivs.
            // canImport har redan släckt importen om en sådan säljrad inte skrivs.
            val rows = _uiState.value.rows
            val candidates = rows.filter { it.switchCandidate }
            val questions = switchQuestions(inputs, candidates)
            val ready = rows.filter { it.readyToImport }
            val options = ready.map { row ->
                val index = candidates.indexOfFirst { it.transaction == row.transaction }
                questions[index].resolve(chosenFor(row, candidates))
            }
            val entries = ready.mapIndexed { index, rowState ->
                val fund = requireNotNull(rowState.matchedFund)
                transactionRepository.upsertFund(fund.copy(isin = rowState.transaction.isin))
                RecordTransaction.Entry(
                    Transaction(
                        fundId = fund.fundId,
                        type = rowState.transaction.type,
                        epochDay = rowState.date.toEpochDay(),
                        shares = requireNotNull(SwedishNumberFormat.parse(rowState.sharesText)),
                        pricePerShare = requireNotNull(SwedishNumberFormat.parse(rowState.priceText)),
                    ),
                    options[index]?.decision?.let { decision -> toEntryIndices(decision, candidates, ready) } ?: SwitchDecision.None,
                )
            }
            val recorded = recordTransaction.recordAll(entries)
            val linkFailed = entries.indices.count { entries[it].decision is SwitchDecision.LinksWatch && recorded[it].watchId == null }
            _uiState.update { it.copy(imported = true, importing = false, linkFailedCount = linkFailed) }
        }
    }
}
