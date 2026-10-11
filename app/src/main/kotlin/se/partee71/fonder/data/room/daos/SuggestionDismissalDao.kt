package se.partee71.fonder.data.room.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import se.partee71.fonder.data.room.entities.SuggestionDismissalEntity

/** Avstådda bytesförslag (HEM-14) — se [SuggestionDismissalEntity]. */
@Dao
interface SuggestionDismissalDao {

    /** Alla avståenden, även utgångna — giltigheten avgörs i domänlagret, inte i SQL. */
    @Query("SELECT * FROM suggestion_dismissals ORDER BY sellIsin, buyIsin, kind")
    fun observeAll(): Flow<List<SuggestionDismissalEntity>>

    /** Att avstå igen från samma par ersätter datumet — en rad per par och sort. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(dismissal: SuggestionDismissalEntity)

    @Query("DELETE FROM suggestion_dismissals WHERE sellIsin = :sellIsin AND buyIsin = :buyIsin AND kind = :kind")
    suspend fun delete(sellIsin: String, buyIsin: String, kind: String)

    /** Hela tabellen, i fast ordning — backup-kontraktets läsväg (NFR-1). */
    @Query("SELECT * FROM suggestion_dismissals ORDER BY sellIsin, buyIsin, kind")
    suspend fun getAll(): List<SuggestionDismissalEntity>

    /** Töms tillsammans med de inspelade förslagen (SET-1) och vid en återställning. */
    @Query("DELETE FROM suggestion_dismissals")
    suspend fun deleteAll()
}
