package se.partee71.fonder.data.repository

import androidx.room.withTransaction
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import se.partee71.fonder.data.room.AppDatabase
import se.partee71.fonder.data.room.daos.SwitchWatchDao
import se.partee71.fonder.data.room.entities.SwitchWatchCandidateEntity
import se.partee71.fonder.data.room.entities.SwitchWatchWithCandidates
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCandidateSource
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.usecase.SwitchWatchCalc

/**
 * Kontrakt för det pågående bytet (ANA-12, issue #114) — perioden mellan sälj och köp, med de
 * alternativ användaren bevakar under tiden. Se [SwitchWatch] för varför innehållet är genuin
 * användardata och ingår i backup-kontraktet (NFR-1).
 */
interface SwitchWatchRepository {

    /** De öppna bevakningarna, senast sålda först — Hems kort (HEM-11) och startgatorna. */
    fun observeOpen(): Flow<List<SwitchWatch>>

    /**
     * Bevakningar stängda på eller efter [epochDay], senast stängda först — Besluts sektion
     * "Avslutade och avstådda" (BYT-4) visar dem i [SwitchWatchCalc.WATCH_TTL_DAYS] dagar.
     */
    fun observeClosedSince(epochDay: Long): Flow<List<SwitchWatch>>

    /** En enskild bevakning med sina kandidater, reaktivt. Null när den inte finns. */
    fun observe(id: Long): Flow<SwitchWatch?>

    /** Hela historiken inklusive stängda — backup-kontraktets läsväg, ingen skärm läser den. */
    suspend fun getAll(): List<SwitchWatch>

    /** Sant om säljet (fond och dag) redan har en öppen bevakning, oavsett koppling — gaten för en bevakning ur ett förslag. */
    suspend fun hasOpenFor(sellIsin: String, soldAtEpochDay: Long): Boolean

    /**
     * Den öppna bevakning av säljfonden utan känd transaktion med **samma** säljdag som
     * [soldAtEpochDay] — äldst om flera, null om ingen; även en utgången som bakgrundskörningen
     * ännu inte stängt, anroparen avgör vad den betyder. Se [linkSellTransaction].
     */
    suspend fun findOpenWithoutTransactionFor(sellIsin: String, soldAtEpochDay: Long): SwitchWatch?

    /**
     * Kopplar en bevakning utan känd transaktion till säljtransaktionen (BYT-1) och sätter
     * likviden ur den. En bevakning startad ur ett förslag samma dag som säljet blir därmed
     * säljets bevakning i stället för att säljet får en andra. Utan [soldAtEpochDay] rörs varken
     * säljdagen eller kandidaternas nollpunkt.
     *
     * Med [soldAtEpochDay] (en annan dag än bevakningens — efter att användaren tillfrågats,
     * issue #144) flyttas säljdagen dit och kandidaternas nollpunkt nollas i samma
     * databastransaktion: nollpunkten är ankrad på den gamla dagen och mäter annars en utveckling
     * från fel dag. Kandidaterna själva — även de manuellt tillagda — ligger kvar. Är dagen
     * densamma som bevakningens rörs ingen nollpunkt.
     *
     * Med [sourceRecordId] tar en bevakning **utan** förslag över planraden (och [targetLevel])
     * när den kopplas — säljet var för raden, och bevakningen ska bli radens pågående byte (BYT-1).
     * En bevakning som redan har ett förslag rörs inte. En stängd bevakning kopplas aldrig.
     *
     * @return sant när länken skrevs nu; falskt när bevakningen redan var kopplad, stängd eller inte finns.
     */
    suspend fun linkSellTransaction(
        watchId: Long,
        transactionId: Long,
        proceedsKr: Double,
        soldAtEpochDay: Long? = null,
        sourceRecordId: Long? = null,
        targetLevel: Int? = null
    ): Boolean

    /**
     * Fyller i [SwitchWatch.targetLevel] i efterhand för en bevakning som startades utan känd
     * nivå (ANA-13, issue #149) — skriver bara när nivån saknas, en befintlig nivå rörs aldrig.
     *
     * @return sant när nivån skrevs nu; falskt när den redan fanns eller bevakningen inte finns.
     */
    suspend fun fillMissingTargetLevel(watchId: Long, targetLevel: Int): Boolean

