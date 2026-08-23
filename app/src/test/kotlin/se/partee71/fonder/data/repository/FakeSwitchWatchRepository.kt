package se.partee71.fonder.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import java.time.LocalDate

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

    override fun observeOpen(): Flow<List<SwitchWatch>> =
        watches.map { all -> all.filter { it.isOpen }.sortedByDescending { it.soldAtEpochDay } }

    override fun observe(id: Long): Flow<SwitchWatch?> =
        watches.map { all -> all.firstOrNull { it.id == id } }

    override suspend fun getAll(): List<SwitchWatch> = watches.value

    override suspend fun hasOpenFor(sellIsin: String, soldAtEpochDay: Long): Boolean =
        watches.value.any { it.isOpen && it.sellIsin == sellIsin && it.soldAtEpochDay == soldAtEpochDay }

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
                },
            )
        }
    }

    override suspend fun close(watchId: Long, reason: SwitchWatchCloseReason, today: LocalDate, boughtIsin: String?) {
        update(watchId) { watch ->
            if (!watch.isOpen) {
                watch
            } else {
                watch.copy(
                    closedAtEpochDay = today.toEpochDay(),
                    boughtIsin = if (reason == SwitchWatchCloseReason.KOPT) boughtIsin else null,
                    closeReason = reason,
                )
            }
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
