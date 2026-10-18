package se.partee71.fonder.data.repository

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCandidateSource
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.usecase.SwitchWatchCalc

/**
 * In-memory-fake av [SwitchWatchRepository] (ANA-12, issue #114) — delad mellan Hems, Sålda
 * fonders, Fonddetaljs, bevakningsskärmens och bakgrundskörningens tester.
 *
 * En **gemensam** fake, till skillnad från de inbäddade fakes varje test annars håller själv:
 * kontraktet har elva metoder och fem läsare, och fem kopior hade blivit fem ställen att glömma
 * när gränssnittet ändras (regel 2 — en fake som slutat matcha sitt interface bryter testbygget
 * i stället för att fånga något).
 *
 * Beteendet speglar Room-implementationen där det spelar roll för testerna: id:n delas ut
 * löpande, kandidater får löpande position sist i listan, taket
 * ([SwitchWatchCalc.MAX_CANDIDATES]) gäller, och en redan bevakad kandidat läggs inte till igen.
 */
class FakeSwitchWatchRepository : SwitchWatchRepository {

    val watches = MutableStateFlow<List<SwitchWatch>>(emptyList())

    private var nextWatchId = 1L
    private var nextCandidateId = 1L

    override fun observeOpen(): Flow<List<SwitchWatch>> = watches.map { all -> all.filter { it.isOpen }.sortedByDescending { it.soldAtEpochDay } }

    override fun observeClosedSince(epochDay: Long): Flow<List<SwitchWatch>> = watches.map { all ->
        all.filter { (it.closedAtEpochDay ?: Long.MIN_VALUE) >= epochDay }
            .sortedWith(compareByDescending<SwitchWatch> { it.closedAtEpochDay }.thenByDescending { it.id })
    }

    override fun observe(id: Long): Flow<SwitchWatch?> = watches.map { all -> all.firstOrNull { it.id == id } }

    override suspend fun getAll(): List<SwitchWatch> = watches.value

    override suspend fun hasOpenFor(sellIsin: String, soldAtEpochDay: Long): Boolean = watches.value.any { it.isOpen && it.sellIsin == sellIsin && it.soldAtEpochDay == soldAtEpochDay }

    override suspend fun findOpenWithoutTransactionFor(sellIsin: String, soldAtEpochDay: Long): SwitchWatch? = watches.value
        .filter { it.isOpen && it.sellTransactionId == null && it.sellIsin == sellIsin && it.soldAtEpochDay == soldAtEpochDay }
        .minByOrNull { it.id }

    /** Sätt till `false` för att spela "någon annan kopplade först": [linkSellTransaction] rör inget och returnerar falskt. */
    var linkResult: Boolean? = null

    override suspend fun linkSellTransaction(
        watchId: Long,
        transactionId: Long,
        proceedsKr: Double,
        soldAtEpochDay: Long?,
        sourceRecordId: Long?,
        targetLevel: Int?
    ): Boolean {
        linkResult?.let { return it }
        val current = watches.value.firstOrNull { it.id == watchId } ?: return false
        if (current.sellTransactionId != null || !current.isOpen) return false
        update(watchId) { watch ->
            val moved = soldAtEpochDay != null && soldAtEpochDay != watch.soldAtEpochDay
            val adopt = watch.sourceRecordId == null && sourceRecordId != null && targetLevel != null
            watch.copy(
                sellTransactionId = transactionId,
                proceedsKr = proceedsKr,
                soldAtEpochDay = soldAtEpochDay ?: watch.soldAtEpochDay,
                sourceRecordId = if (adopt) sourceRecordId else watch.sourceRecordId,
                targetLevel = if (adopt) targetLevel else watch.targetLevel,
                // Som Room: en flyttad dag nollar bara nollpunkten, kandidaterna ligger kvar.
                candidates = if (moved) watch.candidates.map { it.copy(navAtStart = null, navAtStartEpochDay = null) } else watch.candidates
            )
        }
        return true
    }

    override suspend fun hasOpenForTransaction(sellTransactionId: Long): Boolean = watches.value.any { it.isOpen && it.sellTransactionId == sellTransactionId }

    /** Körs först i [fillMissingTargetLevel] — spelar en annan väg som hinner sätta nivån före skrivningen. */
    var beforeFillMissingTargetLevel: (Long) -> Unit = {}

    override suspend fun fillMissingTargetLevel(watchId: Long, targetLevel: Int): Boolean {
        beforeFillMissingTargetLevel(watchId)
        val current = watches.value.firstOrNull { it.id == watchId } ?: return false
        if (current.targetLevel != null) return false
        update(watchId) { it.copy(targetLevel = targetLevel) }
        return true
    }

    override suspend fun setTargetLevel(watchId: Long, targetLevel: Int): Boolean = changeSelection(watchId, { it.targetLevel == targetLevel }) { it.copy(targetLevel = targetLevel) }

