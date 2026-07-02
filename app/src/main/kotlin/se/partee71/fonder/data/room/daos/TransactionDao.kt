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

    @Query("SELECT * FROM transactions WHERE fundIsin = :isin ORDER BY epochDay ASC, id ASC")
    fun observeForFund(isin: String): Flow<List<TransactionEntity>>

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM transactions")
    suspend fun getAll(): List<TransactionEntity>
}
