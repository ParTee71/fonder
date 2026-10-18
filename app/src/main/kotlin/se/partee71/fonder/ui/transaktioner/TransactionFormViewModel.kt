package se.partee71.fonder.ui.transaktioner

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FollowedSuggestion
import se.partee71.fonder.domain.usecase.FollowedSuggestionLookup
import se.partee71.fonder.domain.usecase.RecordTransaction
import se.partee71.fonder.domain.usecase.SwedishNumberFormat
import se.partee71.fonder.domain.usecase.SwitchChoicesSource
import se.partee71.fonder.domain.usecase.SwitchDecision
import se.partee71.fonder.domain.usecase.SwitchOption
import se.partee71.fonder.domain.usecase.SwitchQuestions
import se.partee71.fonder.domain.usecase.TransactionFormValidator
import se.partee71.fonder.domain.usecase.TransactionPrefill
import se.partee71.fonder.ui.navigation.TransactionFormKey
import java.time.LocalDate

/**
 * Inledningen i säljformuläret från Följ (BYT-2): vilket förslag säljet registreras för.
 * [planIndex] null för ett billigare alternativ (ANA-9); [amountKr] null för en rad utan belopp.
 */
data class FollowIntro(val planIndex: Int?, val buyFundName: String, val amountKr: Double?)

@Immutable
data class TransactionFormUiState(
    val funds: ImmutableList<Fund> = persistentListOf(),
    val selectedFund: Fund? = null,
    val type: TransactionType = TransactionType.KOP,
    val date: LocalDate = LocalDate.now(),
    val sharesText: String = "",
    val priceText: String = "",
    val feeText: String = "",
    /** Fälten är giltiga (fond, antal, kurs, datum, avgift) — bytesfrågan räknas i [canSave]. */
    val valid: Boolean = false,
    /**
     * Transaktionen är sparad — en händelse till skärmen, som går tillbaka och kvitterar med
     * [TransactionFormViewModel.onSavedHandled], så att navigeringen sker exakt en gång (även vid rotation).
     */
    val saved: Boolean = false,
    /**
     * Transaktionen sparades, men bevakningen som skulle kopplas ([SwitchDecision.LinksWatch]) hann
     * kopplas eller stängas av något annat — det sägs rakt ut, och Spara är släckt: säljet finns.
     */
    val linkFailed: Boolean = false,
    /** Förifyllt från Följ eller "Köpte den här" ([TransactionFormKey.prefilled]) — rubrik och sparknapp följer typen. */
    val prefilled: Boolean = false,
    /** Fonden kom ur nyckeln och är låst. */
    val fundLocked: Boolean = false,
    /** Typen kom ur nyckeln och är låst. */
    val typeLocked: Boolean = false,
    /** Nyckelns fond gick inte att slå upp — fondväljaren är reserven. */
    val fundLookupFailed: Boolean = false,
    /** Nyckelns fond slås upp — fältet är låst och säger det, så inget val hinner göras och skrivas över. */
    val fundLoading: Boolean = false,
    val followIntro: FollowIntro? = null,
    /** Beloppet andelarna förifylldes ur — anteckningen under fälten. Null utan förifyllning. */
    val prefillAmountKr: Double? = null,
    /** Bytesväljaren (BYT-2); null medan dess indata läses. */
    val switchField: SwitchFieldUi? = null,
) {
    /** Spara kräver giltiga fält **och** ett svar på bytesfrågan (BYT-2: släckt tills en fråga utan förval besvarats). */
    val canSave: Boolean get() = valid && switchField != null && !switchField.unanswered && !linkFailed

    /** Spara är släckt bara för att bytesfrågan saknar svar — skärmen säger det. */
    val blockedBySwitch: Boolean get() = valid && switchField?.unanswered == true
}

private data class FormFields(
    val fund: Fund?,
    val type: TransactionType,
    val date: LocalDate,
    val sharesText: String,
    val priceText: String,
    val feeText: String,
)

/** Det förifyllda lägets flaggor ur nyckeln — låst och laddande från start, löses av uppslaget. */
private data class PrefillState(
    val fundLocked: Boolean = false,
    val fundLoading: Boolean = false,
    val fundLookupFailed: Boolean = false,
    val prefillAmountKr: Double? = null,
)

