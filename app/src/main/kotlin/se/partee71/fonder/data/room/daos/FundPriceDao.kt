package se.partee71.fonder.data.room.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import se.partee71.fonder.data.room.entities.FundPriceEntity

@Dao
interface FundPriceDao {

    @Query("SELECT * FROM fund_prices WHERE fundId = :fundId ORDER BY epochDay DESC LIMIT 1")
    suspend fun getLatest(fundId: String): FundPriceEntity?

    /** Senaste kända kurs per fondId i [fundIds], reaktivt (uppdateras när WorkManager cachar nya kurser). */
    @Query(
        "SELECT fp.* FROM fund_prices fp " +
            "INNER JOIN (SELECT fundId, MAX(epochDay) AS maxEpochDay FROM fund_prices " +
            "WHERE fundId IN (:fundIds) GROUP BY fundId) latest " +
            "ON fp.fundId = latest.fundId AND fp.epochDay = latest.maxEpochDay",
    )
    fun observeLatest(fundIds: List<String>): Flow<List<FundPriceEntity>>

    @Query(
        "SELECT * FROM fund_prices WHERE fundId = :fundId " +
            "AND epochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY epochDay ASC",
    )
    suspend fun getRange(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPriceEntity>

    /** Som [getRange], men reaktivt — uppdateras när nya kurser cachas (issue #7). */
    @Query(
        "SELECT * FROM fund_prices WHERE fundId = :fundId " +
            "AND epochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY epochDay ASC",
    )
    fun observeRange(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPriceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(prices: List<FundPriceEntity>)
}
