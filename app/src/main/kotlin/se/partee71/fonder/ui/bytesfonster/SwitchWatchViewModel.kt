package se.partee71.fonder.ui.bytesfonster

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.SwitchWatchRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCandidateSource
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import java.time.LocalDate
import javax.inject.Inject

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
    val rows: List<CandidateRow> = emptyList(),
    /** Säljfondens egen kurva — diagrammets primärserie, det man bytte *från*. Tom om historiken saknas. */
    val sellSeries: List<Pair<Long, Double>> = emptyList(),
    /** Kandidatkurvor i samma ordning som [rows], bara de som gick att hämta. */
    val candidateSeries: List<Pair<String, List<Pair<Long, Double>>>> = emptyList(),
    /** Sant medan appens egna kandidatförslag hämtas (ANA-13) — en källfråga mot nivån. */
    val fillingCandidates: Boolean = false,
    val message: SwitchWatchMessage? = null,
) {
    val canAddCandidate: Boolean get() = !closed && rows.size < SwitchWatchCalc.MAX_CANDIDATES
    val hasChart: Boolean get() = sellSeries.isNotEmpty() || candidateSeries.isNotEmpty()

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
 * Appen **utför aldrig ett byte**: "Köpte den här" registrerar vad användaren gjort, precis som
 * kvitteringen i bytesplanen (SET-5).
 */
@HiltViewModel
class SwitchWatchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val switchWatchRepository: SwitchWatchRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val transactionRepository: TransactionRepository,
    private val suggestionRecordRepository: SuggestionRecordRepository,
) : ViewModel() {

    private val watchId: Long = checkNotNull(savedStateHandle.get<String>(ARG_WATCH_ID)).toLong()

    private val metadata = MutableStateFlow<Map<String, FundMetadata>>(emptyMap())
    private val histories = MutableStateFlow<Map<String, CandidateHistory>>(emptyMap())
    private val sellSeries = MutableStateFlow<List<Pair<Long, Double>>>(emptyList())
    private val fillingCandidates = MutableStateFlow(false)
    private val message = MutableStateFlow<SwitchWatchMessage?>(null)

    /** ISIN vars historik redan hämtats (eller pågår) — varje kandidat hämtas en gång per skärmlivstid. */
    private val requestedHistories = mutableSetOf<String>()
    private var autoFillAttempted = false
    private var sellSeriesLoaded = false

    private val watch: Flow<SwitchWatch?> = switchWatchRepository.observe(watchId)

    val uiState: StateFlow<SwitchWatchUiState> = combine(
        watch,
        metadata,
        histories,
        sellSeries,
        combine(fillingCandidates, message) { filling, msg -> filling to msg },
    ) { watch, metadataByIsin, historyByIsin, sellPoints, (filling, msg) ->
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
            rows = rows,
            sellSeries = sellPoints,
            // Kurvorna följer radordningen, så teckenförklaringen läser i samma ordning som
            // listan under den — en kurva vars rad ligger femma i listan men tvåa i förklaringen
            // hade varit två svar på samma fråga.
            candidateSeries = rows.mapNotNull { row ->
                (historyByIsin[row.isin] as? CandidateHistory.Ready)?.let { row.name to it.points }
            },
            fillingCandidates = filling,
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
     * (ANA-13) hör till bevakningens start, och att byta ut alternativen mitt under en pågående
     * jämförelse vore ett annat svar på en fråga användaren redan ställt.
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
     * Fyller listan med appens egna förslag (ANA-13) — samma pool och samma rangordning som
     * bytesplanens köpkandidater ([FundMetadataRepository.findSwitchCandidates]), alltså översta
     * kvartilen på 12-månadersavkastning och därefter lägst avgift.
     *
     * Görs **en gång** per skärmlivstid och bara när listan är tom: en omfyllning vid varje
     * emission hade kostat en källfråga per gång och kunnat byta ut de alternativ användaren
     * just tittade på. Utan känd målnivå görs ingenting alls — en gissad nivå hade gett förslag
     * på fel risk, vilket är sämre än inga förslag (ANA-4-principen).
     */
    private suspend fun ensureAutoCandidates(watch: SwitchWatch) {
        if (autoFillAttempted || !watch.isOpen || watch.candidates.isNotEmpty()) return
        val level = watch.targetLevel ?: return
        autoFillAttempted = true

        fillingCandidates.value = true
        try {
            val owned = transactionRepository.observeFunds().first().mapNotNull { it.isin }.toSet()
            val candidates = fundMetadataRepository.findSwitchCandidates(level, owned + watch.sellIsin)
                .take(SwitchWatchCalc.AUTO_CANDIDATES)
                .mapIndexed { index, candidate ->
                    SwitchWatchCandidate(
                        watchId = watch.id,
                        isin = candidate.metadata.isin,
                        name = candidate.metadata.name,
                        source = SwitchWatchCandidateSource.AUTO,
                        position = index,
                    )
                }
            if (candidates.isNotEmpty()) switchWatchRepository.addCandidates(watch.id, candidates)
        } finally {
            fillingCandidates.value = false
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
        watch.candidates.forEach { candidate ->
            if (!requestedHistories.add(candidate.isin)) return@forEach
            histories.value = histories.value + (candidate.isin to CandidateHistory.Loading)
            viewModelScope.launch {
                val today = LocalDate.now()
                val from = LocalDate.ofEpochDay(watch.soldAtEpochDay).minusDays(SwitchWatchCalc.ANCHOR_LOOKBACK_DAYS)
                val points = fundPriceRepository.historyForIsin(candidate.isin, from, today)
                    .map { it.epochDay to it.nav }
                    .sortedBy { it.first }

                histories.value = histories.value + (
                    candidate.isin to if (points.isEmpty()) CandidateHistory.Unavailable else CandidateHistory.Ready(points)
                    )

                if (candidate.navAtStart == null) {
                    SwitchWatchCalc.anchor(points, watch.soldAtEpochDay)?.let { (epochDay, nav) ->
                        switchWatchRepository.setNavAtStart(candidate.id, nav, epochDay)
                    }
                }
            }
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

    /**
     * Kvitterar att kandidaten köptes och stänger bevakningen (ANA-12). Utför inget köp — appen
     * rör aldrig innehav; transaktionen registreras som vanligt (TRX-1).
     *
     * Startades bevakningen ur ett bytesförslag (HEM-8) markeras förslaget som genomfört **bara**
     * när det faktiskt var den föreslagna fonden som köptes. Köptes en annan lämnas raden orörd:
     * facit (SET-5) mäter följda råd, och ett byte till något annat än det föreslagna är inte
     * rådet som följdes.
     */
    fun onBought(isin: String) {
        viewModelScope.launch {
            val current = switchWatchRepository.observe(watchId).first() ?: return@launch
            switchWatchRepository.close(watchId, SwitchWatchCloseReason.KOPT, LocalDate.now(), isin)

            val recordId = current.sourceRecordId ?: return@launch
            val record = suggestionRecordRepository.observeHistory().first().firstOrNull { it.id == recordId }
            if (record != null && record.buyIsin == isin) suggestionRecordRepository.setFollowed(recordId, true)
        }
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

    companion object {
        /** Navigationsargumentets namn — delat med [se.partee71.fonder.ui.navigation.Routes]. */
        const val ARG_WATCH_ID = "watchId"

        /**
         * Hur långt tillbaka säljfondens kurva hämtas ur cachen. Samma horisont som ANA-11:s
         * jämförelse: täcker diagrammets längsta fasta period med marginal, utan att dra in en
         * historik ingen tittar på i en vy som handlar om de senaste dagarna.
         */
        private const val CHART_HISTORY_YEARS = 3L
    }
}
