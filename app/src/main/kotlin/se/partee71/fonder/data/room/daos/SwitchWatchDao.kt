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

    /**
     * Bevakningar stängda **på eller efter** [epochDay], senast stängda först — Besluts hopfällda
     * sektion "Avslutade och avstådda" (BYT-4). Ingen schemaändring: samma kolumner som [observeOpen].
     */
    @Transaction
    @Query("SELECT * FROM switch_watches WHERE closedAtEpochDay IS NOT NULL AND closedAtEpochDay >= :epochDay ORDER BY closedAtEpochDay DESC, id DESC")
    fun observeClosedSince(epochDay: Long): Flow<List<SwitchWatchWithCandidates>>

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

    /**
     * Öppna bevakningar av säljfonden utan känd transaktion (från före 0.67.0 eller startade ur ett
     * förslag, "Bevaka alternativ") med **samma** säljdag, äldst först. "Bevaka alternativ" på
     * Händelser **kopplar** den äldsta ([linkSellTransaction]) i stället för att starta en ny;
     * bevakningar med länk matchar bara på transaktionen ([hasOpenForTransaction]), så två sälj
     * kan få varsin. Ingen datumtolerans — en bevakning från en annan dag är en annan fråga som
     * användaren får ta ställning till (B, #144).
     */
    @Transaction
    @Query(
        """
        SELECT * FROM switch_watches
        WHERE sellIsin = :sellIsin AND soldAtEpochDay = :soldAtEpochDay
          AND closedAtEpochDay IS NULL AND sellTransactionId IS NULL
        ORDER BY id ASC
        """,
    )
    suspend fun getOpenWithoutTransactionFor(sellIsin: String, soldAtEpochDay: Long): List<SwitchWatchWithCandidates>

    /**
     * Kopplar en bevakning utan känd transaktion till säljtransaktionen och sätter likviden ur den
     * — transaktionen är sanningen om hur mycket. Säljdagen rörs inte (samma dag är villkoret för
     * kopplingen), så kandidaternas nollpunkt står kvar. Skriver aldrig över en befintlig länk och
     * kopplar aldrig en **stängd** bevakning — den har ett avslut, och ett sälj kan inte höra till
     * ett byte som redan är klart.
     *
     * @return antal träffade rader: 1 när länken skrevs, 0 när bevakningen redan var kopplad, stängd eller inte finns.
     */
    @Query(
        """
        UPDATE switch_watches SET sellTransactionId = :transactionId, proceedsKr = :proceedsKr
        WHERE id = :id AND sellTransactionId IS NULL AND closedAtEpochDay IS NULL
        """,
    )
    suspend fun linkSellTransaction(id: Long, transactionId: Long, proceedsKr: Double): Int

    /**
     * Som [linkSellTransaction] men **flyttar säljdagen** till transaktionens (issue #144) — vägen
     * för en bevakning från en annan dag som användaren efter fråga sagt är säljets. Anroparen
     * nollar kandidaternas nollpunkt i samma transaktion ([resetCandidateAnchors]): den är ankrad
     * på den gamla dagen och mäter annars en utveckling från fel dag.
     */
    @Query(
        """
        UPDATE switch_watches SET sellTransactionId = :transactionId, proceedsKr = :proceedsKr, soldAtEpochDay = :soldAtEpochDay
        WHERE id = :id AND sellTransactionId IS NULL AND closedAtEpochDay IS NULL
        """,
    )
    suspend fun linkSellTransactionOnDay(id: Long, transactionId: Long, proceedsKr: Double, soldAtEpochDay: Long): Int

    /**
     * Låter en bevakning **utan** förslag ta över planraden den kopplades för (BYT-1, issue #144):
     * källförslag och målnivå sätts bara när inget förslag finns — en bevakning ur ett annat
     * förslag skrivs aldrig om.
     */
    @Query("UPDATE switch_watches SET sourceRecordId = :sourceRecordId, targetLevel = :targetLevel WHERE id = :id AND sourceRecordId IS NULL")
    suspend fun adoptSuggestion(id: Long, sourceRecordId: Long, targetLevel: Int)

    /**
     * Fyller i målnivån i efterhand (ANA-13, issue #149) — **bara** där den saknas: en nivå som
     * redan finns (ur förslaget eller en tidigare ifyllnad) skrivs aldrig över.
     *
     * @return antal träffade rader: 1 när nivån skrevs nu, 0 när den redan fanns eller bevakningen inte finns.
     */
    @Query("UPDATE switch_watches SET targetLevel = :targetLevel WHERE id = :id AND targetLevel IS NULL")
    suspend fun fillMissingTargetLevel(id: Long, targetLevel: Int): Int

    /**
     * Användarens val av målnivå (ANA-13, issue #150) — skrivs över oavsett tidigare nivå, men bara
     * på en **öppen** bevakning och bara när nivån faktiskt ändras: en stängd bevakning bär vad som
     * hände, och ett val av samma nivå ska inte byta ut förslagen.
     *
     * @return 1 när nivån ändrades nu, 0 när den redan var den, bevakningen är stängd eller inte finns.
     */
    @Query(
        """
        UPDATE switch_watches SET targetLevel = :targetLevel
        WHERE id = :id AND closedAtEpochDay IS NULL AND (targetLevel IS NULL OR targetLevel <> :targetLevel)
        """,
    )
    suspend fun setTargetLevel(id: Long, targetLevel: Int): Int

    /** Filtret "bara indexfonder" (ANA-13, issue #150) — samma villkor som [setTargetLevel]. */
    @Query("UPDATE switch_watches SET indexOnly = :indexOnly WHERE id = :id AND closedAtEpochDay IS NULL AND indexOnly <> :indexOnly")
    suspend fun setIndexOnly(id: Long, indexOnly: Boolean): Int

    /**
     * Tar bort appens förslag men behåller de handplockade (ANA-13) — vid byte av nivå eller filter.
     * Villkoret är "inte [manualSource]" i stället för "AUTO", så en okänd källa (som läses som AUTO,
     * se [SwitchWatchCandidateEntity.toDomain]) också räknas som appens.
     */
    @Query("DELETE FROM switch_watch_candidates WHERE watchId = :watchId AND source <> :manualSource")
    suspend fun deleteAutoCandidates(watchId: Long, manualSource: String)

    /**
     * Nollar **bara** nollpunkten för bevakningens kandidater — raderna, inklusive de manuellt
     * tillagda, ligger kvar och ankras om nästa gång historiken hämtas (ANA-12).
     */
    @Query("UPDATE switch_watch_candidates SET navAtStart = NULL, navAtStartEpochDay = NULL WHERE watchId = :watchId")
    suspend fun resetCandidateAnchors(watchId: Long)

    /**
     * Sant om säljtransaktionen redan har en **öppen** bevakning (BYT-1) — dedupen per
     * transaktion, oavsett vilken väg bevakningen startades.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM switch_watches WHERE sellTransactionId = :sellTransactionId AND closedAtEpochDay IS NULL)")
    suspend fun hasOpenForTransaction(sellTransactionId: Long): Boolean

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
     *
     * @return antal träffade rader: 1 när den här uppdateringen stängde bevakningen, 0 när den
     *   redan var stängd eller inte finns — villkoret i `WHERE` avgör, inte en läsning före.
     */
    @Query(
        """
        UPDATE switch_watches
        SET closedAtEpochDay = :closedAtEpochDay, boughtIsin = :boughtIsin, closeReason = :closeReason,
            buyTransactionId = :buyTransactionId
        WHERE id = :id AND closedAtEpochDay IS NULL
        """,
    )
    suspend fun close(id: Long, closedAtEpochDay: Long, boughtIsin: String?, closeReason: String, buyTransactionId: Long? = null): Int

    /**
     * Nollar länkarna till en transaktion som tas bort (TRX-2) — bevakningen rörs inte i övrigt.
     * Användardata raderas aldrig som sidoeffekt; raden ligger kvar som en bevakning utan känd
     * transaktion, precis som en rad från före 0.67.0.
     */
    @Query(
        """
        UPDATE switch_watches
        SET sellTransactionId = CASE WHEN sellTransactionId = :transactionId THEN NULL ELSE sellTransactionId END,
            buyTransactionId = CASE WHEN buyTransactionId = :transactionId THEN NULL ELSE buyTransactionId END
        WHERE sellTransactionId = :transactionId OR buyTransactionId = :transactionId
        """,
    )
    suspend fun clearTransactionLinks(transactionId: Long)

    /** Töms tillsammans med fonder/transaktioner/förslag (SET-1) — bevakningar är användardata, inte cache. */
    @Query("DELETE FROM switch_watches")
    suspend fun deleteAll()
}