    /**
     * Användarens val av målnivå på skärmen (ANA-13, issue #150). I **en** databastransaktion:
     * nivån skrivs och appens förslag ([SwitchWatchCandidateSource.AUTO]) tas bort — de hörde till
     * den gamla nivån — medan de handplockade ligger kvar. Anroparen fyller sedan på med förslag på
     * den nya nivån ([addAutoCandidates]). Bara en öppen bevakning, och bara när nivån faktiskt ändras.
     *
     * @return sant när nivån ändrades nu; falskt när den redan var den, bevakningen är stängd eller inte finns.
     */
    suspend fun setTargetLevel(watchId: Long, targetLevel: Int): Boolean

    /** Filtret "bara indexfonder" ([SwitchWatch.indexOnly], issue #150) — samma flöde och villkor som [setTargetLevel]. */
    suspend fun setIndexOnly(watchId: Long, indexOnly: Boolean): Boolean

    /** Sant om säljtransaktionen redan har en öppen bevakning (BYT-1) — en bevakning per sälj, oavsett startväg. */
    suspend fun hasOpenForTransaction(sellTransactionId: Long): Boolean

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

    /**
     * Som [addCandidates], för appens förslag hämtade för [targetLevel] och [indexOnly] — skrivs
     * **bara** om bevakningen fortfarande är öppen med just den nivån och det filtret, prövat i samma
     * databastransaktion som insättningen. Ett svar på en sökning som hann bli inaktuell (användaren
     * bytte nivå medan källan svarade) läggs alltså aldrig till på fel nivå (issue #150).
     *
     * @return antalet kandidater som faktiskt lades till; 0 när valet ändrats under tiden.
     */
    suspend fun addAutoCandidates(watchId: Long, targetLevel: Int, indexOnly: Boolean, candidates: List<SwitchWatchCandidate>): Int

    suspend fun removeCandidate(candidateId: Long)

    /** Ankrar kandidatens nollpunkt en gång, se [SwitchWatchCandidate.navAtStart]. */
    suspend fun setNavAtStart(candidateId: Long, nav: Double, epochDay: Long)

    /**
     * Stänger bevakningen. [boughtIsin] och [buyTransactionId] bara vid
     * [SwitchWatchCloseReason.KOPT] — de andra avsluten vet per definition inte vad som köptes,
     * och att skriva ett ISIN eller en transaktion där hade påstått ett köp som aldrig gjordes.
     *
     * @return sant när **det här** anropet stängde den; falskt när den redan var stängd eller
     *   inte finns. Ett andra avslut skriver aldrig över det första.
     */
    suspend fun close(
        watchId: Long,
        reason: SwitchWatchCloseReason,
        today: LocalDate,
        boughtIsin: String? = null,
        buyTransactionId: Long? = null
    ): Boolean

