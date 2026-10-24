package se.partee71.fonder.data.room.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import se.partee71.fonder.data.room.entities.TransactionEntity

@Dao
interface TransactionDao {

    @Query("SELECT * FROM transactions ORDER BY epochDay DESC, id DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE fundId = :fundId ORDER BY epochDay ASC, id ASC")
    fun observeForFund(fundId: String): Flow<List<TransactionEntity>>

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    /**
     * Rättar handelsdagen och rensar [TransactionEntity.dateEstimated] i samma uppdatering (IMP-2,
     * #222) — ett datum användaren själv satt är inte längre en uppskattning. Returnerar antalet
     * ändrade rader (0 om id:t inte finns).
     */
    @Query("UPDATE transactions SET epochDay = :epochDay, dateEstimated = 0 WHERE id = :id")
    suspend fun correctDate(id: Long, epochDay: Long): Int

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM transactions")
    suspend fun getAll(): List<TransactionEntity>

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}
