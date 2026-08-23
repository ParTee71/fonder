package se.partee71.fonder.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import se.partee71.fonder.data.room.AppDatabase
import se.partee71.fonder.data.room.daos.SwitchWatchDao
import se.partee71.fonder.data.room.entities.SwitchWatchCandidateEntity
import se.partee71.fonder.data.room.entities.SwitchWatchWithCandidates
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kontrakt för det pågående bytet (ANA-12, issue #114) — perioden mellan sälj och köp, med de
 * alternativ användaren bevakar under tiden. Se [SwitchWatch] för varför innehållet är genuin
 * användardata och ingår i backup-kontraktet (NFR-1).
 */
interface SwitchWatchRepository {

    /** De öppna bevakningarna, senast sålda först — Hems kort (HEM-11) och startgatorna. */
    fun observeOpen(): Flow<List<SwitchWatch>>

    /** En enskild bevakning med sina kandidater, reaktivt. Null när den inte finns. */
    fun observe(id: Long): Flow<SwitchWatch?>

    /** Hela historiken inklusive stängda — backup-kontraktets läsväg, ingen skärm läser den. */
    suspend fun getAll(): List<SwitchWatch>

    /** Sant om säljet redan har en öppen bevakning — gatar starterbjudandet (SLD-5). */
    suspend fun hasOpenFor(sellIsin: String, soldAtEpochDay: Long): Boolean

    /** Startar en bevakning och returnerar dess id. Kandidaterna i [watch] skrivs med. */
    suspend fun start(watch: SwitchWatch): Long

    /**
     * Lägger till kandidater sist i listan. Överskjutande rader utöver
     * [SwitchWatchCalc.MAX_CANDIDATES] avvisas — taket är inte kosmetiskt: varje kandidat
     * kostar ett historikanrop mot en odokumenterad källa (TP-14) varje gång skärmen öppnas.
     *
     * @return antalet kandidater som faktiskt lades till.
     */
    suspend fun addCandidates(watchId: Long, candidates: List<SwitchWatchCandidate>): Int

    suspend fun removeCandidate(candidateId: Long)

    /** Ankrar kandidatens nollpunkt en gång, se [SwitchWatchCandidate.navAtStart]. */
    suspend fun setNavAtStart(candidateId: Long, nav: Double, epochDay: Long)

    /**
     * Stänger bevakningen. [boughtIsin] bara vid [SwitchWatchCloseReason.KOPT] — de andra
     * avsluten vet per definition inte vad som köptes, och att skriva ett ISIN där hade
     * påstått ett köp som aldrig kvitterats.
     */
    suspend fun close(watchId: Long, reason: SwitchWatchCloseReason, today: LocalDate, boughtIsin: String? = null)

    /**
     * Stänger öppna bevakningar som passerat [SwitchWatchCalc.WATCH_TTL_DAYS] som
     * [SwitchWatchCloseReason.UTGANGEN] — de raderas aldrig. Ett fondbyte tar dagar, inte
     * veckor; en bevakning som legat orörd längre än så beskriver ett läge som inte finns
     * längre (samma princip som `SwitchPlanCalc.PLAN_TTL_DAYS`).
     *
     * @return antalet stängda bevakningar.
     */
    suspend fun expireStale(today: LocalDate): Int

    /** Ersätter hela innehållet — återställningens skrivväg (SET-6), aldrig en sammanslagning. */
    suspend fun replaceAll(watches: List<SwitchWatch>)
}

@Singleton
class RoomSwitchWatchRepository @Inject constructor(
    private val database: AppDatabase,
    private val dao: SwitchWatchDao,
) : SwitchWatchRepository {

    override fun observeOpen(): Flow<List<SwitchWatch>> =
        dao.observeOpen().map { list -> list.map(SwitchWatchWithCandidates::toDomain) }

    override fun observe(id: Long): Flow<SwitchWatch?> =
        dao.observeById(id).map { it?.toDomain() }

    override suspend fun getAll(): List<SwitchWatch> =
        dao.getAll().map(SwitchWatchWithCandidates::toDomain)

    override suspend fun hasOpenFor(sellIsin: String, soldAtEpochDay: Long): Boolean =
        dao.hasOpenFor(sellIsin, soldAtEpochDay)

    override suspend fun start(watch: SwitchWatch): Long {
        val row = SwitchWatchWithCandidates.fromDomain(watch)
        // Bevakningen och dess kandidater i **en** transaktion: en avbruten start ska inte
        // kunna lämna en tom bevakning som ser ut som "inga alternativ hittades".
        return database.withTransaction {
            val id = dao.insertWatch(row.watch.copy(id = 0))
            val candidates = watch.candidates
                .take(SwitchWatchCalc.MAX_CANDIDATES)
                .mapIndexed { index, candidate ->
                    SwitchWatchCandidateEntity.fromDomain(candidate.copy(id = 0, position = index), watchId = id)
                }
            if (candidates.isNotEmpty()) dao.insertCandidates(candidates)
            id
        }
    }

    override suspend fun addCandidates(watchId: Long, candidates: List<SwitchWatchCandidate>): Int {
        if (candidates.isEmpty()) return 0
        return database.withTransaction {
            val existing = dao.getById(watchId) ?: return@withTransaction 0
            val room = SwitchWatchCalc.MAX_CANDIDATES - existing.candidates.size
            if (room <= 0) return@withTransaction 0

            val alreadyWatched = existing.candidates.mapTo(mutableSetOf()) { it.isin }
            val nextPosition = (existing.candidates.maxOfOrNull { it.position } ?: -1) + 1
            val toAdd = candidates
                .filterNot { it.isin in alreadyWatched || it.isin == existing.watch.sellIsin }
                .distinctBy { it.isin }
                .take(room)
                .mapIndexed { index, candidate ->
                    SwitchWatchCandidateEntity.fromDomain(
                        candidate.copy(id = 0, position = nextPosition + index),
                        watchId = watchId,
                    )
                }
            if (toAdd.isNotEmpty()) dao.insertCandidates(toAdd)
            toAdd.size
        }
    }

    override suspend fun removeCandidate(candidateId: Long) {
        dao.deleteCandidateById(candidateId)
    }

    override suspend fun setNavAtStart(candidateId: Long, nav: Double, epochDay: Long) {
        dao.setNavAtStart(candidateId, nav, epochDay)
    }

    override suspend fun close(watchId: Long, reason: SwitchWatchCloseReason, today: LocalDate, boughtIsin: String?) {
        dao.close(
            id = watchId,
            closedAtEpochDay = today.toEpochDay(),
            boughtIsin = if (reason == SwitchWatchCloseReason.KOPT) boughtIsin else null,
            closeReason = reason.name,
        )
    }

    override suspend fun expireStale(today: LocalDate): Int {
        val stale = dao.getOpen()
            .map(SwitchWatchWithCandidates::toDomain)
            .filter { SwitchWatchCalc.isExpired(it, today) }
        stale.forEach { close(it.id, SwitchWatchCloseReason.UTGANGEN, today) }
        return stale.size
    }

    override suspend fun replaceAll(watches: List<SwitchWatch>) {
        database.withTransaction {
            dao.deleteAll()
            watches.forEach { watch ->
                val row = SwitchWatchWithCandidates.fromDomain(watch)
                val id = dao.insertWatch(row.watch)
                // Kandidaternas `watchId` sätts om till det id insättningen faktiskt gav:
                // bevaras id:t (som normalt) är de identiska, men en fil med krockande id:n
                // ska ge kandidater som hänger ihop med sin bevakning, inte föräldralösa rader.
                val candidates = row.candidates.map { it.copy(watchId = id) }
                if (candidates.isNotEmpty()) dao.insertCandidates(candidates)
            }
        }
    }
}
