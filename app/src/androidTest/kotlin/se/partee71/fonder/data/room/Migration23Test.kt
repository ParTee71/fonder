package se.partee71.fonder.data.room

import android.content.Context
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
        context.createLegacyDatabase(dbName, version = 2) { db ->
            db.execSQL("INSERT INTO funds (fundId, name, currency) VALUES ('SHB0000442', 'Fond A', 'SEK')")
        }

        // Appens kompilerade schema är nu v4 (se AppDatabase.MIGRATIONS) — 2→3→4 måste
        // registreras, annars saknar Room en migreringsväg vid öppning.
        // Öppnar (och migrerar) — kastar om det resulterande schemat inte matchar entiteterna.
        val db = context.openMigratedDatabase(dbName)

        val fund = db.fundDao().getByFundId("SHB0000442")
        assertEquals("Fond A", fund?.name)
        assertNull(fund?.isin)

        // Nya kolumnen ska gå att skriva och läsa direkt efter migreringen.
        db.fundDao().upsert(FundEntity(fundId = "SHB0000442", name = "Fond A", currency = "SEK", isin = "SE0004297927"))
        assertEquals("SE0004297927", db.fundDao().getByFundId("SHB0000442")?.isin)

        db.close()
    }
}