    /** Nollar [SwitchWatch.sellTransactionId]/[SwitchWatch.buyTransactionId] som pekar på en borttagen transaktion (TRX-2). */
    suspend fun clearTransactionLinks(transactionId: Long)

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
    private val dao: SwitchWatchDao
) : SwitchWatchRepository {

    override fun observeOpen(): Flow<List<SwitchWatch>> = dao.observeOpen().map { list -> list.map(SwitchWatchWithCandidates::toDomain) }

    override fun observeClosedSince(epochDay: Long): Flow<List<SwitchWatch>> = dao.observeClosedSince(epochDay).map { list -> list.map(SwitchWatchWithCandidates::toDomain) }

    override fun observe(id: Long): Flow<SwitchWatch?> = dao.observeById(id).map { it?.toDomain() }

    override suspend fun getAll(): List<SwitchWatch> = dao.getAll().map(SwitchWatchWithCandidates::toDomain)

    override suspend fun hasOpenFor(sellIsin: String, soldAtEpochDay: Long): Boolean = dao.hasOpenFor(sellIsin, soldAtEpochDay)

    override suspend fun findOpenWithoutTransactionFor(sellIsin: String, soldAtEpochDay: Long): SwitchWatch? = dao.getOpenWithoutTransactionFor(sellIsin, soldAtEpochDay).firstOrNull()?.toDomain()

    override suspend fun linkSellTransaction(
        watchId: Long,
        transactionId: Long,
        proceedsKr: Double,
        soldAtEpochDay: Long?,
        sourceRecordId: Long?,
        targetLevel: Int?
    ): Boolean = database.withTransaction {
        val current = dao.getById(watchId)?.watch ?: return@withTransaction false
        val linked = if (soldAtEpochDay == null || soldAtEpochDay == current.soldAtEpochDay) {
            dao.linkSellTransaction(watchId, transactionId, proceedsKr) == 1
        } else {
            // Länk, dag och nollpunkt i **en** transaktion: en flyttad dag med gamla nollpunkter
            // kvar hade mätt kandidaterna från fel dag tills nästa hämtning råkade skriva om dem.
            val moved = dao.linkSellTransactionOnDay(watchId, transactionId, proceedsKr, soldAtEpochDay) == 1
            if (moved) dao.resetCandidateAnchors(watchId)
            moved
        }
        if (linked && sourceRecordId != null && targetLevel != null) dao.adoptSuggestion(watchId, sourceRecordId, targetLevel)
        linked
    }

    override suspend fun hasOpenForTransaction(sellTransactionId: Long): Boolean = dao.hasOpenForTransaction(sellTransactionId)

    override suspend fun fillMissingTargetLevel(watchId: Long, targetLevel: Int): Boolean = dao.fillMissingTargetLevel(watchId, targetLevel) == 1

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

    override suspend fun setTargetLevel(watchId: Long, targetLevel: Int): Boolean = changeSelection(watchId) { dao.setTargetLevel(watchId, targetLevel) }

    override suspend fun setIndexOnly(watchId: Long, indexOnly: Boolean): Boolean = changeSelection(watchId) { dao.setIndexOnly(watchId, indexOnly) }

    /** Valet och borttagningen av appens förslag i **en** transaktion — aldrig en ny nivå med den gamlas förslag kvar. */
    private suspend fun changeSelection(watchId: Long, write: suspend () -> Int): Boolean = database.withTransaction {
        val changed = write() == 1
        if (changed) dao.deleteAutoCandidates(watchId, SwitchWatchCandidateSource.MANUELL.name)
        changed
    }

    override suspend fun addCandidates(watchId: Long, candidates: List<SwitchWatchCandidate>): Int {
        if (candidates.isEmpty()) return 0
        return database.withTransaction {
            val existing = dao.getById(watchId) ?: return@withTransaction 0
            insertWithinCap(existing, candidates)
        }
    }

    override suspend fun addAutoCandidates(watchId: Long, targetLevel: Int, indexOnly: Boolean, candidates: List<SwitchWatchCandidate>): Int {
        if (candidates.isEmpty()) return 0
        return database.withTransaction {
            val existing = dao.getById(watchId) ?: return@withTransaction 0
            val current = existing.watch
            val stillChosen = current.closedAtEpochDay == null && current.targetLevel == targetLevel && current.indexOnly == indexOnly
            if (!stillChosen) return@withTransaction 0
            insertWithinCap(existing, candidates)
        }
    }

    /** Lägger [candidates] sist under taket, utan dubbletter och utan säljfonden. Körs inuti anroparens transaktion. */
    private suspend fun insertWithinCap(existing: SwitchWatchWithCandidates, candidates: List<SwitchWatchCandidate>): Int {
        val room = SwitchWatchCalc.MAX_CANDIDATES - existing.candidates.size
        if (room <= 0) return 0

        val alreadyWatched = existing.candidates.mapTo(mutableSetOf()) { it.isin }
        val nextPosition = (existing.candidates.maxOfOrNull { it.position } ?: -1) + 1
        val toAdd = candidates
            .filterNot { it.isin in alreadyWatched || it.isin == existing.watch.sellIsin }
            .distinctBy { it.isin }
            .take(room)
            .mapIndexed { index, candidate ->
                SwitchWatchCandidateEntity.fromDomain(
                    candidate.copy(id = 0, position = nextPosition + index),
                    watchId = existing.watch.id
                )
            }
        if (toAdd.isNotEmpty()) dao.insertCandidates(toAdd)
        return toAdd.size
    }

    override suspend fun removeCandidate(candidateId: Long) {
        dao.deleteCandidateById(candidateId)
    }

    override suspend fun setNavAtStart(candidateId: Long, nav: Double, epochDay: Long) {
        dao.setNavAtStart(candidateId, nav, epochDay)
    }

    override suspend fun close(
        watchId: Long,
        reason: SwitchWatchCloseReason,
        today: LocalDate,
        boughtIsin: String?,
        buyTransactionId: Long?
    ): Boolean {
        val bought = reason == SwitchWatchCloseReason.KOPT
        return dao.close(
            id = watchId,
            closedAtEpochDay = today.toEpochDay(),
            boughtIsin = if (bought) boughtIsin else null,
            closeReason = reason.name,
            buyTransactionId = if (bought) buyTransactionId else null
        ) == 1
    }

    override suspend fun clearTransactionLinks(transactionId: Long) {
        dao.clearTransactionLinks(transactionId)
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
