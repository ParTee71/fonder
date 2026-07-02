package se.partee71.fonder.data.room.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import se.partee71.fonder.data.room.entities.FundEntity

@Dao
interface FundDao {

    @Query("SELECT * FROM funds ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<FundEntity>>

    @Query("SELECT * FROM funds WHERE isin = :isin")
    suspend fun getByIsin(isin: String): FundEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fund: FundEntity)

    @Query("DELETE FROM funds WHERE isin = :isin")
    suspend fun deleteByIsin(isin: String)

    @Query("SELECT * FROM funds")
    suspend fun getAll(): List<FundEntity>
}
