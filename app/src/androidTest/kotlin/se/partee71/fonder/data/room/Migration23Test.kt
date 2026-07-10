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
import se.partee71.fonder.data.room.entities.FundEntity

/**
 * Migration 2→3 lägger till den nullable `isin`-kolumnen på `funds` (se
 * AppDatabase.MIGRATION_2_3). Samma mönster som Migration12Test: bygger en v2-databas för
 * hand, kör migreringen, öppnar via den riktiga Room-AppDatabase (identity-hash-validering
 * mot de kompilerade entiteterna fångar en felaktig migrering).
 */
@RunWith(AndroidJUnit4::class)
class Migration23Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration23-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_2_3_lagger_till_nullable_isin_utan_dataforlust() = runTest {
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                "CREATE TABLE `funds` (`fundId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`currency` TEXT NOT NULL, PRIMARY KEY(`fundId`))",
            )
            db.execSQL(
                "CREATE TABLE `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`fundId` TEXT NOT NULL, `type` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, " +
                    "`shares` REAL NOT NULL, `pricePerShare` REAL NOT NULL)",
            )
            db.execSQL("CREATE INDEX `index_transactions_fundId` ON `transactions` (`fundId`)")
            db.execSQL(
                "CREATE TABLE `fund_prices` (`fundId` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, " +
                    "`nav` REAL NOT NULL, `currency` TEXT NOT NULL, PRIMARY KEY(`fundId`, `epochDay`))",
            )
            db.execSQL("INSERT INTO funds (fundId, name, currency) VALUES ('SHB0000442', 'Fond A', 'SEK')")
            db.version = 2
        }

        // Appens kompilerade schema är nu v4 (se AppDatabase.MIGRATIONS) — 2→3→4 måste
        // registreras, annars saknar Room en migreringsväg vid öppning.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()

        // Öppnar (och migrerar) — kastar om det resulterande schemat inte matchar entiteterna.
        db.openHelper.writableDatabase

        val fund = db.fundDao().getByFundId("SHB0000442")
        assertEquals("Fond A", fund?.name)
        assertNull(fund?.isin)

        // Nya kolumnen ska gå att skriva och läsa direkt efter migreringen.
        db.fundDao().upsert(FundEntity(fundId = "SHB0000442", name = "Fond A", currency = "SEK", isin = "SE0004297927"))
        assertEquals("SE0004297927", db.fundDao().getByFundId("SHB0000442")?.isin)

        db.close()
    }
}
