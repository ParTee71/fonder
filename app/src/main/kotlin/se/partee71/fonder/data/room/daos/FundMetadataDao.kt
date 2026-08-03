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

    /**
     * Flera rader i en fråga — för läsare som bara får använda cachen (SET-5/facit, issue #80).
     * Att i stället filtrera [getAll] i minnet materialiserar hela metadatatabellen, som växer
     * med varje kandidatsökning bytesplanen gör (HEM-8).
     */
    @Query("SELECT * FROM fund_metadata WHERE isin IN (:isins)")
    suspend fun getByIsins(isins: List<String>): List<FundMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FundMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<FundMetadataEntity>)

    @Query("DELETE FROM fund_metadata")
    suspend fun deleteAll()
}
