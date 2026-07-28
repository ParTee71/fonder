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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration 5→6 (issue #41) rensar kurser som inte är i kronor ur `fund_prices`. Fondlista
 * noterar varje fond i fondens egen valuta, och när den blev källa även för ISIN-matchade
 * fonder (#39/#40) hamnade USD-noterade NAV i cachen — där hela värdekedjan räknar kronor
 * utan konvertering, så värdet blev fel med hela växelkursen.
 *
 * Samma mönster som de övriga migreringstesterna: bygger en v5-databas för hand, kör hela
 * kedjan, öppnar via den riktiga Room-AppDatabase.
 */
@RunWith(AndroidJUnit4::class)
class Migration56Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration56-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_5_6_rensar_kurser_i_annan_valuta_men_behaller_kronor() = runTest {
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
                "INSERT INTO funds (fundId, name, currency, isin, fondlistaFundId) " +
                    "VALUES ('LU0496367417', 'Franklin Gold', 'USD', 'LU0496367417', '0P0000O30D')",
            )
            db.execSQL(
                "INSERT INTO transactions (fundId, type, epochDay, shares, pricePerShare, fee) " +
                    "VALUES ('LU0496367417', 'KOP', 20000, 2.0, 150.0, 0.0)",
            )
            // Blandad cache, precis som på en enhet som hunnit köra 0.21.1: gamla rader i
            // kronor och nya i dollar för samma fond.
            db.execSQL("INSERT INTO fund_prices VALUES ('LU0496367417', 20655, 1878.75, 'SEK')")
            db.execSQL("INSERT INTO fund_prices VALUES ('LU0496367417', 20658, 193.48, 'USD')")
            db.execSQL("INSERT INTO fund_prices VALUES ('0P00000L4S', 20658, 323.07, 'SEK')")
            db.version = 5
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        db.openHelper.writableDatabase

        // Dollarraden är borta; kronraderna är kvar.
        val franklin = db.fundPriceDao()
            .getRange("LU0496367417", fromEpochDay = Long.MIN_VALUE, toEpochDay = Long.MAX_VALUE)
        assertEquals(listOf(20655L), franklin.map { it.epochDay })
        assertEquals(1878.75, franklin.single().nav, 1e-9)

        assertEquals(1, db.fundPriceDao().getRange("0P00000L4S", Long.MIN_VALUE, Long.MAX_VALUE).size)

        // Fonderna och transaktionerna rörs inte — bara den härledda kurscachen (NFR-1).
        assertEquals("Franklin Gold", db.fundDao().getByFundId("LU0496367417")?.name)
        assertEquals("0P0000O30D", db.fundDao().getByFundId("LU0496367417")?.fondlistaFundId)
        assertEquals(1, db.transactionDao().observeForFund("LU0496367417").first().size)

        db.close()
    }
}
