package se.partee71.fonder.data.room.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import se.partee71.fonder.data.room.entities.SwitchWatchCandidateEntity
import se.partee71.fonder.data.room.entities.SwitchWatchEntity
import se.partee71.fonder.data.room.entities.SwitchWatchWithCandidates

@Dao
interface SwitchWatchDao {

    /**
     * De öppna bevakningarna, senast sålda först (ANA-12) — Hems kort (HEM-11) och
     * startgatorna läser den här. Öppen = ingen kvittering och ingen utgång skriven; att en
     * öppen bevakning **passerat** färskhetsgränsen avgörs vid visning
     * ([se.partee71.fonder.domain.usecase.SwitchWatchCalc.isExpired]), inte i SQL: gränsen
     * beror på dagens datum och en fråga som tyst dolde raden hade gjort den omöjlig att
     * stänga för bakgrundskörningen.
     */
    @Transaction
    @Query("SELECT * FROM switch_watches WHERE closedAtEpochDay IS NULL ORDER BY soldAtEpochDay DESC, id DESC")
    fun observeOpen(): Flow<List<SwitchWatchWithCandidates>>

    /** En enskild bevakning, reaktivt — skärmens läsväg. Null när den raderats. */
    @Transaction
    @Query("SELECT * FROM switch_watches WHERE id = :id")
    fun observeById(id: Long): Flow<SwitchWatchWithCandidates?>

    /**
     * Hela tabellen inklusive stängda bevakningar — backup-kontraktets läsväg (NFR-1) och
     * migreringstesternas verifieringsfråga. Ingen skärm läser allt.
     */
    @Transaction
    @Query("SELECT * FROM switch_watches ORDER BY id ASC")
    suspend fun getAll(): List<SwitchWatchWithCandidates>

    /** En enskild bevakning som ett engångssvar — skrivvägarnas läsning före en ändring. */
    @Transaction
    @Query("SELECT * FROM switch_watches WHERE id = :id")
    suspend fun getById(id: Long): SwitchWatchWithCandidates?

    /** De öppna bevakningarna som ett engångssvar — bakgrundskörningens läsväg. */
    @Transaction
    @Query("SELECT * FROM switch_watches WHERE closedAtEpochDay IS NULL")
    suspend fun getOpen(): List<SwitchWatchWithCandidates>

    /**
     * Sant om säljet redan har en **öppen** bevakning. Nyckeln är fond + säljdag, inte bara
     * fonden: en fond kan säljas i omgångar, och två sälj samma dag är i praktiken ett byte.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM switch_watches
            WHERE sellIsin = :sellIsin AND soldAtEpochDay = :soldAtEpochDay AND closedAtEpochDay IS NULL
        )
        """,
    )
    suspend fun hasOpenFor(sellIsin: String, soldAtEpochDay: Long): Boolean

    @Insert
    suspend fun insertWatch(watch: SwitchWatchEntity): Long

    @Insert
    suspend fun insertCandidates(candidates: List<SwitchWatchCandidateEntity>)

    @Delete
    suspend fun deleteCandidate(candidate: SwitchWatchCandidateEntity)

    @Query("DELETE FROM switch_watch_candidates WHERE id = :id")
    suspend fun deleteCandidateById(id: Long)

    /** Ankrar en kandidats nollpunkt, se [SwitchWatchCandidateEntity.navAtStart]. Skrivs en gång. */
    @Query("UPDATE switch_watch_candidates SET navAtStart = :nav, navAtStartEpochDay = :epochDay WHERE id = :id")
    suspend fun setNavAtStart(id: Long, nav: Double, epochDay: Long)

    /**
     * Stänger en bevakning. Raden ligger kvar — den bär vad som faktiskt köptes, och det är
     * data ingen omräkning kan återskapa.
     */
    @Query(
        """
        UPDATE switch_watches
        SET closedAtEpochDay = :closedAtEpochDay, boughtIsin = :boughtIsin, closeReason = :closeReason
        WHERE id = :id AND closedAtEpochDay IS NULL
        """,
    )
    suspend fun close(id: Long, closedAtEpochDay: Long, boughtIsin: String?, closeReason: String)

    /** Töms tillsammans med fonder/transaktioner/förslag (SET-1) — bevakningar är användardata, inte cache. */
    @Query("DELETE FROM switch_watches")
    suspend fun deleteAll()
}
