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
 * Migration 7→8 (issue #57) lägger till `fund_metadata` för Avanzas sökbara fondmetadata
 * (KRAVLISTA TP-21) — helt ny tabell, ingen befintlig data rörs. Samma mönster som de övriga
 * migreringstesterna (t.ex. [Migration67Test]).
 */
@RunWith(AndroidJUnit4::class)
class Migration78Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration78-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration_7_8 lagger till fund_metadata och rör inte befintlig data`() = runTest {
        context.createLegacyDatabase(dbName, version = 7) { db ->
            insertFundWithPriceAndFxRate(db)
        }

        val db = context.openMigratedDatabase(dbName)

        // fund_metadata är skrivbar direkt efter migreringen.
        db.fundMetadataDao().upsert(
            FundMetadataEntity(
                isin = "SE0000581434", name = "Länsförsäkringar Sverige Index", orderbookId = "12345",
                totalFee = 0.21, managementFee = 0.2, category = "Sverige", fundType = "EQUITY_FUND",
                companyName = "Länsförsäkringar", risk = 4, indexFund = true, startDateEpochDay = null,
                minimumBuy = 100.0, tagsJson = "[]", availableAtHandelsbanken = null,
                availabilityResolvedAtEpochDay = null, fetchedAtEpochDay = 20658
            )
        )
        assertEquals("Länsförsäkringar Sverige Index", db.fundMetadataDao().getByIsin("SE0000581434")?.name)

        // Befintliga fonder, transaktioner, kurser och växelkurser rörs inte.
        db.assertFundWithPriceAndFxRateUntouched()

        db.close()
    }
}
