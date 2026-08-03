package se.partee71.fonder.data.room.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity

@Dao
interface SuggestionRecordDao {

    /**
     * Raderna från den **senast inspelade körningen** — den enda batch Hem visar (HEM-8).
     * Filtreringen ligger i SQL i stället för i minnet: tabellen växer med varje
     * backstop-körning, och att materialisera hela historiken vid varje emission på Hem för
     * att plocka ut tre rader är slöseri som bara blir värre med tiden (issue #75, punkt 6).
     *
     * Senaste dygnet räcker inte som nyckel — backstopen kör var 12:e timme, så två körningar
     * landar normalt samma dygn. Därav `batchEpochMillis` (issue #75, fynd B).
     */
    @Query(
        """
        SELECT * FROM suggestion_records
        WHERE suggestedAtEpochDay = (SELECT MAX(suggestedAtEpochDay) FROM suggestion_records)
          AND batchEpochMillis = (
              SELECT MAX(batchEpochMillis) FROM suggestion_records
              WHERE suggestedAtEpochDay = (SELECT MAX(suggestedAtEpochDay) FROM suggestion_records)
          )
        ORDER BY planIndex ASC, id ASC
        """,
    )
    fun observeLatestBatch(): Flow<List<SuggestionRecordEntity>>

    /** Sant om exakt det här bytet (samma sälj-/köp-ISIN) redan spelats in [epochDay] — dedupspärr mot upprepad inspelning samma dag. */
    @Query("SELECT EXISTS(SELECT 1 FROM suggestion_records WHERE sellIsin = :sellIsin AND buyIsin = :buyIsin AND suggestedAtEpochDay = :epochDay)")
    suspend fun existsForDay(sellIsin: String, buyIsin: String, epochDay: Long): Boolean

    @Insert
    suspend fun insert(record: SuggestionRecordEntity): Long

    /**
     * Hela tabellen. Ingen produktionsläsare — verifieringsfrågan för tester (SET-1:s
     * tömningsrundtur och migreringstesterna), där hela innehållet är själva påståendet.
     */
    @Query("SELECT * FROM suggestion_records")
    suspend fun getAll(): List<SuggestionRecordEntity>

    /** Tar bort förslag inspelade före [epochDay] — retention, se [se.partee71.fonder.data.repository.SuggestionRecordRepository.prune]. */
    @Query("DELETE FROM suggestion_records WHERE suggestedAtEpochDay < :epochDay")
    suspend fun deleteOlderThan(epochDay: Long)

    /** Töms tillsammans med fonder/transaktioner/kurser (SET-1) — inspelade förslag är användardata, inte cache. */
    @Query("DELETE FROM suggestion_records")
    suspend fun deleteAll()
}
