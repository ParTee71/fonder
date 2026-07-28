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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Migration 4→5 (issue #39) gör två saker: lägger till den nullable kolumnen `fondlistaFundId`
 * på `funds`, och rensar **helgdaterade** rader ur `fund_prices`. Samma mönster som
 * Migration34Test: bygger en v4-databas för hand, kör migreringen, öppnar via den riktiga
 * Room-AppDatabase (identity-hash-validering mot de kompilerade entiteterna fångar en
 * felaktig migrering).
 *
 * Sedan migrering 6→7 (issue #43) töms `fund_prices` helt som en del av samma kedja — det
 * här testet kan därför inte längre visa att just helgraderna filtrerades bort (allt är
 * borta oavsett), bara att 4→5:s SQL körs utan att krascha och att fond-/transaktionsdata
 * överlever hela kedjan. Se `Migration67Test` för den nu gällande sanningen om `fund_prices`.
 */
@RunWith(AndroidJUnit4::class)
class Migration45Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration45-test.db"

    // 2026-07-24 fredag · 07-25 lördag · 07-26 söndag · 07-27 måndag.
    private val fredag = LocalDate.of(2026, 7, 24).toEpochDay()
    private val lordag = LocalDate.of(2026, 7, 25).toEpochDay()
    private val sondag = LocalDate.of(2026, 7, 26).toEpochDay()
    private val mandag = LocalDate.of(2026, 7, 27).toEpochDay()

    // Före epoken — SQLites modulo trunkerar mot noll, så negativa epoch-dagar måste hanteras
    // rätt av villkoret i migreringen. 1960-01-02 är en lördag.
    private val gammalLordag = LocalDate.of(1960, 1, 2).toEpochDay()
    private val gammalFredag = LocalDate.of(1960, 1, 1).toEpochDay()

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_4_5_lagger_till_fondlistaFundId_och_rensar_helgkurser() = runTest {
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
                    "`shares` REAL NOT NULL, `pricePerShare` REAL NOT NULL, `fee` REAL NOT NULL DEFAULT 0.0)",
            )
            db.execSQL("CREATE INDEX `index_transactions_fundId` ON `transactions` (`fundId`)")
            db.execSQL(
                "CREATE TABLE `fund_prices` (`fundId` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, " +
                    "`nav` REAL NOT NULL, `currency` TEXT NOT NULL, PRIMARY KEY(`fundId`, `epochDay`))",
            )
            // En importmatchad fond: identiteten ÄR ISIN:et (findFundByIsin, TP-13/TP-14).
            db.execSQL(
                "INSERT INTO funds (fundId, name, currency, isin) " +
                    "VALUES ('LU0496367417', 'Franklin Gold', 'USD', 'LU0496367417')",
            )
            db.execSQL(
                "INSERT INTO transactions (fundId, type, epochDay, shares, pricePerShare, fee) " +
                    "VALUES ('LU0496367417', 'KOP', 100, 2.0, 150.0, 0.0)",
            )
            // Kronor: det här testet gäller helgfiltret, och migrering 5→6 rensar allt som
            // inte är i FundPrice.VALUE_CURRENCY (issue #41) — annars vore listan tom oavsett
            // veckodag och testet skulle inte längre bevisa något om helgrensningen.
            listOf(
                gammalFredag to 90.0,
                gammalLordag to 91.0,
                fredag to 100.0,
                lordag to 101.0,
                sondag to 102.0,
                mandag to 103.0,
            ).forEach { (day, nav) ->
                db.execSQL(
                    "INSERT INTO fund_prices (fundId, epochDay, nav, currency) " +
                        "VALUES ('LU0496367417', $day, $nav, 'SEK')",
                )
            }
            db.version = 4
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            // Hela kedjan, av samma skäl som i de äldre migreringstesterna.
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        // Öppnar (och migrerar) — kastar om det resulterande schemat inte matchar entiteterna.
        db.openHelper.writableDatabase

        // Fonden och dess transaktion är orörda; nya kolumnen är null tills den slås upp.
        val fund = db.fundDao().getByFundId("LU0496367417")
        assertEquals("Franklin Gold", fund?.name)
        assertEquals("LU0496367417", fund?.isin)
        assertNull(fund?.fondlistaFundId)
        assertEquals(1, db.transactionDao().observeForFund("LU0496367417").first().size)

        // Testet öppnar (som alla migreringstester i det här repot) via den riktiga,
        // kompilerade AppDatabase-klassen — Room kör då alltid HELA kedjan fram till den
        // aktuella versionen, inte bara 4→5. Sedan migrering 6→7 (issue #43) tömmer
        // `fund_prices` helt (källbytet fondlista/Avanza kräver en ren cache) är alla
        // kurser borta oavsett vad 4→5:s helgfilter gjorde med dem — det går inte att
        // observera "läget precis efter 4→5" utan `MigrationTestHelper`, som repot
        // medvetet inte använder (se KDoc:en ovan). Det som återstår att verifiera här är
        // att 4→5:s SQL inte kraschar och att fond/transaktionsdata överlever hela kedjan.
        val prices = db.fundPriceDao()
            .getRange("LU0496367417", fromEpochDay = Long.MIN_VALUE, toEpochDay = Long.MAX_VALUE)
        assertTrue("fund_prices ska vara tom efter hela kedjan — se migrering 6→7", prices.isEmpty())

        // Nya kolumnen ska gå att skriva och läsa direkt efter migreringen.
        db.fundDao().upsert(requireNotNull(fund).copy(fondlistaFundId = "0P0000O30D"))
        assertEquals("0P0000O30D", db.fundDao().getByFundId("LU0496367417")?.fondlistaFundId)

        db.close()
    }
}
