package se.partee71.fonder.data.room.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import se.partee71.fonder.data.room.entities.FxRateEntity

@Dao
interface FxRateDao {

    /** Kurser för [currency] inom intervallet (inklusive), i stigande datumordning. */
    @Query(
        "SELECT * FROM fx_rates WHERE currency = :currency " +
            "AND epochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY epochDay ASC",
    )
    suspend fun getRange(currency: String, fromEpochDay: Long, toEpochDay: Long): List<FxRateEntity>

    /** Äldsta cachade kursen för [currency] — hur långt bak cachen når. */
    @Query("SELECT * FROM fx_rates WHERE currency = :currency ORDER BY epochDay ASC LIMIT 1")
    suspend fun getOldest(currency: String): FxRateEntity?

    /** Senaste cachade kursen för [currency] — hur långt fram cachen når. */
    @Query("SELECT * FROM fx_rates WHERE currency = :currency ORDER BY epochDay DESC LIMIT 1")
    suspend fun getLatest(currency: String): FxRateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rates: List<FxRateEntity>)

    @Query("DELETE FROM fx_rates")
    suspend fun deleteAll()
}