/**
 * Formulär för att registrera en fondtransaktion (NAV-4, issue #4). Sparar via [RecordTransaction]
 * (BYT-2) med svaret på **bytesväljaren**: alternativ och förval ur [SwitchQuestions] (samma
 * regler som PDF-importen), och Spara släckt tills en fråga utan förval besvarats.
 *
 * Med argument i [key] förifylls formuläret (issue #144): Följ ger säljet för ett förslag med
 * fond och typ låsta, dagens datum, senaste kursen och andelar = belopp ÷ kurs; "Köpte den här"
 * ger köpet som avslutar bevakningen. En fond som bara är känd på ISIN (en kandidat appen inte
 * bevakar) slås upp med [FundPriceRepository.findFundByIsin] och läggs till när köpet sparas —
 * går den inte att slå upp är fondväljaren reserven.
 */
@HiltViewModel(assistedFactory = TransactionFormViewModel.Factory::class)
class TransactionFormViewModel @AssistedInject constructor(
    @Assisted private val key: TransactionFormKey,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val recordTransaction: RecordTransaction,
    switchChoicesSource: SwitchChoicesSource,
    private val followedSuggestionLookup: FollowedSuggestionLookup,
) : ViewModel() {

    private val funds = MutableStateFlow<List<Fund>>(emptyList())

    /** Fonden ur nyckeln när den inte bevakas än (uppslagen på ISIN) — läggs till först när transaktionen sparas. */
    private val lookedUpFund = MutableStateFlow<Fund?>(null)
    private val selectedFund = MutableStateFlow<Fund?>(null)
    private val type = MutableStateFlow(key.type ?: TransactionType.KOP)
    private val date = MutableStateFlow(LocalDate.now())
    private val sharesText = MutableStateFlow("")
    private val priceText = MutableStateFlow("")
    private val feeText = MutableStateFlow("")
    private val saved = MutableStateFlow(false)
    private val linkFailed = MutableStateFlow(false)
    // Låst från start när nyckeln pekar ut en fond: annars hann ett val i fondväljaren göras
    // under uppslaget och skrivas över av det.
    private val prefill = MutableStateFlow(PrefillState(fundLocked = key.prefilled, fundLoading = key.prefilled))

    /** Följs förslag uppslaget på radens id — reserven när det inte står i Besluts källa ([SwitchQuestions.followed]). */
    private val lookedUpFollow = MutableStateFlow<FollowedSuggestion?>(null)
    private val switchInputs = MutableStateFlow<SwitchChoicesSource.Inputs?>(null)

    /** Användarens svar på bytesfrågan — null = inget val gjort, förvalet gäller. Nollas vid fond- och typbyte. */
    private val chosenSwitch = MutableStateFlow<SwitchOption?>(null)

    /** Pågående kursuppslag för vald fond — avbryts vid fondbyte, se [onFundSelected]. */
    private var priceLookupJob: Job? = null

    /** Pågående sparning — se [save]. */
    private var saveJob: Job? = null

    // combine() saknar en 6-parametersvariant — kombinerar därför i två steg.
    private val baseFields = combine(selectedFund, type, date) { fund, type, date -> Triple(fund, type, date) }
    private val amountFields = combine(sharesText, priceText, feeText) { shares, price, fee -> Triple(shares, price, fee) }
    private val formFields = combine(baseFields, amountFields) { (fund, type, date), (shares, price, fee) ->
        FormFields(fund, type, date, shares, price, fee)
    }
    private val shownFunds = combine(funds, lookedUpFund) { tracked, extra ->
        if (extra == null || tracked.any { it.fundId == extra.fundId }) tracked else tracked + extra
    }
    private val switchState = combine(switchInputs, chosenSwitch, lookedUpFollow, linkFailed) { inputs, chosen, lookedUp, linkFailed ->
        SwitchState(inputs, chosen, lookedUp, linkFailed)
    }

    private data class SwitchState(
        val inputs: SwitchChoicesSource.Inputs?,
        val chosen: SwitchOption?,
        val lookedUp: FollowedSuggestion?,
        val linkFailed: Boolean,
    )

    val uiState: StateFlow<TransactionFormUiState> =
        combine(shownFunds, formFields, saved, prefill, switchState) { funds, fields, saved, prefill, (inputs, chosen, lookedUp, linkFailed) ->
            val shares = SwedishNumberFormat.parse(fields.sharesText)
            val price = SwedishNumberFormat.parse(fields.priceText)
            val fee = parseFee(fields.feeText)
            TransactionFormUiState(
                funds = funds.toImmutableList(),
                selectedFund = fields.fund,
                type = fields.type,
                date = fields.date,
                sharesText = fields.sharesText,
                priceText = fields.priceText,
                feeText = fields.feeText,
                valid = TransactionFormValidator.isValid(fields.fund?.fundId, shares, price, fields.date, fee),
                saved = saved,
                linkFailed = linkFailed,
                prefilled = key.prefilled,
                fundLocked = prefill.fundLocked,
                typeLocked = key.type != null,
                fundLookupFailed = prefill.fundLookupFailed,
                fundLoading = prefill.fundLoading,
                followIntro = inputs?.let { followIntro(followed(it, lookedUp), prefill.prefillAmountKr) },
                prefillAmountKr = prefill.prefillAmountKr,
                switchField = inputs?.let { switchField(it, lookedUp, fields.fund, fields.type, fields.date, chosen) },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TransactionFormUiState(
                type = key.type ?: TransactionType.KOP,
                prefilled = key.prefilled,
                typeLocked = key.type != null,
                fundLocked = key.prefilled,
                fundLoading = key.prefilled,
            ),
        )

    init {
        transactionRepository.observeFunds().onEach { funds.value = it }.launchIn(viewModelScope)
        switchChoicesSource.observe().onEach { switchInputs.value = it }.launchIn(viewModelScope)
        if (key.prefilled) viewModelScope.launch { prefillFromKey() }
    }

    /**
     * Fond, kurs och andelar ur nyckeln. Fonden söks bland de bevakade på id och sedan på ISIN,
     * och slås annars upp på ISIN — aldrig på namn. Andelarna räknas ur beloppet (nyckelns, annars
     * förslagets eget) och senaste kursen; saknas någon av dem lämnas fältet tomt.
     */
    private suspend fun prefillFromKey() {
        val lookedUp = key.suggestionRecordId?.let { followedSuggestionLookup.lookup(it) }
        lookedUpFollow.value = lookedUp
        val tracked = transactionRepository.observeFunds().first()
        val fund = tracked.firstOrNull { key.fundId != null && it.fundId == key.fundId }
            ?: tracked.firstOrNull { key.fundIsin != null && it.isin == key.fundIsin }
            ?: key.fundIsin?.let { isin -> fundPriceRepository.findFundByIsin(isin)?.copy(isin = isin) }?.also { lookedUpFund.value = it }
        if (fund == null) {
            prefill.value = PrefillState(fundLookupFailed = true)
            return
        }
        // Fältet har varit låst sedan start, så inget användarval finns att skriva över.
        if (selectedFund.value == null) selectedFund.value = fund
        prefill.value = PrefillState(fundLocked = true)
        val amount = key.prefillAmountKr ?: key.suggestionRecordId?.let { recordId ->
            SwitchQuestions.followed(switchInputs.filterNotNull().first(), recordId, lookedUp)?.switchValueKr
        }
        val nav = fundPriceRepository.latestPrice(fund.fundId)?.nav
        nav?.let { priceText.value = it.toString() }
        val shares = TransactionPrefill.sharesForAmount(amount, nav)
        shares?.let { sharesText.value = SwedishNumberFormat.format(it, TransactionPrefill.SHARE_DECIMALS) }
        prefill.value = PrefillState(fundLocked = true, prefillAmountKr = amount.takeIf { shares != null })
    }

    private fun followed(inputs: SwitchChoicesSource.Inputs, lookedUp: FollowedSuggestion?): FollowedSuggestion? =
        key.suggestionRecordId?.let { SwitchQuestions.followed(inputs, it, lookedUp) }

    private fun followIntro(followed: FollowedSuggestion?, amountKr: Double?): FollowIntro? =
        followed?.let { FollowIntro(planIndex = it.planIndex, buyFundName = it.buyFundName, amountKr = amountKr ?: it.switchValueKr) }

    private fun question(inputs: SwitchChoicesSource.Inputs, lookedUp: FollowedSuggestion?, fund: Fund?, type: TransactionType, date: LocalDate) =
        SwitchQuestions.forTransaction(inputs, type, fund?.isin, date.toEpochDay(), followed(inputs, lookedUp), key.watchId)

    private fun switchField(
        inputs: SwitchChoicesSource.Inputs,
        lookedUp: FollowedSuggestion?,
        fund: Fund?,
        type: TransactionType,
        date: LocalDate,
        chosen: SwitchOption?,
    ) = SwitchFieldUi.of(type, question(inputs, lookedUp, fund, type, date), chosen, inputs, followed(inputs, lookedUp))

    /**
     * Byter fond och förifyller kursen. Fältet **nollas först**, och ett pågående uppslag
     * avbryts: annars stod föregående fonds NAV kvar när den nya fonden saknar cachad kurs
     * (eller skrevs över av ett långsamt uppslag som landade efter bytet), och transaktionen
     * kunde sparas på fel fonds kurs — ett tyst fel i GAV, realiserat resultat och analys.
     * Bytesvalet nollas också: det gällde den förra fonden.
     */
    fun onFundSelected(fund: Fund) {
        if (prefill.value.fundLocked) return
        priceLookupJob?.cancel()
        selectedFund.value = fund
        chosenSwitch.value = null
        priceText.value = ""
        priceLookupJob = viewModelScope.launch {
            fundPriceRepository.latestPrice(fund.fundId)?.let { priceText.value = it.nav.toString() }
        }
    }

    fun onTypeChange(newType: TransactionType) {
        if (key.type != null || newType == type.value) return
        type.value = newType
        chosenSwitch.value = null
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

    fun onSwitchSelected(option: SwitchOption) {
        chosenSwitch.value = option
    }

    /**
     * Idempotent under pågående sparning — två snabba tryck ska inte ge två transaktioner. Sparar
     * inte utan svar på bytesfrågan. "Ja" på frågan om en okopplad bevakning skrivs som
     * [SwitchDecision.LinksWatch] i samma anrop; gjordes kopplingen inte (bevakningen hann kopplas
     * eller stängas) sägs det ([TransactionFormUiState.linkFailed]) i stället för att formuläret
     * stängs som om allt gått bra.
     */
    fun save() {
        val fund = selectedFund.value ?: return
        val shares = SwedishNumberFormat.parse(sharesText.value) ?: return
        val price = SwedishNumberFormat.parse(priceText.value) ?: return
        val fee = parseFee(feeText.value) ?: return
        if (!TransactionFormValidator.isValid(fund.fundId, shares, price, date.value, fee)) return
        val inputs = switchInputs.value ?: return
        val option = question(inputs, lookedUpFollow.value, fund, type.value, date.value).resolve(chosenSwitch.value) ?: return
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            if (lookedUpFund.value?.fundId == fund.fundId && funds.value.none { it.fundId == fund.fundId }) {
                transactionRepository.upsertFund(fund)
            }
            val transaction = Transaction(
                fundId = fund.fundId,
                type = type.value,
                epochDay = date.value.toEpochDay(),
                shares = shares,
                pricePerShare = price,
                fee = fee,
            )
            val recorded = recordTransaction.record(RecordTransaction.Entry(transaction, option.decision))
            if (option.decision is SwitchDecision.LinksWatch && recorded.watchId == null) {
                linkFailed.value = true
            } else {
                saved.value = true
            }
        }
    }

    /** Kvitterar [TransactionFormUiState.saved] när skärmen gått tillbaka, så att det inte upprepas. */
    fun onSavedHandled() {
        saved.value = false
    }

    /** Tomt avgiftsfält tolkas som 0.0 (ingen känd avgift) — annars måste texten vara ett giltigt tal. */
    private fun parseFee(text: String): Double? = if (text.isBlank()) 0.0 else SwedishNumberFormat.parse(text)

    /** Skapar ViewModeln för en given nyckel (assisted injection, `hiltViewModel` med `creationCallback`). */
    @AssistedFactory
    interface Factory {
        fun create(key: TransactionFormKey): TransactionFormViewModel
    }
}
