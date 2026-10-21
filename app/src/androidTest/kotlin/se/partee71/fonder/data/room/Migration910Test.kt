package se.partee71.fonder.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity

/**
 * Migration 9→10 (issue #70), två delar: `developmentOneYear` på `fund_metadata` och den nya
 * tabellen `suggestion_records` — befintliga rader (inklusive #61:s jämförelsefält) ska
 * överleva oförändrade. Samma mönster som [Migration89Test].
 */
@RunWith(AndroidJUnit4::class)
class Migration910Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration910-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration_9_10 lagger till developmentOneYear och suggestion_records - ror inte befintlig data`() = runTest {
        context.createLegacyDatabase(dbName, version = 9) { db ->
            insertFundWithPriceAndFxRate(db)
            // Redan uppslagen jämförelse (#61) — måste överleva migreringen oförändrad.
            db.execSQL(
                "INSERT INTO fund_metadata (isin, name, orderbookId, totalFee, managementFee, " +
                    "category, fundType, companyName, risk, indexFund, startDateEpochDay, minimumBuy, " +
                    "tagsJson, availableAtHandelsbanken, availabilityResolvedAtEpochDay, fetchedAtEpochDay, " +
                    "cheapestAlternativeIsin, cheapestAlternativeFee, comparisonResolvedAtEpochDay) " +
                    "VALUES ('SE0000581434', 'Länsförsäkringar Sverige Index', '12345', 0.21, 0.2, " +
                    "'Sverige', 'EQUITY_FUND', 'Länsförsäkringar', 4, 1, NULL, 100.0, " +
                    "'[]', 1, 20600, 20658, 'SE0001466368', 0.73, 20658)"
            )
        }

        val db = context.openMigratedDatabase(dbName)

        // developmentOneYear är skrivbar direkt efter migreringen, resten av raden orörd.
        val existing = db.fundMetadataDao().getByIsin("SE0000581434")!!
        db.fundMetadataDao().upsert(existing.copy(developmentOneYear = 0.083))
        val updated = db.fundMetadataDao().getByIsin("SE0000581434")
        assertEquals(0.083, updated?.developmentOneYear ?: -1.0, 1e-9)
        assertEquals("SE0001466368", updated?.cheapestAlternativeIsin)
        assertEquals(0.73, updated?.cheapestAlternativeFee ?: -1.0, 1e-9)
        assertEquals(true, updated?.availableAtHandelsbanken)

        // suggestion_records finns och är skrivbar (HEM-8, genuin användardata — NFR-1).
        db.suggestionRecordDao().insert(
            SuggestionRecordEntity(
                suggestedAtEpochDay = 20658,
                planIndex = 0,
                sellIsin = "SE0000581434",
                buyIsin = "SE0001466368",
                sellNavAtSuggestion = 100.0,
                buyNavAtSuggestion = 50.0,
                switchValueKr = 1_000.0,
                followed = null
            )
        )
        val records = db.suggestionRecordDao().getAll()
        assertEquals(1, records.size)
        assertEquals("SE0000581434", records.single().sellIsin)

        // Befintliga fonder, transaktioner, kurser och växelkurser rörs inte.
        db.assertFundWithPriceAndFxRateUntouched()

        db.close()
    }
}
