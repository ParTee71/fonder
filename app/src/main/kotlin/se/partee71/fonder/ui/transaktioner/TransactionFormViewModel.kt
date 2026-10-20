package se.partee71.fonder.ui.transaktioner

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.R
import se.partee71.fonder.data.common.AppErrors
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.data.common.suspendRunCatching
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FollowedSuggestion
import se.partee71.fonder.domain.usecase.FollowedSuggestionLookup
import se.partee71.fonder.domain.usecase.RealizedGainCalculator
import se.partee71.fonder.domain.usecase.RecordTransaction
import se.partee71.fonder.domain.usecase.SwedishNumberFormat
import se.partee71.fonder.domain.usecase.SwitchChoicesSource
import se.partee71.fonder.domain.usecase.SwitchDecision
import se.partee71.fonder.domain.usecase.SwitchOption
import se.partee71.fonder.domain.usecase.SwitchQuestions
import se.partee71.fonder.domain.usecase.TransactionFormValidator
import se.partee71.fonder.domain.usecase.TransactionPrefill
import se.partee71.fonder.ui.navigation.TransactionFormKey
import se.partee71.ui.common.EditorEvents
import se.partee71.ui.common.EditorLoader
import se.partee71.ui.common.EditorState
import se.partee71.ui.common.EditorUiState
import se.partee71.ui.common.UiError
import se.partee71.ui.common.Validator

/**
 * Inledningen i säljformuläret från Följ (BYT-2): vilket förslag säljet registreras för.
 * [planIndex] null för ett billigare alternativ (ANA-9); [amountKr] null för en rad utan belopp.
 */
data class FollowIntro(val planIndex: Int?, val buyFundName: String, val amountKr: Double?)

/**
 * Transaktionsformulärets värde — det [EditorState] håller: fälten och svaret på bytesfrågan
 * ([chosenSwitch], null = inget val gjort, förvalet gäller). Fondlistan, bytesväljarens indata och
 * innehavet hör inte hit; de läses och är inget användaren skriver.
 */
@Immutable
data class TransactionForm(
    val fund: Fund? = null,
    val type: TransactionType = TransactionType.KOP,
    val date: LocalDate = LocalDate.now(),
    val sharesText: String = "",
    val priceText: String = "",
    val feeText: String = "",
    val chosenSwitch: SwitchOption? = null
)

/** Fältnamnen i [EditorState]. */
internal const val TRANSACTION_FUND = "fund"
internal const val TRANSACTION_TYPE = "type"
internal const val TRANSACTION_DATE = "date"
internal const val TRANSACTION_SHARES = "shares"
internal const val TRANSACTION_PRICE = "price"
internal const val TRANSACTION_FEE = "fee"
internal const val TRANSACTION_SWITCH = "switch"

/** Sparad men inte kopplad ([TransactionFormUiState.linkFailed]) — ingen andra sparning. */
internal const val TRANSACTION_LINK = "link"

/** Felen som inte hör till ett fält — de räknas inte i [TransactionFormUiState.valid]. */
private val notFieldErrors = setOf(TRANSACTION_SWITCH, TRANSACTION_LINK)

/** Tomt avgiftsfält tolkas som 0.0 (ingen känd avgift) — annars måste texten vara ett giltigt tal. */
private fun parseFee(text: String): Double? = if (text.isBlank()) 0.0 else SwedishNumberFormat.parse(text)

/**
 * Valideringen av [TransactionForm] (NAV-4, TRX-5) — en gång för ViewModel:n och testerna. Fond,
 * antal, kurs och avgift som [TransactionFormValidator]; ett sälj får inte vara fler andelar än
 * [ownedShares] (null = innehavet är inte läst än, då prövas det inte); bytesfrågan måste ha ett svar
 * ([switchAnswered], BYT-2); och en transaktion som sparats utan sin koppling ([linkFailed]) sparas
 * inte igen.
 */
