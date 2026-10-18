package se.partee71.fonder.ui.bytesfonster

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SwitchWatchRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCandidateSource
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.usecase.SwitchPlanCalc
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import se.partee71.fonder.domain.usecase.TargetLevelPreselection
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import java.time.LocalDate
import kotlin.math.roundToInt

/** Kurshistoriken för en bevakad kandidat — samma tre lägen som ANA-11:s jämförelse. */
sealed interface CandidateHistory {
    data object Loading : CandidateHistory

    /** Ingen källa kunde ge historiken — sägs ut på raden, aldrig som en tom kurva. */
    data object Unavailable : CandidateHistory

    data class Ready(val points: List<Pair<Long, Double>>) : CandidateHistory
}

/**
 * En bevakad kandidat, redo för visning. Utvecklingen är alltid mätt från **säljdagen**
 * ([SwitchWatchCalc.outcome]), aldrig från diagrammets periodstart.
 */
data class CandidateRow(
    val candidateId: Long,
    val isin: String,
    val name: String,
    val riskLevel: Int? = null,
    val feePercent: Double? = null,
    val changeFraction: Double? = null,
    val changeKr: Double? = null,
    /** Nollpunkten kunde inte ankras på säljdagen utan på en senare dag — kortare fönster än väntperioden. */
    val partial: Boolean = false,
    /** Historiken gick inte att hämta — raden visas utan kurva och utan utveckling, aldrig som 0. */
    val historyUnavailable: Boolean = false,
    /**
     * Historiken hämtas just nu — radens väntesnurra (NAV-6). Skild från [historyUnavailable]:
     * "vi vet inte än" och "det gick inte" är olika besked, och utan snurran ser det första ut
     * som det andra under de sekunder källan svarar (ANA-4-principen).
     */
    val historyLoading: Boolean = false,
    /** Handplockad kandidat (ANA-13) — bara de går att ta bort; de automatiska hör till förslaget. */
    val manual: Boolean = false,
)

/** Engångsmeddelande efter en åtgärd — visas och kvitteras, ingen del av det bestående tillståndet. */
sealed interface SwitchWatchMessage {
    /** Den valda fonden gick inte att identifiera med ISIN och kan därför inte bevakas (ANA-4-principen). */
    data object IsinUnavailable : SwitchWatchMessage

    /** Listan är full — [SwitchWatchCalc.MAX_CANDIDATES] är taket. */
    data object CandidateLimitReached : SwitchWatchMessage
}

/**
 * Varför listan över alternativ är tom (ANA-13, issue #149) — tre olika besked som tidigare
 * delade en enda text. Null när appen inte försökt eller förslagen bara inte är ifyllda än.
 */
enum class SwitchWatchEmptyReason {
    /** Risknivån för säljfonden är okänd, även i källan — appen gissar aldrig en nivå. */
    NO_TARGET_LEVEL,

    /** Ingen av de prövade fonderna på nivån gick att köpa hos Handelsbanken. */
    NONE_AVAILABLE,

    /** Köpbarheten kunde inte kontrolleras (katalogen gick inte att hämta) — går att försöka igen. */
    LOOKUP_FAILED,
}

