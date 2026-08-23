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
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity

/**
 * Migration 10→11 (issue #75): `switchValueKr` på `suggestion_records`. En rad inspelad före
 * migreringen ska överleva med `switchValueKr = null` — beloppet fanns aldrig sparat och får
 * inte gissas till 0 kr, som inte gick att skilja från ett verkligt belopp. Samma mönster som
 * [Migration910Test].
 */
@RunWith(AndroidJUnit4::class)
class Migration1011Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration1011-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration_10_11 lagger till switchValueKr utan att rora befintliga forslag`() = runTest {
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
                    `comparisonResolvedAtEpochDay` INTEGER, `developmentOneYear` REAL,
                    PRIMARY KEY(`isin`)
                )
                """.trimIndent(),
            )
            // Version 10:s suggestion_records — utan switchValueKr.
            db.execSQL(
                """
                CREATE TABLE `suggestion_records` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `suggestedAtEpochDay` INTEGER NOT NULL,
                    `planIndex` INTEGER NOT NULL,
                    `sellIsin` TEXT NOT NULL,
                    `buyIsin` TEXT NOT NULL,
                    `sellNavAtSuggestion` REAL NOT NULL,
                    `buyNavAtSuggestion` REAL NOT NULL,
                    `followed` INTEGER
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
            // Ett förslag inspelat före migreringen — beloppet fanns aldrig sparat.
            db.execSQL(
                "INSERT INTO suggestion_records (suggestedAtEpochDay, planIndex, sellIsin, buyIsin, " +
                    "sellNavAtSuggestion, buyNavAtSuggestion, followed) " +
                    "VALUES (20658, 0, 'SE0000581434', 'SE0001466368', 100.0, 50.0, NULL)",
            )
            db.version = 10
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        db.openHelper.writableDatabase

        // Den gamla raden överlever oförändrad, med okänt belopp — inte 0 kr.
        val existing = db.suggestionRecordDao().getAll().single()
        assertEquals("SE0000581434", existing.sellIsin)
        assertEquals("SE0001466368", existing.buyIsin)
        assertEquals(100.0, existing.sellNavAtSuggestion, 1e-9)
        assertEquals(50.0, existing.buyNavAtSuggestion, 1e-9)
        assertNull("beloppet fanns inte före #75 och får inte gissas", existing.switchValueKr)

        // Nya förslag bär beloppet.
        db.suggestionRecordDao().insert(
            SuggestionRecordEntity(
                suggestedAtEpochDay = 20659, planIndex = 0,
                sellIsin = "SE0000581434", buyIsin = "SE0001466368",
                sellNavAtSuggestion = 101.0, buyNavAtSuggestion = 51.0,
                switchValueKr = 1_000.0, followed = null,
            ),
        )
        val nyast = db.suggestionRecordDao().getAll().maxBy { it.id }
        assertEquals(1_000.0, nyast.switchValueKr ?: -1.0, 1e-9)

        // Befintliga fonder och transaktioner rörs inte.
        assertEquals("Handelsbanken Amerika Småbolag Tema", db.fundDao().getByFundId("SHB0000442")?.name)
        assertEquals(1, db.transactionDao().observeForFund("SHB0000442").first().size)

        db.close()
    }
}
