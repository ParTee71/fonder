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
    fun `migration_9_10 lagger till developmentOneYear och suggestion_records, ror inte befintlig data`() = runTest {
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
                """
                CREATE TABLE `fund_metadata` (
                    `isin` TEXT NOT NULL, `name` TEXT NOT NULL, `orderbookId` TEXT NOT NULL,
                    `totalFee` REAL, `managementFee` REAL, `category` TEXT, `fundType` TEXT,
                    `companyName` TEXT, `risk` INTEGER, `indexFund` INTEGER NOT NULL,
                    `startDateEpochDay` INTEGER, `minimumBuy` REAL, `tagsJson` TEXT NOT NULL,
                    `availableAtHandelsbanken` INTEGER, `availabilityResolvedAtEpochDay` INTEGER,
                    `fetchedAtEpochDay` INTEGER NOT NULL,
                    `cheapestAlternativeIsin` TEXT, `cheapestAlternativeFee` REAL,
                    `comparisonResolvedAtEpochDay` INTEGER,
                    PRIMARY KEY(`isin`)
                )
                """.trimIndent(),
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
            // Redan uppslagen jämförelse (#61) — måste överleva migreringen oförändrad.
            db.execSQL(
                "INSERT INTO fund_metadata (isin, name, orderbookId, totalFee, managementFee, " +
                    "category, fundType, companyName, risk, indexFund, startDateEpochDay, minimumBuy, " +
                    "tagsJson, availableAtHandelsbanken, availabilityResolvedAtEpochDay, fetchedAtEpochDay, " +
                    "cheapestAlternativeIsin, cheapestAlternativeFee, comparisonResolvedAtEpochDay) " +
                    "VALUES ('SE0000581434', 'Länsförsäkringar Sverige Index', '12345', 0.21, 0.2, " +
                    "'Sverige', 'EQUITY_FUND', 'Länsförsäkringar', 4, 1, NULL, 100.0, " +
                    "'[]', 1, 20600, 20658, 'SE0001466368', 0.73, 20658)",
            )
            db.version = 9
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        db.openHelper.writableDatabase

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
                suggestedAtEpochDay = 20658, planIndex = 0,
                sellIsin = "SE0000581434", buyIsin = "SE0001466368",
                sellNavAtSuggestion = 100.0, buyNavAtSuggestion = 50.0, followed = null,
            ),
        )
        val records = db.suggestionRecordDao().observeAll().first()
        assertEquals(1, records.size)
        assertEquals("SE0000581434", records.single().sellIsin)

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
