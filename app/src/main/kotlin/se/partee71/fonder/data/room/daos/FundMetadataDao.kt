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

    /**
     * Namn + risknivå för de cachade fonder som har en känd risk (UI-10, issue #85) — underlaget
     * till risknivån i fondsök, där träffarna kommer från fondlista-katalogen och saknar ISIN
     * (`parseFundCatalog`), så uppslaget måste gå via namnet.
     *
     * Medvetet en **projektion**: raderna bär taggar och avgiftsfält som inget av det här
     * behöver, och tabellen växer med varje kandidatsökning bytesplanen gör (HEM-8) — att
     * materialisera hela [getAll] för två kolumner vore att betala för alla de andra.
     */
    @Query("SELECT name, risk FROM fund_metadata WHERE risk IS NOT NULL")
    suspend fun getKnownRisks(): List<FundNameRisk>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FundMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<FundMetadataEntity>)

    @Query("DELETE FROM fund_metadata")
    suspend fun deleteAll()
}

/** Projektionsrad för [FundMetadataDao.getKnownRisks] — bara det risknivåuppslaget behöver. */
data class FundNameRisk(val name: String, val risk: Int)
