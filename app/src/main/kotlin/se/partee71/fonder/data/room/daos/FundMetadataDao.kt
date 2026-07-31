package se.partee71.fonder.data.room.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import se.partee71.fonder.data.room.entities.FundMetadataEntity

@Dao
interface FundMetadataDao {

    @Query("SELECT * FROM fund_metadata")
    suspend fun getAll(): List<FundMetadataEntity>

    @Query("SELECT * FROM fund_metadata WHERE isin = :isin")
    suspend fun getByIsin(isin: String): FundMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FundMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<FundMetadataEntity>)

    @Query("DELETE FROM fund_metadata")
    suspend fun deleteAll()
}
