package se.partee71.fonder.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.entities.FundMetadataEntity

/**
 * Migration 8→9 (issue #61) lägger till tre kolumner på `fund_metadata` för den persisterade
 * billigare-alternativ-jämförelsen (ANA-9/HEM-6) — befintliga rader (inklusive redan uppslagen
 * köpbarhet från #57) ska överleva oförändrade. Samma mönster som [Migration78Test].
 */
@RunWith(AndroidJUnit4::class)
class Migration89Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration89-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration_8_9 lagger till jamforelsekolumner och rör inte befintlig data`() = runTest {
        context.createLegacyDatabase(dbName, version = 8) { db ->
            insertFundWithPriceAndFxRate(db)
            // Redan uppslagen köpbarhet (#57) — måste överleva migreringen oförändrad.
            db.execSQL(
                "INSERT INTO fund_metadata (isin, name, orderbookId, totalFee, managementFee, " +
                    "category, fundType, companyName, risk, indexFund, startDateEpochDay, minimumBuy, " +
                    "tagsJson, availableAtHandelsbanken, availabilityResolvedAtEpochDay, fetchedAtEpochDay) " +
                    "VALUES ('SE0000581434', 'Länsförsäkringar Sverige Index', '12345', 0.21, 0.2, " +
                    "'Sverige', 'EQUITY_FUND', 'Länsförsäkringar', 4, 1, NULL, 100.0, " +
                    "'[]', 1, 20600, 20658)"
            )
        }

        val db = context.openMigratedDatabase(dbName)

        // De tre nya kolumnerna är skrivbara direkt efter migreringen.
        val existing = db.fundMetadataDao().getByIsin("SE0000581434")!!
        db.fundMetadataDao().upsert(
            existing.copy(
                cheapestAlternativeIsin = "SE0001466368",
                cheapestAlternativeFee = 0.73,
                comparisonResolvedAtEpochDay = 20658
            )
        )
        val updated = db.fundMetadataDao().getByIsin("SE0000581434")
        assertEquals("SE0001466368", updated?.cheapestAlternativeIsin)
        assertEquals(0.73, updated?.cheapestAlternativeFee ?: -1.0, 1e-9)
        assertEquals(20658L, updated?.comparisonResolvedAtEpochDay)

        // Redan uppslagen köpbarhet (#57) rörs inte av migreringen.
        assertEquals(true, updated?.availableAtHandelsbanken)
        assertEquals(20600L, updated?.availabilityResolvedAtEpochDay)
        assertEquals("Länsförsäkringar Sverige Index", updated?.name)

        // Befintliga fonder, transaktioner, kurser och växelkurser rörs inte.
        db.assertFundWithPriceAndFxRateUntouched()

        db.close()
    }
}
