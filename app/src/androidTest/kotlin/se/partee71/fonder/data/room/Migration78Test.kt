package se.partee71.fonder.data.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                "CREATE TABLE `funds` (`fundId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`currency` TEXT NOT NULL, `isin` TEXT, `fondlistaFundId` TEXT, PRIMARY KEY(`fundId`))",
            )
            db.execSQL(
                "CREATE TABLE `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`fundId` TEXT NOT NULL, `type` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, " +
                    "`shares` REAL NOT NULL, `pricePerShare` REAL NOT NULL, `fee` REAL NOT NULL DEFAULT 0.0)",
            )
            db.execSQL("CREATE INDEX `index_transactions_fundId` ON `transactions` (`fundId`)")
            db.execSQL(
                "CREATE TABLE `fund_prices` (`fundId` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, " +
                    "`nav` REAL NOT NULL, `currency` TEXT NOT NULL, PRIMARY KEY(`fundId`, `epochDay`))",
            )
            db.execSQL(
                "CREATE TABLE `fx_rates` (`currency` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, " +
                    "`rate` REAL NOT NULL, PRIMARY KEY(`currency`, `epochDay`))",
            )
            db.execSQL(
                "INSERT INTO funds (fundId, name, currency, isin, fondlistaFundId) " +
                    "VALUES ('SHB0000442', 'Handelsbanken Amerika Småbolag Tema', 'SEK', 'SE0000582033', NULL)",
            )
            db.execSQL(
                "INSERT INTO transactions (fundId, type, epochDay, shares, pricePerShare, fee) " +
                    "VALUES ('SHB0000442', 'KOP', 20000, 2.0, 150.0, 0.0)",
            )
            db.execSQL("INSERT INTO fund_prices VALUES ('SHB0000442', 20658, 164.35, 'SEK')")
            db.execSQL("INSERT INTO fx_rates VALUES ('USD', 20658, 9.73973)")
            db.version = 7
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        db.openHelper.writableDatabase

        // fund_metadata är skrivbar direkt efter migreringen.
        db.fundMetadataDao().upsert(
            FundMetadataEntity(
                isin = "SE0000581434", name = "Länsförsäkringar Sverige Index", orderbookId = "12345",
                totalFee = 0.21, managementFee = 0.2, category = "Sverige", fundType = "EQUITY_FUND",
                companyName = "Länsförsäkringar", risk = 4, indexFund = true, startDateEpochDay = null,
                minimumBuy = 100.0, tagsJson = "[]", availableAtHandelsbanken = null,
                availabilityResolvedAtEpochDay = null, fetchedAtEpochDay = 20658,
            ),
        )
        assertEquals("Länsförsäkringar Sverige Index", db.fundMetadataDao().getByIsin("SE0000581434")?.name)

        // Befintliga fonder, transaktioner, kurser och växelkurser rörs inte.
        assertEquals("Handelsbanken Amerika Småbolag Tema", db.fundDao().getByFundId("SHB0000442")?.name)
        assertEquals(1, db.transactionDao().observeForFund("SHB0000442").first().size)
        assertTrue(
            db.fundPriceDao().getRange("SHB0000442", fromEpochDay = Long.MIN_VALUE, toEpochDay = Long.MAX_VALUE)
                .isNotEmpty(),
        )
        assertEquals(9.73973, db.fxRateDao().getLatest("USD")?.rate ?: -1.0, 1e-9)

        db.close()
    }
}
