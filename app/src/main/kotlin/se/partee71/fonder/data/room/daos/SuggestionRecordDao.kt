package se.partee71.fonder.data.room.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity

@Dao
interface SuggestionRecordDao {

    @Query("SELECT * FROM suggestion_records ORDER BY suggestedAtEpochDay DESC, planIndex ASC, id DESC")
    fun observeAll(): Flow<List<SuggestionRecordEntity>>

    /** Sant om exakt det här bytet (samma sälj-/köp-ISIN) redan spelats in [epochDay] — dedupspärr mot upprepad inspelning samma dag. */
    @Query("SELECT EXISTS(SELECT 1 FROM suggestion_records WHERE sellIsin = :sellIsin AND buyIsin = :buyIsin AND suggestedAtEpochDay = :epochDay)")
    suspend fun existsForDay(sellIsin: String, buyIsin: String, epochDay: Long): Boolean

    @Insert
    suspend fun insert(record: SuggestionRecordEntity): Long

    @Query("SELECT * FROM suggestion_records")
    suspend fun getAll(): List<SuggestionRecordEntity>

    /** Töms tillsammans med fonder/transaktioner/kurser (SET-1) — inspelade förslag är användardata, inte cache. */
    @Query("DELETE FROM suggestion_records")
    suspend fun deleteAll()
}
