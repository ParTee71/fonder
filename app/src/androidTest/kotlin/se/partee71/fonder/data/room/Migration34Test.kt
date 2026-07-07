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
 * Migration 3→4 lägger till den NOT NULL DEFAULT 0.0-kolumnen `fee` på `transactions` (se
 * AppDatabase.MIGRATION_3_4, issue #10 — realisationsberäkning för sälj-transaktioner).
 * Samma mönster som Migration23Test: bygger en v3-databas för hand, kör migreringen, öppnar
 * via den riktiga Room-AppDatabase (identity-hash-validering mot de kompilerade entiteterna
 * fångar en felaktig migrering).
 */
@RunWith(AndroidJUnit4::class)
class Migration34Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration34-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_3_4_lagger_till_fee_med_default_0_utan_dataforlust() = runTest {
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                "CREATE TABLE `funds` (`fundId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`currency` TEXT NOT NULL, `isin` TEXT, PRIMARY KEY(`fundId`))",
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
            db.execSQL(
                "INSERT INTO transactions (fundId, type, epochDay, shares, pricePerShare) " +
                    "VALUES ('SHB0000442', 'KOP', 100, 2.0, 150.0)",
            )
            db.version = 3
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_3_4)
            .build()

        // Öppnar (och migrerar) — kastar om det resulterande schemat inte matchar entiteterna.
        db.openHelper.writableDatabase

        val transactions = db.transactionDao().observeForFund("SHB0000442").first()
        assertEquals(1, transactions.size)
        assertEquals(0.0, transactions.first().fee, 1e-9)

        // Nya kolumnen ska gå att skriva och läsa direkt efter migreringen.
        db.transactionDao().insert(
            se.partee71.fonder.data.room.entities.TransactionEntity(
                fundId = "SHB0000442", type = "SALJ", epochDay = 200, shares = 1.0, pricePerShare = 160.0, fee = 25.0,
            ),
        )
        val updated = db.transactionDao().observeForFund("SHB0000442").first()
        assertEquals(25.0, updated.first { it.type == "SALJ" }.fee, 1e-9)

        db.close()
    }
}