internal fun transactionFormValidator(
    switchAnswered: (TransactionForm) -> Boolean = { true },
    ownedShares: (fundId: String) -> Double? = { null },
    linkFailed: () -> Boolean = { false }
) = Validator<TransactionForm> { form ->
    buildMap {
        if (form.fund == null) put(TRANSACTION_FUND, R.string.transaktionsform_error_fund)
        val shares = SwedishNumberFormat.parse(form.sharesText)
        val owned = form.fund?.let { ownedShares(it.fundId) }
        when {
            !TransactionFormValidator.isValidShares(shares) -> put(TRANSACTION_SHARES, R.string.transaktionsform_error_shares)

            form.type == TransactionType.SALJ && owned != null && TransactionFormValidator.exceedsHolding(checkNotNull(shares), owned) ->
                put(TRANSACTION_SHARES, R.string.transaktionsform_error_oversell)
        }
        if (!TransactionFormValidator.isValidPrice(SwedishNumberFormat.parse(form.priceText))) put(TRANSACTION_PRICE, R.string.transaktionsform_error_price)
        if (!TransactionFormValidator.isValidFee(parseFee(form.feeText))) put(TRANSACTION_FEE, R.string.transaktionsform_error_fee)
        if (!switchAnswered(form)) put(TRANSACTION_SWITCH, R.string.transaktionsform_save_blocked)
        if (linkFailed()) put(TRANSACTION_LINK, R.string.transaktionsform_link_failed)
    }
}

/**
 * Det transaktionsformuläret visar: formulärets tillstånd ([editor], det `EntityEditScreen` läser –
 * laddning, läsfel, sparning, sparfel, ändrat och giltigt) och det som läses vid sidan av – fonderna,
 * bytesväljaren, förifyllningens flaggor och innehavet i vald fond.
 */
@Immutable
data class TransactionFormUiState(
    val editor: EditorUiState<TransactionForm> = EditorUiState(TransactionForm(), loading = true),
    val funds: ImmutableList<Fund> = persistentListOf(),
    /** Fälten är giltiga (fond, antal, kurs, avgift, inget översälj) — bytesfrågan räknas i [canSave]. */
    val valid: Boolean = false,
    /**
     * Transaktionen sparades, men bevakningen som skulle kopplas ([SwitchDecision.LinksWatch]) hann
     * kopplas eller stängas av något annat — det sägs rakt ut, formuläret stängs inte, och Spara är
     * släckt: säljet finns.
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
    val followIntro: FollowIntro? = null,
    /** Beloppet andelarna förifylldes ur — anteckningen under fälten. Null utan förifyllning. */
    val prefillAmountKr: Double? = null,
    /** Bytesväljaren (BYT-2); null medan dess indata läses. */
    val switchField: SwitchFieldUi? = null,
    /** Andelarna som ägs i vald fond vid sälj (TRX-5); null vid köp, utan fond eller innan innehavet lästs. */
    val ownedShares: Double? = null
) {
    val selectedFund: Fund? get() = editor.value.fund
    val type: TransactionType get() = editor.value.type
    val date: LocalDate get() = editor.value.date
    val sharesText: String get() = editor.value.sharesText
    val priceText: String get() = editor.value.priceText
    val feeText: String get() = editor.value.feeText

    /** Transaktionen sparas just nu — Spara är släckt, så ett dubbeltryck inte sparar två gånger. */
    val saving: Boolean get() = editor.saving

    /** Sparningen misslyckades — ramen visar det som snackbar, och formuläret står kvar med inmatningen (NFR-6). */
    val failure: UiError? get() = editor.failure

    /** Transaktionen är sparad — ramen går tillbaka och kvitterar ([EditorEvents.onDoneHandled]). */
    val saved: Boolean get() = editor.done

    /** Spara: giltiga fält, ett svar på bytesfrågan, ändrat (eller förifyllt) och inget som sparas eller läses. */
    val canSave: Boolean get() = editor.canSave

    /** Spara är släckt bara för att bytesfrågan saknar svar — skärmen säger det. */
    val blockedBySwitch: Boolean get() = valid && switchField?.unanswered == true

    /**
     * Inga bevakade fonder att välja bland (TRX-6) — ett formulär som aldrig kan sparas; skärmen
     * visar i stället ett tomt läge med vägen till fondsök. Inte förifyllt med en fond som slagits upp.
     */
    val noFunds: Boolean get() = !editor.loading && editor.loadError == null && funds.isEmpty() && (!prefilled || fundLookupFailed)
}

