package se.partee71.fonder.data.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration 13→14 (issue #93): `fund_metadata.shownAlternativeIsinsJson` — samtliga alternativ
 * ANA-9 visade, inte bara det billigaste, så facit kan spela in varje *givet* råd.
 *
 * Befintliga rader får `'[]'` och inte en lista härledd ur `cheapestAlternativeIsin`: en gammal
 * rad vet bara vilket alternativ som var billigast, och de övriga två går inte att återskapa —
 * att fylla på med det enda kända hade sett ut som "jämförelsen visade ett alternativ" när den
 * i själva verket visade tre. Nästa jämförelse fyller listan. Samma mönster som
 * [Migration1213Test].
 */
@RunWith(AndroidJUnit4::class)
class Migration1314Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration1314-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration_13_14 lagger till listan utan att rora befintlig jamforelse`() = runTest {
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
            // Version 13:s fund_metadata — med jämförelsefälten, utan listan över visade alternativ.
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
                    `followed` INTEGER,
                    `switchValueKr` REAL,
                    `batchEpochMillis` INTEGER NOT NULL DEFAULT 0,
                    `kind` TEXT NOT NULL DEFAULT 'RISK_PLAN'
                )
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO fund_metadata (isin, name, orderbookId, totalFee, managementFee, category, " +
                    "fundType, companyName, risk, indexFund, startDateEpochDay, minimumBuy, tagsJson, " +
                    "availableAtHandelsbanken, availabilityResolvedAtEpochDay, fetchedAtEpochDay, " +
                    "cheapestAlternativeIsin, cheapestAlternativeFee, comparisonResolvedAtEpochDay, developmentOneYear) " +
                    "VALUES ('SE0000582033', 'Innehavet', 'ob-1', 1.4, 1.3, 'Sverige', 'EQUITY_FUND', " +
                    "'Bolaget', 6, 0, NULL, NULL, '[]', 1, 20650, 20658, 'SE0001466368', 0.21, 20658, 0.083)",
            )
            db.version = 13
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        db.openHelper.writableDatabase

        val existing = db.fundMetadataDao().getByIsin("SE0000582033")!!
        // Jämförelsen är orörd — HEM-6 räknar vidare på exakt samma underlag.
        assertEquals("SE0001466368", existing.cheapestAlternativeIsin)
        assertEquals(0.21, existing.cheapestAlternativeFee ?: -1.0, 1e-9)
        assertEquals(20658L, existing.comparisonResolvedAtEpochDay)
        // …men listan är tom, inte påhittad ur det billigaste.
        assertEquals(emptyList<String>(), existing.toDomain().shownAlternativeIsins)

        // Nya rader bär hela listan.
        db.fundMetadataDao().upsert(
            existing.copy(shownAlternativeIsinsJson = """["SE0001466368","SE0004617590"]"""),
        )
        assertEquals(
            listOf("SE0001466368", "SE0004617590"),
            db.fundMetadataDao().getByIsin("SE0000582033")!!.toDomain().shownAlternativeIsins,
        )

        db.close()
    }
}