    override suspend fun setIndexOnly(watchId: Long, indexOnly: Boolean): Boolean = changeSelection(watchId, { it.indexOnly == indexOnly }) { it.copy(indexOnly = indexOnly) }

    /** Som Room: bara en öppen bevakning och en verklig ändring; appens förslag går, de handplockade ligger kvar. */
    private fun changeSelection(watchId: Long, unchanged: (SwitchWatch) -> Boolean, change: (SwitchWatch) -> SwitchWatch): Boolean {
        val current = watches.value.firstOrNull { it.id == watchId } ?: return false
        if (!current.isOpen || unchanged(current)) return false
        update(watchId) { watch ->
            change(watch).copy(candidates = watch.candidates.filter { it.source == SwitchWatchCandidateSource.MANUELL })
        }
        return true
    }

    override suspend fun addAutoCandidates(watchId: Long, targetLevel: Int, indexOnly: Boolean, candidates: List<SwitchWatchCandidate>): Int {
        val current = watches.value.firstOrNull { it.id == watchId } ?: return 0
        if (!current.isOpen || current.targetLevel != targetLevel || current.indexOnly != indexOnly) return 0
        return addCandidates(watchId, candidates)
    }

    override suspend fun start(watch: SwitchWatch): Long {
        val id = nextWatchId++
        val candidates = watch.candidates
            .take(SwitchWatchCalc.MAX_CANDIDATES)
            .mapIndexed { index, candidate ->
                candidate.copy(id = nextCandidateId++, watchId = id, position = index)
            }
        watches.value = watches.value + watch.copy(id = id, candidates = candidates)
        return id
    }

    override suspend fun addCandidates(watchId: Long, candidates: List<SwitchWatchCandidate>): Int {
        val existing = watches.value.firstOrNull { it.id == watchId } ?: return 0
        val room = SwitchWatchCalc.MAX_CANDIDATES - existing.candidates.size
        if (room <= 0) return 0

        val alreadyWatched = existing.candidates.map { it.isin }.toSet()
        val nextPosition = (existing.candidates.maxOfOrNull { it.position } ?: -1) + 1
        val added = candidates
            .filterNot { it.isin in alreadyWatched || it.isin == existing.sellIsin }
            .distinctBy { it.isin }
            .take(room)
            .mapIndexed { index, candidate ->
                candidate.copy(id = nextCandidateId++, watchId = watchId, position = nextPosition + index)
            }
        if (added.isEmpty()) return 0
        update(watchId) { it.copy(candidates = it.candidates + added) }
        return added.size
    }

    override suspend fun removeCandidate(candidateId: Long) {
        watches.value = watches.value.map { watch ->
            watch.copy(candidates = watch.candidates.filterNot { it.id == candidateId })
        }
    }

    override suspend fun setNavAtStart(candidateId: Long, nav: Double, epochDay: Long) {
        watches.value = watches.value.map { watch ->
            watch.copy(
                candidates = watch.candidates.map { candidate ->
                    if (candidate.id == candidateId) {
                        candidate.copy(navAtStart = nav, navAtStartEpochDay = epochDay)
                    } else {
                        candidate
                    }
                }
            )
        }
    }

    /** Sätt till `false` för att spela "någon annan stängde först": [close] rör inget och returnerar falskt. */
    var closeResult: Boolean? = null

    override suspend fun close(
        watchId: Long,
        reason: SwitchWatchCloseReason,
        today: LocalDate,
        boughtIsin: String?,
        buyTransactionId: Long?
    ): Boolean {
        closeResult?.let { return it }
        val wasOpen = watches.value.firstOrNull { it.id == watchId }?.isOpen == true
        update(watchId) { watch ->
            if (!watch.isOpen) {
                watch
            } else {
                val bought = reason == SwitchWatchCloseReason.KOPT
                watch.copy(
                    closedAtEpochDay = today.toEpochDay(),
                    boughtIsin = if (bought) boughtIsin else null,
                    closeReason = reason,
                    buyTransactionId = if (bought) buyTransactionId else null
                )
            }
        }
        return wasOpen
    }

    override suspend fun clearTransactionLinks(transactionId: Long) {
        watches.value = watches.value.map { watch ->
            watch.copy(
                sellTransactionId = watch.sellTransactionId?.takeUnless { it == transactionId },
                buyTransactionId = watch.buyTransactionId?.takeUnless { it == transactionId }
            )
        }
    }

    override suspend fun expireStale(today: LocalDate): Int {
        val stale = watches.value.filter { SwitchWatchCalc.isExpired(it, today) }
        stale.forEach { close(it.id, SwitchWatchCloseReason.UTGANGEN, today) }
        return stale.size
    }

    override suspend fun replaceAll(watches: List<SwitchWatch>) {
        this.watches.value = watches
    }

    private fun update(watchId: Long, transform: (SwitchWatch) -> SwitchWatch) {
        watches.value = watches.value.map { if (it.id == watchId) transform(it) else it }
    }
}
