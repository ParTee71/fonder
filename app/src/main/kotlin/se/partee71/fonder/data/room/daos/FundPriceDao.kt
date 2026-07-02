package se.partee71.fonder.data.room.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import se.partee71.fonder.data.room.entities.FundPriceEntity

@Dao
interface FundPriceDao {

    @Query("SELECT * FROM fund_prices WHERE fundId = :fundId ORDER BY epochDay DESC LIMIT 1")
    suspend fun getLatest(fundId: String): FundPriceEntity?

    @Query(
        "SELECT * FROM fund_prices WHERE fundId = :fundId " +
            "AND epochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY epochDay ASC",
    )
    suspend fun getRange(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPriceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(prices: List<FundPriceEntity>)
}
