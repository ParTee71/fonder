package se.partee71.fonder.data.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration 18→19 (issue #153): varje cachat **nej** på köpbarhet hos Handelsbanken glöms, så det
 * slås upp igen — en brandväggskontroll hos källan hade lästs som "går inte att köpa" och cachats.
 *
 * Bevisar att nej-raderna får köpbarhet och uppslagsdag `NULL`, att ja-raderna och de aldrig prövade
 * är orörda, och att resten av raden (metadata, jämförelse, avkastning) står kvar. Version 18:s schema
 * läses ur den incheckade, frysta `schemas/…/18.json` ([exportedSchemaStatements]), som i
 * [Migration1718Test].
 */
@RunWith(AndroidJUnit4::class)
class Migration1819Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration1819-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_18_19_glommer_cachade_nej_pa_kopbarhet_och_ror_inget_annat() = runTest {
        context.deleteDatabase(dbName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { old ->
            exportedSchemaStatements(18).forEach(old::execSQL)
            old.execSQL(
                "INSERT INTO fund_metadata (isin, name, orderbookId, totalFee, managementFee, category, fundType, companyName, " +
                    "risk, indexFund, startDateEpochDay, minimumBuy, tagsJson, availableAtHandelsbanken, availabilityResolvedAtEpochDay, " +
                    "fetchedAtEpochDay, cheapestAlternativeIsin, cheapestAlternativeFee, shownAlternativeIsinsJson, " +
                    "comparisonResolvedAtEpochDay, developmentOneYear) VALUES " +
                    "('LU0122376428', 'BGF World Energy A2', '111', 2.55, 1.75, 'Energi', 'EQUITY_FUND', 'BlackRock', 5, 0, 11000, 100.0, '[]', 0, 20740, 20740, 'SE1', 1.1, '[\"SE1\"]', 20739, 61.2), " +
                    "('SE0005932993', 'Handelsbanken Asien Småbolag (A1 SEK)', '222', 2.07, 1.6, 'Asien', 'EQUITY_FUND', 'Handelsbanken', 5, 0, 16000, 100.0, '[]', 1, 20741, 20741, NULL, NULL, '[]', NULL, 55.0), " +
                    "('SE0000000001', 'Aldrig prövad', '333', 0.2, 0.2, NULL, 'EQUITY_FUND', NULL, 4, 1, NULL, NULL, '[]', NULL, NULL, 20742, NULL, NULL, '[]', NULL, NULL)"
            )
            old.version = 18
        }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        val dao = migrated.fundMetadataDao()

        val no = dao.getByIsin("LU0122376428")!!
        assertNull(no.availableAtHandelsbanken)
        assertNull(no.availabilityResolvedAtEpochDay)
        assertEquals("BGF World Energy A2", no.name)
        assertEquals(5, no.risk)
        assertEquals(2.55, no.totalFee!!, 1e-9)
        assertEquals(20_740L, no.fetchedAtEpochDay)
        assertEquals("SE1", no.cheapestAlternativeIsin)
        assertEquals(20_739L, no.comparisonResolvedAtEpochDay)
        assertEquals(61.2, no.developmentOneYear!!, 1e-9)

        val yes = dao.getByIsin("SE0005932993")!!
        assertEquals(true, yes.availableAtHandelsbanken)
        assertEquals(20_741L, yes.availabilityResolvedAtEpochDay)

        val untried = dao.getByIsin("SE0000000001")!!
        assertNull(untried.availableAtHandelsbanken)
        assertNull(untried.availabilityResolvedAtEpochDay)

        assertEquals(3, dao.getAll().size)
        migrated.close()
    }
}