/** Det förifyllda lägets flaggor ur nyckeln — låst från start, löses av uppslaget. */
private data class PrefillState(
    val fundLocked: Boolean = false,
    val fundLookupFailed: Boolean = false,
    val prefillAmountKr: Double? = null
)

/** Det som läses vid sidan av formuläret för bytesväljaren. */
private data class SwitchContext(
    val inputs: SwitchChoicesSource.Inputs?,
    val lookedUp: FollowedSuggestion?,
    val linkFailed: Boolean
)

/**
 * Formulär för att registrera en fondtransaktion (NAV-4, issue #4). Sparar via [RecordTransaction]
 * (BYT-2) med svaret på **bytesväljaren**: alternativ och förval ur [SwitchQuestions] (samma
 * regler som PDF-importen), och Spara släckt tills en fråga utan förval besvarats.
 *
 * Formuläret är android-ui:s [EditorState] med [EditorLoader], och skärmen dess `EntityEditScreen`
 * (UI-15, NFR-6): fonderna – och för en förifylld nyckel fonden, kursen och andelarna – läses en gång
 * innan formuläret visas, ett läsfel visas med "Försök igen" ([retry]), Spara är aktiv först när något
 * ändrats och formuläret är giltigt, och sparningen är spärrad mot dubbeltryck. Ramen kvitterar sparat
 * och sparfel via [events].
 *
 * Med argument i [key] förifylls formuläret (issue #144): Följ ger säljet för ett förslag med
 * fond och typ låsta, dagens datum, senaste kursen och andelar = belopp ÷ kurs; "Köpte den här"
 * ger köpet som avslutar bevakningen. Ett förifyllt formulär får sparas utan ändring
 * (`saveUnchanged`). En fond som bara är känd på ISIN (en kandidat appen inte bevakar) slås upp med
 * [FundPriceRepository.findFundByIsin] och läggs till när köpet sparas — går den inte att slå upp
 * är fondväljaren reserven.
 *
 * Vid sälj visas innehavet i vald fond och "Sälj alla andelar" ([onSellAll]) fyller i det; fler
 * andelar än så är ett fältfel (TRX-5). Innehavet räknas av [RealizedGainCalculator.remainingPositions],
 * samma FIFO-matchning som portföljen.
 */
