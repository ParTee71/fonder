package se.partee71.fonder.data.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.entities.SwitchWatchCandidateEntity
import se.partee71.fonder.data.room.entities.SwitchWatchEntity

/**
 * Migration 14→15 (issue #114): `switch_watches` + `switch_watch_candidates` — det pågående
 * bytet mellan sälj och köp (ANA-12/ANA-13).
 *
 * Två helt nya tabeller: testet bevisar dels att befintlig användardata (fonder, transaktioner,
 * inspelade förslag) är orörd efteråt, dels att relationen faktiskt kaskadraderas. Utan det
 * sista hade en borttagen bevakning lämnat kandidater ingen läsare kan tolka — rader som
 * `getAll()` för backupen aldrig ser men som ändå ligger kvar i filen som skräp.
 *
 * Samma mönster som [Migration1314Test]: version 14:s schema byggs för hand, inte via
 * `MigrationTestHelper`.
 */
@RunWith(AndroidJUnit4::class)
class Migration1415Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration1415-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration_14_15 lagger till bevakningarna utan att rora befintlig data`() = runTest {
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
            // Version 14:s fund_metadata — med listan över visade alternativ (13→14).
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
                    `shownAlternativeIsinsJson` TEXT NOT NULL DEFAULT '[]',
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
                "INSERT INTO funds (fundId, name, currency, isin, fondlistaFundId) " +
                    "VALUES ('SHB0000442', 'Fond A', 'SEK', 'SE0000582033', 'SHB0000442')",
            )
            db.execSQL(
                "INSERT INTO transactions (id, fundId, type, epochDay, shares, pricePerShare, fee) " +
                    "VALUES (11, 'SHB0000442', 'KOP', 19000, 12.5, 178.25, 25.0)",
            )
            db.execSQL(
                "INSERT INTO suggestion_records (id, suggestedAtEpochDay, planIndex, sellIsin, buyIsin, " +
                    "sellNavAtSuggestion, buyNavAtSuggestion, followed, switchValueKr, batchEpochMillis, kind) " +
                    "VALUES (3, 19800, 0, 'SE0000582033', 'SE0001466368', 201.5, 95.25, 1, 4200.0, 1754200000000, 'RISK_PLAN')",
            )
            db.version = 14
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        db.openHelper.writableDatabase

        // Befintlig användardata är orörd — migreringen lägger bara till.
        assertEquals(1, db.fundDao().getAll().size)
        assertEquals(1, db.transactionDao().getAll().size)
        val record = db.suggestionRecordDao().getAll().single()
        assertEquals("SE0001466368", record.buyIsin)
        assertEquals(true, record.followed)

        // De nya tabellerna är läs- och skrivbara.
        val dao = db.switchWatchDao()
        assertTrue(dao.getAll().isEmpty())
        val watchId = dao.insertWatch(
            SwitchWatchEntity(
                sellIsin = "SE0000582033",
                sellFundName = "Fond A",
                soldAtEpochDay = 19_900,
                proceedsKr = 12_500.0,
                targetLevel = 4,
                sourceRecordId = 3,
                closedAtEpochDay = null,
                boughtIsin = null,
                closeReason = null,
            ),
        )
        dao.insertCandidates(
            listOf(
                SwitchWatchCandidateEntity(
                    watchId = watchId, isin = "SE0001466368", name = "Kandidat A",
                    navAtStart = 95.25, navAtStartEpochDay = 19_900, position = 0,
                ),
            ),
        )

        val stored = dao.getAll().single()
        assertEquals("Fond A", stored.watch.sellFundName)
        assertEquals(listOf("SE0001466368"), stored.candidates.map { it.isin })

        // Kaskadregeln gäller efter migreringen, inte bara i ett nybyggt schema: en kandidat
        // utan bevakning är en rad ingen läsare kan tolka.
        db.openHelper.writableDatabase.execSQL("DELETE FROM switch_watches WHERE id = $watchId")
        assertTrue(dao.getAll().isEmpty())
        assertEquals(
            0,
            db.openHelper.writableDatabase
                .query("SELECT COUNT(*) FROM switch_watch_candidates")
                .use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                },
        )

        db.close()
    }
}