@Immutable
data class SwitchWatchUiState(
    val loading: Boolean = true,
    /** Bevakningen finns inte (raderad, eller en gammal länk) — skärmen säger det i stället för att visa tomt. */
    val missing: Boolean = false,
    val sellFundName: String = "",
    val soldAtEpochDay: Long = 0,
    val proceedsKr: Double? = null,
    val daysWaiting: Long = 0,
    val ttlDays: Long = SwitchWatchCalc.WATCH_TTL_DAYS,
    /** Öppen men äldre än TTL:n — läget beskrivs inte längre av verkligheten (ANA-12). */
    val expired: Boolean = false,
    val closed: Boolean = false,
    val boughtFundName: String? = null,
    val rows: ImmutableList<CandidateRow> = persistentListOf(),
    /** Säljfondens egen kurva — diagrammets primärserie, det man bytte *från*. Tom om historiken saknas. */
    val sellSeries: ImmutableList<Pair<Long, Double>> = persistentListOf(),
    /** Kandidatkurvor i samma ordning som [rows], bara de som gick att hämta. */
    val candidateSeries: ImmutableList<Pair<String, ImmutableList<Pair<Long, Double>>>> = persistentListOf(),
    /** Sant medan appens egna kandidatförslag hämtas (ANA-13) — en källfråga mot nivån. */
    val fillingCandidates: Boolean = false,
    /**
     * Målnivån förslagen hämtas för — den valda i nivåväljaren (ANA-13, issue #150), och texten för
     * [SwitchWatchEmptyReason.NONE_AVAILABLE] nämner den. Ändras med [SwitchWatchViewModel.onSelectTargetLevel].
     */
    val targetLevel: Int? = null,
    /**
     * Säljfondens **egen** risknivå ur metadatan — nivåväljarens markering av "samma som du sålde"
     * (issue #150). Null tills metadatan lästs, eller när nivån inte är känd.
     */
    val sellLevel: Int? = null,
    /** Filtret "bara indexfonder" för appens förslag ([SwitchWatch.indexOnly]) — ändras med [SwitchWatchViewModel.onIndexOnlyChanged]. */
    val indexOnly: Boolean = false,
    /**
     * Nivån riskprofilen saknar mest på (issue #150) — samma regel som förvalet
     * ([SwitchPlanCalc.profileSuggestion]) men utan planens nivå, med bevakningens egen likvid
     * räknad i totalen och på ingen nivå. Null utan riskprofil, utan underviktad nivå, för en stängd
     * bevakning eller tills den räknats. Förklaringsraden jämför den med [targetLevel].
     */
    val profileSuggestedLevel: Int? = null,
    /** Hur mycket profilen saknar på [profileSuggestedLevel], i hela procentenheter (avrundat). Null när den är null. */
    val profileGapPp: Int? = null,
    /** Varför listan är tom, bara när den är det och bevakningen är öppen — se [SwitchWatchEmptyReason]. */
    val emptyReason: SwitchWatchEmptyReason? = null,
    val message: SwitchWatchMessage? = null,
) {
    val canAddCandidate: Boolean get() = !closed && rows.size < SwitchWatchCalc.MAX_CANDIDATES
    val hasChart: Boolean get() = sellSeries.isNotEmpty() || candidateSeries.isNotEmpty()

    /**
     * Tomläget för [SwitchWatchEmptyReason.NONE_AVAILABLE] beror på filtret: inga köpbara
     * **indexfonder** på nivån är ett annat besked än inga köpbara fonder alls (issue #150).
     */
    val emptyBecauseOfIndexFilter: Boolean get() = emptyReason == SwitchWatchEmptyReason.NONE_AVAILABLE && indexOnly

    /** Nivå och filter går bara att ändra medan bytet pågår — en stängd bevakning bär vad som hände. */
    val canChangeSelection: Boolean get() = !loading && !missing && !closed

    /** Rubrikkortets väntesnurra (NAV-6) — bevakningen läses och alternativen fylls på. */
    val headerWorking: Boolean get() = loading || fillingCandidates

    /**
     * Diagrammets väntesnurra: en kurva som ännu saknar en av kandidaterna är inte fel, men den
     * är inte heller hela jämförelsen — och skillnaden syns inte i bilden.
     */
    val chartWorking: Boolean get() = loading || fillingCandidates || rows.any { it.historyLoading }
}

/**
 * Skärmen **Pågående byte** (ANA-12, issue #114) — perioden mellan att en fond sålts och att
 * likviden köpt nästa, med de alternativ användaren bevakar under tiden.
 *
 * Kandidaternas kurshistorik hämtas vid skärmöppning och hålls **bara i minnet**
 * ([FundPriceRepository.historyForIsin]) — den skrivs aldrig till kurscachen, som är sanningen
 * om bevakade fonder (samma gräns som ANA-11 drar). Taket på antal kandidater
 * ([SwitchWatchCalc.MAX_CANDIDATES]) är därför en kostnadsgräns lika mycket som en
 * beslutsgräns: varje kandidat är ett nätverksanrop mot en odokumenterad källa (TP-14) varje
 * gång skärmen öppnas.
 *
 * Appen **utför aldrig ett byte**: "Köpte den här" öppnar köpformuläret för kandidaten
 * (`TransactionFormKey.bought`, BYT-2), och det sparade köpet stänger bevakningen med
 * köptransaktionen som koppling — ingen kvittering utan transaktion sker härifrån.
 */