@HiltViewModel(assistedFactory = TransactionFormViewModel.Factory::class)
class TransactionFormViewModel @AssistedInject constructor(
    @Assisted private val key: TransactionFormKey,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val recordTransaction: RecordTransaction,
    switchChoicesSource: SwitchChoicesSource,
    private val followedSuggestionLookup: FollowedSuggestionLookup
) : ViewModel() {

    private val funds = MutableStateFlow<List<Fund>>(emptyList())

    /** Fonden ur nyckeln när den inte bevakas än (uppslagen på ISIN) — läggs till först när transaktionen sparas. */
    private val lookedUpFund = MutableStateFlow<Fund?>(null)
    private val linkFailed = MutableStateFlow(false)

    /** Andelar per fond-id ur transaktionerna; null innan de lästs. */
    private val ownedByFund = MutableStateFlow<Map<String, Double>?>(null)

    // Låst från start när nyckeln pekar ut en fond: annars hann ett val i fondväljaren göras
    // under uppslaget och skrivas över av det.
    private val prefill = MutableStateFlow(PrefillState(fundLocked = key.prefilled))

    /** Följs förslag uppslaget på radens id — reserven när det inte står i Besluts källa ([SwitchQuestions.followed]). */
    private val lookedUpFollow = MutableStateFlow<FollowedSuggestion?>(null)
    private val switchInputs = MutableStateFlow<SwitchChoicesSource.Inputs?>(null)

    /** Pågående kursuppslag för vald fond — avbryts vid fondbyte, se [onFundSelected]. */
    private var priceLookupJob: Job? = null

    private val validator = transactionFormValidator(
        switchAnswered = { form -> switchInputs.value?.let { question(it, lookedUpFollow.value, form).resolve(form.chosenSwitch) } != null },
        ownedShares = { fundId -> ownedByFund.value?.let { it[fundId] ?: 0.0 } },
        linkFailed = { linkFailed.value }
    )

    private val editor = EditorState(
        initial = TransactionForm(type = key.type ?: TransactionType.KOP),
        validator = validator,
        errors = AppErrors,
        loading = true,
        saveUnchanged = key.prefilled
    )

    /** Ramens kvittering av sparat och sparfel ([EditorUiState.done], [EditorUiState.failure]). */
    val events: EditorEvents get() = editor

    // Ett förifyllt sälj visar översälj direkt – andelarna kommer ur förslagets belopp, inte ur något
    // användaren skrivit. Ett förifyllt köp har inga andelar än, och där väntar felen tills fältet rörts.
    private val loader = EditorLoader(
        editor,
        viewModelScope,
        read = { suspendRunCatching { readForm() } },
        showInvalid = key.prefilled && key.type == TransactionType.SALJ
    )

    private val shownFunds = combine(funds, lookedUpFund) { tracked, extra ->
        if (extra == null || tracked.any { it.fundId == extra.fundId }) tracked else tracked + extra
    }
    private val switchContext = combine(switchInputs, lookedUpFollow, linkFailed) { inputs, lookedUp, linkFailed -> SwitchContext(inputs, lookedUp, linkFailed) }

    val uiState: StateFlow<TransactionFormUiState> =
        combine(editor.state, shownFunds, prefill, switchContext, ownedByFund) { form, funds, prefill, switch, owned ->
            val value = form.value
            TransactionFormUiState(
                // Sparat utan koppling stänger inte formuläret – det står kvar och säger det.
                editor = if (switch.linkFailed) form.copy(done = false) else form,
                funds = funds.toImmutableList(),
                valid = (validator.validate(value).keys - notFieldErrors).isEmpty(),
                linkFailed = switch.linkFailed,
                prefilled = key.prefilled,
                fundLocked = prefill.fundLocked,
                typeLocked = key.type != null,
                fundLookupFailed = prefill.fundLookupFailed,
                followIntro = switch.inputs?.let { followIntro(followed(it, switch.lookedUp), prefill.prefillAmountKr) },
                prefillAmountKr = prefill.prefillAmountKr,
                switchField = switch.inputs?.let { switchField(it, switch.lookedUp, value) },
                ownedShares = ownedShares(value, owned)
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TransactionFormUiState(
                editor = editor.state.value,
                prefilled = key.prefilled,
                typeLocked = key.type != null,
                fundLocked = key.prefilled
            )
        )

    init {
        // Ett läsfel i fonderna visas av laddningen ([readForm], "Försök igen"), inte som en krasch här.
        transactionRepository.observeFunds().onEach { funds.value = it }.catch { }.launchIn(viewModelScope)
        // Giltigheten vilar på bytesväljarens indata och innehavet — revalideras när de kommer.
        switchChoicesSource.observe().onEach {
            switchInputs.value = it
            editor.revalidate()
        }.launchIn(viewModelScope)
        transactionRepository.observeTransactions().onEach { transactions ->
            ownedByFund.value = RealizedGainCalculator.remainingPositions(transactions).mapValues { (_, position) -> position.shares }
            editor.revalidate()
        }.catch { }.launchIn(viewModelScope) // Utan innehav prövas inget översälj och "Sälj alla" visas inte.
    }

    /**
     * Formulärets startvärde: fonderna läses först (så att formuläret inte blinkar förbi "Inga bevakade
     * fonder"), och för en förifylld nyckel fond, kurs och andelar. Fonden söks bland de bevakade på id
     * och sedan på ISIN, och slås annars upp på ISIN — aldrig på namn. Andelarna räknas ur beloppet
     * (nyckelns, annars förslagets eget) och senaste kursen; saknas någon av dem lämnas fältet tomt.
     */
    private suspend fun readForm(): TransactionForm {
        val tracked = transactionRepository.observeFunds().first()
        funds.value = tracked
        val blank = TransactionForm(type = key.type ?: TransactionType.KOP, date = LocalDate.now())
        if (!key.prefilled) return blank
        val lookedUp = key.suggestionRecordId?.let { followedSuggestionLookup.lookup(it) }
        lookedUpFollow.value = lookedUp
        val fund = tracked.firstOrNull { key.fundId != null && it.fundId == key.fundId }
            ?: tracked.firstOrNull { key.fundIsin != null && it.isin == key.fundIsin }
            ?: key.fundIsin?.let { isin -> fundPriceRepository.findFundByIsin(isin)?.copy(isin = isin) }?.also { lookedUpFund.value = it }
        if (fund == null) {
            prefill.value = PrefillState(fundLookupFailed = true)
            return blank
        }
        val amount = key.prefillAmountKr ?: key.suggestionRecordId?.let { recordId ->
            SwitchQuestions.followed(switchInputs.filterNotNull().first(), recordId, lookedUp)?.switchValueKr
        }
        val nav = fundPriceRepository.latestPrice(fund.fundId)?.nav
        val shares = TransactionPrefill.sharesForAmount(amount, nav)
        prefill.value = PrefillState(fundLocked = true, prefillAmountKr = amount.takeIf { shares != null })
        return blank.copy(
            fund = fund,
            priceText = nav?.toString().orEmpty(),
            sharesText = shares?.let { SwedishNumberFormat.format(it, TransactionPrefill.SHARE_DECIMALS) }.orEmpty()
        )
    }

    private fun followed(inputs: SwitchChoicesSource.Inputs, lookedUp: FollowedSuggestion?): FollowedSuggestion? = key.suggestionRecordId?.let { SwitchQuestions.followed(inputs, it, lookedUp) }

    private fun followIntro(followed: FollowedSuggestion?, amountKr: Double?): FollowIntro? = followed?.let { FollowIntro(planIndex = it.planIndex, buyFundName = it.buyFundName, amountKr = amountKr ?: it.switchValueKr) }

    private fun question(inputs: SwitchChoicesSource.Inputs, lookedUp: FollowedSuggestion?, form: TransactionForm) = SwitchQuestions.forTransaction(inputs, form.type, form.fund?.isin, form.date.toEpochDay(), followed(inputs, lookedUp), key.watchId)

    private fun switchField(inputs: SwitchChoicesSource.Inputs, lookedUp: FollowedSuggestion?, form: TransactionForm) = SwitchFieldUi.of(form.type, question(inputs, lookedUp, form), form.chosenSwitch, inputs, followed(inputs, lookedUp))

    /** Innehavet i vald fond vid sälj — 0 för en fond utan andelar; null vid köp, utan fond eller innan transaktionerna lästs. */
    private fun ownedShares(form: TransactionForm, owned: Map<String, Double>?): Double? {
        val fund = form.fund ?: return null
        if (form.type != TransactionType.SALJ || owned == null) return null
        return owned[fund.fundId] ?: 0.0
    }

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
        editor.update(TRANSACTION_FUND) { it.copy(fund = fund, chosenSwitch = null, priceText = "") }
        priceLookupJob = viewModelScope.launch {
            val nav = fundPriceRepository.latestPrice(fund.fundId)?.nav ?: return@launch
            // Förifyllt, inte skrivet: kursfältet räknas inte som rört.
            editor.update { form -> if (form.fund?.fundId == fund.fundId) form.copy(priceText = nav.toString()) else form }
        }
    }

    fun onTypeChange(newType: TransactionType) {
        if (key.type != null || newType == editor.value.type) return
        editor.update(TRANSACTION_TYPE) { it.copy(type = newType, chosenSwitch = null) }
    }

    fun onDateChange(newDate: LocalDate) = editor.update(TRANSACTION_DATE) { it.copy(date = newDate) }

    fun onSharesTextChange(text: String) = editor.update(TRANSACTION_SHARES) { it.copy(sharesText = text) }

    fun onPriceTextChange(text: String) = editor.update(TRANSACTION_PRICE) { it.copy(priceText = text) }

    fun onFeeTextChange(text: String) = editor.update(TRANSACTION_FEE) { it.copy(feeText = text) }

    fun onSwitchSelected(option: SwitchOption) = editor.update(TRANSACTION_SWITCH) { it.copy(chosenSwitch = option) }

    /** "Sälj alla andelar" (TRX-5): hela innehavet i vald fond, med alla decimaler, i antalsfältet. */
    fun onSellAll() {
        val owned = ownedShares(editor.value, ownedByFund.value)?.takeIf { it > 0.0 } ?: return
        editor.update(TRANSACTION_SHARES) { it.copy(sharesText = SwedishNumberFormat.formatShares(owned)) }
    }

    /**
     * Sparar via [EditorState.save] — idempotent under pågående sparning, så två snabba tryck inte ger
     * två transaktioner, och inte utan giltiga fält och ett svar på bytesfrågan. "Ja" på frågan om en
     * okopplad bevakning skrivs som [SwitchDecision.LinksWatch] i samma anrop; gjordes kopplingen inte
     * (bevakningen hann kopplas eller stängas) sägs det ([TransactionFormUiState.linkFailed]) i stället
     * för att formuläret stängs som om allt gått bra. Ett fel vid sparningen visas av ramen som
     * snackbar (NFR-6) och formuläret står kvar med inmatningen; ett avbrott släpps igenom och är
     * inget fel.
     */
    fun save() {
        viewModelScope.launch {
            // Sparningen är lokal (Room): ett skrivfel är aldrig "kunde inte nå källan" eller
            // "gick inte att läsa" — samma val som Riskprofilen.
            editor.save { form -> suspendRunCatching({ DataError.Unknown }) { write(form) } }
            // Sparat utan koppling: "klar" kvitteras här, formuläret står kvar och säger det.
            if (linkFailed.value) editor.onDoneHandled()
        }
    }

    private suspend fun write(form: TransactionForm) {
        val fund = checkNotNull(form.fund)
        val inputs = checkNotNull(switchInputs.value)
        val option = checkNotNull(question(inputs, lookedUpFollow.value, form).resolve(form.chosenSwitch))
        val transaction = Transaction(
            fundId = fund.fundId,
            type = form.type,
            epochDay = form.date.toEpochDay(),
            shares = checkNotNull(SwedishNumberFormat.parse(form.sharesText)),
            pricePerShare = checkNotNull(SwedishNumberFormat.parse(form.priceText)),
            fee = checkNotNull(parseFee(form.feeText))
        )
        if (lookedUpFund.value?.fundId == fund.fundId && funds.value.none { it.fundId == fund.fundId }) {
            transactionRepository.upsertFund(fund)
        }
        val recorded = recordTransaction.record(RecordTransaction.Entry(transaction, option.decision))
        // Sätts före sparningens slut, så att valideringen redan då släcker Spara.
        if (option.decision is SwitchDecision.LinksWatch && recorded.watchId == null) linkFailed.value = true
    }

    /** "Försök igen" efter ett läsfel: läser fonderna (och förifyllningen) på nytt. */
    fun retry() = loader.retry()

    /** Skapar ViewModeln för en given nyckel (assisted injection, `hiltViewModel` med `creationCallback`). */
    @AssistedFactory
    interface Factory {
        fun create(key: TransactionFormKey): TransactionFormViewModel
    }
}
