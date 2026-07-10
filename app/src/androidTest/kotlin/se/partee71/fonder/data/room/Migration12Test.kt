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
import se.partee71.fonder.data.room.entities.FundPriceEntity

/**
 * Migration 1→2 döper om isin/fundIsin → fundId och lägger till fund_prices (se
 * AppDatabase.MIGRATION_1_2, issue #3). Ingen schemas/-export finns i repot ännu (kräver
 * en lokal gradle-körning som denna sandboxade session inte har SDK för) — testet bygger
 * därför en v1-databas för hand som matchar entiteterna före namnbytet, kör migreringen,
 * och öppnar sedan via den riktiga Room-AppDatabase. Room validerar det migrerade schemat
 * mot de kompilerade entiteterna vid öppning (identity hash), så en felaktig migrering
 * upptäcks precis som med MigrationTestHelper.
 */
@RunWith(AndroidJUnit4::class)
class Migration12Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration12-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_1_2_byter_namn_pa_kolumner_och_lagger_till_fund_prices() = runTest {
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                "CREATE TABLE `funds` (`isin` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`currency` TEXT NOT NULL, PRIMARY KEY(`isin`))",
            )
            db.execSQL(
                "CREATE TABLE `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`fundIsin` TEXT NOT NULL, `type` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, " +
                    "`shares` REAL NOT NULL, `pricePerShare` REAL NOT NULL)",
            )
            db.execSQL("CREATE INDEX `index_transactions_fundIsin` ON `transactions` (`fundIsin`)")

            db.execSQL("INSERT INTO funds (isin, name, currency) VALUES ('SHB0000442', 'Fond A', 'SEK')")
            db.execSQL(
                "INSERT INTO transactions (fundIsin, type, epochDay, shares, pricePerShare) " +
                    "VALUES ('SHB0000442', 'KOP', 100, 2.0, 150.0)",
            )
            db.version = 1
        }

        // Appens kompilerade schema är nu v4 (se AppDatabase.MIGRATIONS) — hela vägen
        // 1→2→3→4 måste registreras, annars saknar Room en migreringsväg vid öppning.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()

        // Öppnar (och migrerar) — kastar om det resulterande schemat inte matchar entiteterna.
        db.openHelper.writableDatabase

        val fund = db.fundDao().getByFundId("SHB0000442")
        assertEquals("Fond A", fund?.name)

        val transactions = db.transactionDao().observeForFund("SHB0000442").first()
        assertEquals(1, transactions.size)
        assertEquals(2.0, transactions.first().shares, 1e-9)
        assertEquals("SHB0000442", transactions.first().fundId)

        // fund_prices är ny i v2 — ska gå att skriva och läsa direkt efter migreringen.
        db.fundPriceDao().upsertAll(
            listOf(FundPriceEntity(fundId = "SHB0000442", epochDay = 100, nav = 150.0, currency = "SEK")),
        )
        assertEquals(150.0, db.fundPriceDao().getLatest("SHB0000442")?.nav ?: -1.0, 1e-9)

        db.close()
    }
}
