package se.partee71.fonder.data.room

import android.content.Context
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

/**
 * Migration 5→6 (issue #41) rensar kurser som inte är i kronor ur `fund_prices`. Fondlista
 * noterar varje fond i fondens egen valuta, och när den blev källa även för ISIN-matchade
 * fonder (#39/#40) hamnade USD-noterade NAV i cachen — där hela värdekedjan räknar kronor
 * utan konvertering, så värdet blev fel med hela växelkursen.
 *
 * Samma mönster som de övriga migreringstesterna: bygger en v5-databas för hand, kör hela
 * kedjan, öppnar via den riktiga Room-AppDatabase.
 *
 * Sedan migrering 6→7 (issue #43, samma källbyte som gjorde valutakonvertering nödvändig)
 * töms `fund_prices` helt som en del av samma kedja — det här testet kan därför inte längre
 * visa att just SEK-raden överlevde valutafiltreringen (allt är borta oavsett), bara att
 * 5→6:s SQL körs utan att krascha och att fond-/transaktionsdata överlever hela kedjan.
 * Se `Migration67Test` för den nu gällande sanningen om `fund_prices`.
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
        context.createLegacyDatabase(dbName, version = 5) { db ->
            db.execSQL(
                "INSERT INTO funds (fundId, name, currency, isin, fondlistaFundId) " +
                    "VALUES ('LU0496367417', 'Franklin Gold', 'USD', 'LU0496367417', '0P0000O30D')"
            )
            db.execSQL(
                "INSERT INTO transactions (fundId, type, epochDay, shares, pricePerShare, fee) " +
                    "VALUES ('LU0496367417', 'KOP', 20000, 2.0, 150.0, 0.0)"
            )
            // Blandad cache, precis som på en enhet som hunnit köra 0.21.1: gamla rader i
            // kronor och nya i dollar för samma fond.
            db.execSQL("INSERT INTO fund_prices VALUES ('LU0496367417', 20655, 1878.75, 'SEK')")
            db.execSQL("INSERT INTO fund_prices VALUES ('LU0496367417', 20658, 193.48, 'USD')")
            db.execSQL("INSERT INTO fund_prices VALUES ('0P00000L4S', 20658, 323.07, 'SEK')")
        }

        val db = context.openMigratedDatabase(dbName)

        // Testet öppnar (som alla migreringstester i det här repot) via den riktiga,
        // kompilerade AppDatabase-klassen — Room kör då alltid HELA kedjan fram till den
        // aktuella versionen, inte bara 5→6. Migrering 6→7 tömmer `fund_prices` helt direkt
        // efteråt, så både dollar- och kronraden är borta oavsett vad 5→6 gjorde med dem.
        val franklin = db.fundPriceDao()
            .getRange("LU0496367417", fromEpochDay = Long.MIN_VALUE, toEpochDay = Long.MAX_VALUE)
        assertTrue("fund_prices ska vara tom efter hela kedjan — se migrering 6→7", franklin.isEmpty())

        assertTrue(db.fundPriceDao().getRange("0P00000L4S", Long.MIN_VALUE, Long.MAX_VALUE).isEmpty())

        // Fonderna och transaktionerna rörs inte — bara den härledda kurscachen (NFR-1).
        assertEquals("Franklin Gold", db.fundDao().getByFundId("LU0496367417")?.name)
        assertEquals("0P0000O30D", db.fundDao().getByFundId("LU0496367417")?.fondlistaFundId)
        assertEquals(1, db.transactionDao().observeForFund("LU0496367417").first().size)

        db.close()
    }
}