@HiltViewModel(assistedFactory = SwitchWatchViewModel.Factory::class)
class SwitchWatchViewModel @AssistedInject constructor(
    /** Bevakningen skärmen visar — ur navigationsnyckeln (`SwitchWatchKey`/`SwitchWatchPickerKey`). */
    @Assisted private val watchId: Long,
    private val switchWatchRepository: SwitchWatchRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val transactionRepository: TransactionRepository,
    private val targetLevelPreselection: TargetLevelPreselection,
    private val scheduler: FundPriceRefreshScheduler,
) : ViewModel() {

    private val metadata = MutableStateFlow<Map<String, FundMetadata>>(emptyMap())
    private val histories = MutableStateFlow<Map<String, CandidateHistory>>(emptyMap())
    private val sellSeries = MutableStateFlow<List<Pair<Long, Double>>>(emptyList())
    private val fillingCandidates = MutableStateFlow(false)
    private val emptyReason = MutableStateFlow<SwitchWatchEmptyReason?>(null)
    private val message = MutableStateFlow<SwitchWatchMessage?>(null)
    private val profileSuggestion = MutableStateFlow<SwitchPlanCalc.LevelGap?>(null)

    /** ISIN vars historik redan hämtats (eller pågår) — varje kandidat hämtas en gång per skärmlivstid. */
    private val requestedHistories = mutableSetOf<String>()

    /** Kandidater vars nollpunkt redan skrivits från den här skärmen — en skrivning per kandidat. */
    private val anchoredCandidates = mutableSetOf<Long>()

    /** Säljdagen historiken i minnet hämtades för — flyttas den (BYT-2-koppling) hämtas historiken om. */
    private var historiesSoldAt: Long? = null
    private var autoFillAttempted = false

    /**
     * **Alla** påfyllningar av appens förslag — vid skärmöppning, "Försök igen" och byte av nivå/filter
     * — körs här, en i taget ([launchFill]): två samtidiga hade kunnat skriva förslag på fel nivå
     * eller lämna fel [emptyReason]/[fillingCandidates] efter sig.
     */
    private var fillJob: Job? = null

    /**
     * Valet ett **pågående** byte av nivå/filter ska skriva — null när inget pågår. Bara det jämförs
     * ett nytt tryck med före avbrottet ([changeSelection]); annars är databasen facit.
     */
    private var pendingSelection: Selection? = null
    private var sellSeriesLoaded = false

    /**
     * Profilens förslag, räknat **en** gång per skärmöppning ([profileSuggestionAsync]) — räknas om
     * bara efter ett fel, av "Försök igen".
     */
    private var profileSuggestionJob: Deferred<ProfileSuggestion>? = null

    private val watch: Flow<SwitchWatch?> = switchWatchRepository.observe(watchId)

    val uiState: StateFlow<SwitchWatchUiState> = combine(
        watch,
        metadata,
        histories,
        sellSeries,
        combine(fillingCandidates, emptyReason, message, profileSuggestion, ::Transient),
    ) { watch, metadataByIsin, historyByIsin, sellPoints, (filling, reason, msg, suggestion) ->
        if (watch == null) return@combine SwitchWatchUiState(loading = false, missing = true)

        val today = LocalDate.now()
        val rows = SwitchWatchCalc.ranked(
            watch.candidates.map { candidate ->
                SwitchWatchCalc.outcome(
                    candidate = candidate,
                    latestNav = latestNavFor(candidate, historyByIsin),
                    proceedsKr = watch.proceedsKr,
                    soldAtEpochDay = watch.soldAtEpochDay,
                )
            },
        ).map { outcome ->
            val candidate = outcome.candidate
            val meta = metadataByIsin[candidate.isin]
            CandidateRow(
                candidateId = candidate.id,
                isin = candidate.isin,
                name = candidate.name,
                riskLevel = meta?.risk,
                feePercent = meta?.totalFee,
                changeFraction = outcome.changeFraction,
                changeKr = outcome.changeKr,
                partial = outcome.partial,
                historyUnavailable = historyByIsin[candidate.isin] is CandidateHistory.Unavailable,
                // Också "ännu inte efterfrågad" (null) räknas som pågående: hämtningen köas i
                // samma svep som raden dyker upp, se ensureHistories.
                historyLoading = historyByIsin[candidate.isin].let { it == null || it is CandidateHistory.Loading },
                manual = candidate.source == SwitchWatchCandidateSource.MANUELL,
            )
        }

        SwitchWatchUiState(
            loading = false,
            sellFundName = watch.sellFundName,
            soldAtEpochDay = watch.soldAtEpochDay,
            proceedsKr = watch.proceedsKr,
            daysWaiting = SwitchWatchCalc.daysWaiting(watch, today),
            expired = SwitchWatchCalc.isExpired(watch, today),
            closed = !watch.isOpen,
            boughtFundName = watch.boughtIsin?.let { isin ->
                watch.candidates.firstOrNull { it.isin == isin }?.name ?: metadataByIsin[isin]?.name ?: isin
            },
            rows = rows.toImmutableList(),
            sellSeries = sellPoints.toImmutableList(),
            // Kurvorna följer radordningen, så teckenförklaringen läser i samma ordning som
            // listan under den — en kurva vars rad ligger femma i listan men tvåa i förklaringen
            // hade varit två svar på samma fråga.
            candidateSeries = rows.mapNotNull { row ->
                (historyByIsin[row.isin] as? CandidateHistory.Ready)?.let { row.name to it.points.toImmutableList() }
            }.toImmutableList(),
            fillingCandidates = filling,
            targetLevel = watch.targetLevel,
            sellLevel = metadataByIsin[watch.sellIsin]?.risk,
            indexOnly = watch.indexOnly,
            profileSuggestedLevel = suggestion?.level,
            profileGapPp = suggestion?.gapPp?.roundToInt(),
            // Bara när listan faktiskt är tom: en handplockad kandidat efter ett misslyckat
            // förslag gör orsaken inaktuell, och en stängd bevakning föreslår inget alls.
            emptyReason = reason.takeIf { rows.isEmpty() && watch.isOpen && !filling },
            message = msg,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SwitchWatchUiState(),
    )

    init {
        viewModelScope.launch {
            watch.collect { current ->
                if (current == null) return@collect
                if (current.isOpen) profileSuggestionAsync(current)
                ensureAutoCandidates(current)
                ensureSellSeries(current)
                ensureHistories(current)
                ensureMetadata(current)
            }
        }
    }

    /**
     * Dra ned för att uppdatera (UI-11) — hämtar om kandidaternas historik och säljfondens
     * kurva. Kandidaterna ligger per definition inte i kurscachen (ANA-11), så det som visas är
     * alltid hämtningen från skärmöppningen; utan den här vägen fanns ingen väg till färskare
     * tal än att lämna skärmen och komma tillbaka.
     *
     * Nollställer hämtningsspärrarna i stället för att gå runt dem, så `ensureHistories` gör
     * exakt vad den gör vid skärmöppning — en andra kodväg hade kunnat hämta ett annat fönster
     * och därmed en annan nollpunkt. Kandidatlistan fylls **inte** på igen: appens förslag
     * (ANA-13) hör till bevakningens start och till användarens val av nivå och filter
     * ([onSelectTargetLevel]), och att byta ut alternativen mitt under en pågående jämförelse vore
     * ett annat svar på en fråga användaren redan ställt.
     */
    fun refresh() {
        viewModelScope.launch {
            val current = watch.first() ?: return@launch
            requestedHistories.clear()
            sellSeriesLoaded = false
            ensureSellSeries(current)
            ensureHistories(current)
        }
    }

    /**
     * Senast kända kurs för kandidaten: den hämtade historikens sista punkt. Serien är hämtad i
     * det här ögonblicket och är därmed alltid minst lika färsk som cachen — och kandidaten
     * ligger per definition inte i kurscachen som ett innehav (ANA-11), så den vore ändå bara
     * en delvis fylld reserv.
     */
    private fun latestNavFor(candidate: SwitchWatchCandidate, historyByIsin: Map<String, CandidateHistory>): Double? =
        (historyByIsin[candidate.isin] as? CandidateHistory.Ready)?.points?.maxByOrNull { it.first }?.second

    /**
     * Fyller listan med appens egna förslag (ANA-13) när skärmen öppnas — se [fillAutoCandidates].
     *
     * Görs **en gång** per skärmlivstid och bara när listan är tom: en omfyllning vid varje
     * emission hade kostat en källfråga per gång och kunnat byta ut de alternativ användaren
     * just tittade på. Ett byte av nivå eller filter fyller på igen, en gång per val ([changeSelection]).
     * Körs i [fillJob] som alla andra påfyllningar, så ett nivåbyte under den avbryter och inväntar den.
     */
    private fun ensureAutoCandidates(watch: SwitchWatch) {
        if (autoFillAttempted || !watch.isOpen || watch.candidates.isNotEmpty()) return
        autoFillAttempted = true
        // Ett pågående byte av nivå/filter fyller själv på — det får inte avbrytas innan valet skrivits.
        if (fillJob?.isActive == true) return
        launchFill { fillAutoCandidates(watch) }
    }

    /**
     * Startar en påfyllning i [fillJob] efter att den förra avbrutits **och** avslutats.
     * [block] får veta om den förra fortfarande pågick — då kan den ha lämnat sitt arbete halvgjort.
     */
    private fun launchFill(block: suspend (interrupted: Boolean) -> Unit) {
        val previous = fillJob
        val interrupted = previous?.isActive == true
        fillJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            block(interrupted)
        }
    }

    /**
     * Lägger till appens förslag — samma pool och samma rangordning som bytesplanens köpkandidater
     * ([FundMetadataRepository.findSwitchCandidates]), alltså översta kvartilen på
     * 12-månadersavkastning och därefter lägst avgift, med bevakningens filter (bara indexfonder,
     * issue #150) — högst [SwitchWatchCalc.AUTO_CANDIDATES], efter de handplockade som redan finns.
     *
     * Saknar bevakningen målnivå förväljs den nu ([fillMissingTargetLevel], issue #149/#150) och
     * sparas på bevakningen. Ger inte heller det en nivå görs inget förslag: en gissad nivå hade gett
     * förslag på fel risk, vilket är sämre än inga förslag (ANA-4-principen). Utfallet blir
     * [emptyReason], så tomläget kan säga vilken av de tre orsakerna det gäller.
     *
     * Förslagen skrivs bara om nivån och filtret fortfarande är de sökningen gjordes för
     * ([SwitchWatchRepository.addAutoCandidates]) — ett svar som hann bli inaktuellt hamnar aldrig
     * på fel nivå. Fyller bara på upp till [SwitchWatchCalc.AUTO_CANDIDATES] appförslag: en
     * påfyllning som avbröts av ett nytt val lämnar aldrig listan tom, och en full lista frågar
     * inte källan igen.
     */
    private suspend fun fillAutoCandidates(watch: SwitchWatch) {
        val missing = SwitchWatchCalc.AUTO_CANDIDATES - watch.candidates.count { it.source == SwitchWatchCandidateSource.AUTO }
        if (missing <= 0) return
        emptyReason.value = null
        fillingCandidates.value = true
        try {
            val level = watch.targetLevel ?: when (val filled = fillMissingTargetLevel(watch)) {
                is MissingLevel.Filled -> filled.level
                MissingLevel.Unknown -> {
                    emptyReason.value = SwitchWatchEmptyReason.NO_TARGET_LEVEL
                    return
                }
                // Profilens förslag gick inte att räkna — ingen nivå sparas på ett tillfälligt fel.
                MissingLevel.Failed -> {
                    emptyReason.value = SwitchWatchEmptyReason.LOOKUP_FAILED
                    return
                }
            }
            val owned = transactionRepository.observeFunds().first().mapNotNull { it.isin }.toSet()
            val watched = watch.candidates.map { it.isin }
            val search = fundMetadataRepository.searchSwitchCandidates(level, owned + watch.sellIsin + watched, watch.indexOnly)
            val candidates = search.candidates
                .take(missing)
                .mapIndexed { index, candidate ->
                    SwitchWatchCandidate(
                        watchId = watch.id,
                        isin = candidate.metadata.isin,
                        name = candidate.metadata.name,
                        source = SwitchWatchCandidateSource.AUTO,
                        position = index,
                    )
                }
            if (candidates.isNotEmpty()) {
                switchWatchRepository.addAutoCandidates(watch.id, level, watch.indexOnly, candidates)
            } else {
                emptyReason.value =
                    if (search.lookupFailed) SwitchWatchEmptyReason.LOOKUP_FAILED else SwitchWatchEmptyReason.NONE_AVAILABLE
            }
        } finally {
            fillingCandidates.value = false
        }
    }

    /**
     * Förvald målnivå sparad på bevakningen — bara där nivån saknas
     * ([SwitchWatchRepository.fillMissingTargetLevel]). Samma regel som när säljet registreras
     * ([SwitchPlanCalc.preselectTargetLevel], issue #150): med riskprofil den mest underviktade nivån
     * (bevakningens egen likvid oräknad), annars säljfondens risknivå — här får uppslaget gå till
     * källan. Profilens förslag är **samma** beräkning som skärmen visar ([profileSuggestionAsync]),
     * inte en andra portföljläsning. [MissingLevel.Unknown] när ingen nivå är känd ens där.
     *
     * Gick profilens förslag inte att räkna sparas **ingen** nivå ([MissingLevel.Failed]): med en
     * riskprofil hade reserven (säljfondens nivå) annars sparats för gott på ett tillfälligt fel.
     * Utan profil kan förslaget inte misslyckas — då läses inget annat än profilen.
     */
    private suspend fun fillMissingTargetLevel(watch: SwitchWatch): MissingLevel {
        val suggestion = profileSuggestionAsync(watch).await()
        if (suggestion is ProfileSuggestion.Failed) return MissingLevel.Failed
        val sellLevel = fundMetadataRepository.metadataFor(listOf(watch.sellIsin))[watch.sellIsin]?.risk
        val level = SwitchPlanCalc.preselectTargetLevel(
            planToLevel = null,
            profileSuggestion = (suggestion as ProfileSuggestion.Computed).gap,
            sellLevel = sellLevel,
        ) ?: return MissingLevel.Unknown
        if (switchWatchRepository.fillMissingTargetLevel(watch.id, level)) {
            // Likviden räknas på målnivån (BYT-3) — en nivå som nyss blev känd ändrar planen.
            scheduler.triggerSwitchPlanScan(force = false)
            return MissingLevel.Filled(level)
        }
        // Någon annan väg (t.ex. ett förslag som kopplades) hann sätta nivån — den sparade gäller.
        return this.watch.first()?.targetLevel?.let { MissingLevel.Filled(it) } ?: MissingLevel.Unknown
    }

    /**
     * Var riskprofilen saknar mest ([SwitchWatchUiState.profileSuggestedLevel], issue #150) — räknas
     * **en gång** per skärmöppning, i en egen korutin så att förslagen inte väntar på den, och
     * delas med förvalet i efterhand ([fillMissingTargetLevel]): en portföljläsning, inte två.
     * Innehavens metadata får gå till källan, och bevakningens egen likvid ligger på ingen nivå.
     * Oberoende av vald nivå, så ett nivåbyte räknar inte om den.
     *
     * Läsningen kan gå till nätet: ett fel ger [ProfileSuggestion.Failed], aldrig en krasch —
     * skärmen visar då bara den valda nivån, och en saknad nivå sparas inte ([fillMissingTargetLevel]).
     */
    private fun profileSuggestionAsync(watch: SwitchWatch): Deferred<ProfileSuggestion> =
        profileSuggestionJob ?: viewModelScope.async {
            val suggestion = try {
                ProfileSuggestion.Computed(
                    targetLevelPreselection.profileSuggestion(
                        proceedsKr = watch.proceedsKr,
                        today = LocalDate.now(),
                        metadataFor = fundMetadataRepository::metadataFor,
                        excludeWatchId = watch.id,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ingen detalj i loggen: undantaget kan bära belopp eller innehav.
                ProfileSuggestion.Failed
            }
            profileSuggestion.value = (suggestion as? ProfileSuggestion.Computed)?.gap
            suggestion
        }.also { profileSuggestionJob = it }

    /**
     * Glömmer ett **misslyckat** profilförslag, så nästa försök räknar om det. Ett avbrutet räknas
     * som misslyckat — `getCompleted` hade kastat på det.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun forgetFailedProfileSuggestion() {
        val job = profileSuggestionJob ?: return
        if (job.isCancelled || (job.isCompleted && job.getCompleted() is ProfileSuggestion.Failed)) profileSuggestionJob = null
    }

    /**
     * "Försök igen" efter [SwitchWatchEmptyReason.LOOKUP_FAILED] — samma påfyllning som vid
     * skärmöppning, i samma jobb som alla andra ([launchFill]).
     */
    fun onRetryAutoFill() {
        // Ett dubbeltryck ska inte starta två autofyllningar som båda lägger till kandidater.
        if (fillJob?.isActive == true) return
        launchFill {
            val current = watch.first() ?: return@launchFill
            // Felet kan ha varit profilens förslag (en saknad nivå) — det räknas om.
            forgetFailedProfileSuggestion()
            if (current.isOpen) fillAutoCandidates(current)
        }
    }

    /**
     * Användaren väljer risknivå att köpa på (ANA-13, issue #150). Appens förslag på den gamla
     * nivån tas bort, de handplockade ligger kvar, och förslag på den nya nivån hämtas — en gång
     * per val. Bytesplanen räknas om: likviden räknas på målnivån (BYT-3). Samma nivå igen gör ingenting.
     */
    fun onSelectTargetLevel(level: Int) {
        changeSelection(Selection(level = level)) { it.level == level }
    }

    /** Filtret "bara indexfonder" (issue #150) — samma flöde som [onSelectTargetLevel]. */
    fun onIndexOnlyChanged(indexOnly: Boolean) {
        changeSelection(Selection(indexOnly = indexOnly)) { it.indexOnly == indexOnly }
    }

    /**
     * Ett tryck på samma val som ett **pågående** byte redan ska skriva är en no-op **innan** något
     * avbryts — ett dubbeltryck får inte avbryta påfyllningen det första trycket startade. Annars
     * jämförs inget i minnet: skrivningen själv är villkorad i databasen (en no-op om värdet redan
     * gäller), så ett tryck ignoreras aldrig när databasen har något annat.
     *
     * Ett nytt val avbryter och **inväntar** det förra ([launchFill]) och skriver sedan det förras
     * ännu oskrivna del tillsammans med sin egen ([pendingSelection]), så ett avbrutet val aldrig
     * tappas halvvägs. Skrivningen (valet och borttagningen av appens förslag) är en
     * databastransaktion. Påfyllningen görs när valet ändrades eller ett avbrutet jobb kan ha lämnat
     * listan halvfylld, och fyller bara det som saknas ([fillAutoCandidates]); den skrivs bara om
     * valet fortfarande gäller ([SwitchWatchRepository.addAutoCandidates]).
     *
     * Bytesplanen räknas om bara när **nivån** kan ha ändrats — likviden räknas på målnivån (BYT-3),
     * filtret påverkar den inte.
     */
    private fun changeSelection(change: Selection, alreadyPending: (Selection) -> Boolean) {
        val previous = pendingSelection
        if (previous != null && alreadyPending(previous)) return
        val wanted = previous?.merge(change) ?: change
        pendingSelection = wanted
        // Före skrivningen: en lista som blir tom av den ska inte också fyllas av skärmöppningens väg.
        autoFillAttempted = true
        launchFill { interrupted ->
            try {
                // En stängd bevakning bär vad som hände — inget skrivs, inget räknas om.
                if (watch.first()?.isOpen != true) return@launchFill
                val levelChanged = wanted.level?.let { switchWatchRepository.setTargetLevel(watchId, it) } ?: false
                val filterChanged = wanted.indexOnly?.let { switchWatchRepository.setIndexOnly(watchId, it) } ?: false
                // Ett avbrutet byte kan ha hunnit skriva nivån men inte begärt omräkningen.
                if (levelChanged || (interrupted && previous?.level != null)) scheduler.triggerSwitchPlanScan(force = false)
                if (levelChanged || filterChanged || interrupted) {
                    val current = watch.first() ?: return@launchFill
                    if (current.isOpen) fillAutoCandidates(current)
                }
            } finally {
                if (pendingSelection === wanted) pendingSelection = null
            }
        }
    }

    /**
     * Säljfondens egen kurva ur **cachen** — den är ett bevakat innehav och behöver inget
     * nätverksanrop. Hämtas via fondens `fundId`, inte via ISIN:et: kurscachen är nycklad på
     * fondens identitet, och ISIN:et är bara identitet för fonder appen aldrig ägt (TP-13/TP-14).
     */
    private suspend fun ensureSellSeries(watch: SwitchWatch) {
        if (sellSeriesLoaded) return
        sellSeriesLoaded = true
        val fund = transactionRepository.observeFunds().first().firstOrNull { it.isin == watch.sellIsin }
            ?: return
        val today = LocalDate.now()
        sellSeries.value = fundPriceRepository
            .priceHistory(
                fundId = fund.fundId,
                fromEpochDay = today.minusYears(CHART_HISTORY_YEARS).toEpochDay(),
                toEpochDay = today.toEpochDay(),
            )
            .map { it.epochDay to it.nav }
            .sortedBy { it.first }
    }

    /**
     * Hämtar varje kandidats kurshistorik en gång och **ankrar nollpunkten** samtidigt: NAV på
     * säljdagen kan inte återskapas senare, eftersom kandidaten inte ligger i kurscachen. Fönstret
     * börjar [SwitchWatchCalc.ANCHOR_LOOKBACK_DAYS] före säljdagen så en säljdag på en helg eller
     * röd dag ändå kan ankras på första handelsdagen efter.
     */
    private suspend fun ensureHistories(watch: SwitchWatch) {
        // En koppling till ett sälj från en annan dag flyttar säljdagen och nollar nollpunkterna
        // (RecordTransaction.linkWatch) — historiken i minnet når kanske inte tillbaka till den nya dagen.
        if (historiesSoldAt != null && historiesSoldAt != watch.soldAtEpochDay) requestedHistories.clear()
        historiesSoldAt = watch.soldAtEpochDay
        watch.candidates.forEach { candidate ->
            // En nollpunkt som nollats efter att den skrevs här ska skrivas igen.
            if (candidate.navAtStart == null) anchoredCandidates -= candidate.id
            if (!requestedHistories.add(candidate.isin)) {
                // Historiken finns redan (eller hämtas) — en kandidat som lagts till igen efter ett
                // byte av nivå eller filter är en ny rad utan nollpunkt och ankras ur minnet.
                // Pågår hämtningen ankrar den när den är klar.
                if (candidate.navAtStart == null) {
                    (histories.value[candidate.isin] as? CandidateHistory.Ready)?.let { anchor(candidate, it.points, watch.soldAtEpochDay) }
                }
                return@forEach
            }
            histories.value = histories.value + (candidate.isin to CandidateHistory.Loading)
            viewModelScope.launch {
                val today = LocalDate.now()
                val from = LocalDate.ofEpochDay(watch.soldAtEpochDay).minusDays(SwitchWatchCalc.ANCHOR_LOOKBACK_DAYS)
                val points = fundPriceRepository.historyForIsin(candidate.isin, from, today)
                    .map { it.epochDay to it.nav }
                    .sortedBy { it.first }

                // Säljdagen flyttades medan hämtningen pågick — en ny hämtning för den nya dagen gäller.
                if (historiesSoldAt != watch.soldAtEpochDay) return@launch
                histories.value = histories.value + (
                    candidate.isin to if (points.isEmpty()) CandidateHistory.Unavailable else CandidateHistory.Ready(points)
                    )

                // Alla rader med fonden som saknar nollpunkt nu — också de som lagts till medan
                // hämtningen pågick.
                val current = this@SwitchWatchViewModel.watch.first() ?: return@launch
                if (current.soldAtEpochDay != watch.soldAtEpochDay) return@launch
                current.candidates
                    .filter { it.isin == candidate.isin && it.navAtStart == null }
                    .forEach { anchor(it, points, current.soldAtEpochDay) }
            }
        }
    }

    /**
     * Skriver kandidatens nollpunkt på säljdagen (eller första handelsdagen efter), en gång per
     * kandidat. Kandidaten räknas som ankrad först när skrivningen lyckats — en misslyckad skrivning
     * görs om vid nästa emission eller uppdatering, i stället för att raden blir utan nollpunkt.
     */
    private suspend fun anchor(candidate: SwitchWatchCandidate, points: List<Pair<Long, Double>>, soldAtEpochDay: Long) {
        val (epochDay, nav) = SwitchWatchCalc.anchor(points, soldAtEpochDay) ?: return
        if (candidate.id in anchoredCandidates) return
        try {
            switchWatchRepository.setNavAtStart(candidate.id, nav, epochDay)
            anchoredCandidates += candidate.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Inga belopp i loggen; nästa försök kommer med nästa emission.
        }
    }

    /**
     * Risknivå (UI-10) och avgift per kandidat ur metadatacachen. Eget flöde, inte inne i
     * tillståndets `map` — samma skäl som i Fonddetalj: uppslaget kan gå till nätverket, och låg
     * det i flödet blockerades hela skärmen på det.
     */
    private suspend fun ensureMetadata(watch: SwitchWatch) {
        val isins = (watch.candidates.map { it.isin } + watch.sellIsin).distinct().sorted()
        if (isins.isEmpty() || metadata.value.keys.containsAll(isins)) return
        metadata.value = fundMetadataRepository.metadataFor(isins)
    }

    /** Avbryter bevakningen utan köp — raden ligger kvar, den är användardata (ANA-12). */
    fun onCancel() {
        viewModelScope.launch {
            switchWatchRepository.close(watchId, SwitchWatchCloseReason.AVBRUTEN, LocalDate.now())
        }
    }

    fun onRemoveCandidate(candidateId: Long) {
        viewModelScope.launch { switchWatchRepository.removeCandidate(candidateId) }
    }

    /**
     * Lägger till en handplockad kandidat (ANA-13). Fondsöks katalogträffar saknar ofta ISIN
     * (TP-21), och utan ISIN går fonden varken att hämta kurser för eller att jämföra — då säger
     * skärmen det i stället för att lägga till en rad som aldrig kan visa någon utveckling.
     */
    fun onAddCandidate(fund: Fund) {
        viewModelScope.launch {
            val isin = fund.isin ?: fundPriceRepository.suggestIsin(fund.name)
            if (isin == null) {
                message.value = SwitchWatchMessage.IsinUnavailable
                return@launch
            }
            val added = switchWatchRepository.addCandidates(
                watchId,
                listOf(
                    SwitchWatchCandidate(
                        watchId = watchId,
                        isin = isin,
                        name = fund.name,
                        source = SwitchWatchCandidateSource.MANUELL,
                    ),
                ),
            )
            if (added == 0) message.value = SwitchWatchMessage.CandidateLimitReached
        }
    }

    fun onMessageShown() {
        message.value = null
    }

    /** Skapar ViewModeln för en given bevakning (assisted injection, `hiltViewModel` med `creationCallback`). */
    @AssistedFactory
    interface Factory {
        fun create(watchId: Long): SwitchWatchViewModel
    }

    companion object {
        /**
         * Hur långt tillbaka säljfondens kurva hämtas ur cachen. Samma horisont som ANA-11:s
         * jämförelse: täcker diagrammets längsta fasta period med marginal, utan att dra in en
         * historik ingen tittar på i en vy som handlar om de senaste dagarna.
         */
        private const val CHART_HISTORY_YEARS = 3L
    }
}

/** Ett begärt val av nivå och/eller filter — null = rörs inte. */
private data class Selection(val level: Int? = null, val indexOnly: Boolean? = null) {
    /** [newer] vinner där den har ett värde; resten av det här valet ligger kvar. */
    fun merge(newer: Selection) = Selection(level = newer.level ?: level, indexOnly = newer.indexOnly ?: indexOnly)
}

/** Profilens förslag: räknat (null = ingen profil eller ingen underviktad nivå) eller misslyckat. */
private sealed interface ProfileSuggestion {
    data class Computed(val gap: SwitchPlanCalc.LevelGap?) : ProfileSuggestion
    data object Failed : ProfileSuggestion
}

/** Utfallet av att fylla i en saknad målnivå ([SwitchWatchViewModel.fillMissingTargetLevel]). */
private sealed interface MissingLevel {
    data class Filled(val level: Int) : MissingLevel
    data object Unknown : MissingLevel
    data object Failed : MissingLevel
}

/** Tillståndet som inte bor i databasen, samlat för `combine` (som tar högst fem flöden). */
private data class Transient(
    val filling: Boolean,
    val reason: SwitchWatchEmptyReason?,
    val message: SwitchWatchMessage?,
    val suggestion: SwitchPlanCalc.LevelGap?,
)
