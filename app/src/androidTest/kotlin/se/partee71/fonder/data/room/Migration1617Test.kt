package se.partee71.fonder.data.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SwitchWatchCloseReason

/**
 * Migration 16→17 (BYT-1, issue #143): `switch_watches.sellTransactionId` och `buyTransactionId` —
 * kopplingen mellan ett pågående byte och transaktionerna som startade och stängde det — samt
 * indexet på `suggestion_records (kind, sellIsin, buyIsin)` för "senaste raden per par" (HEM-14).
 *
 * Två nullbara kolumner utan default: testet bevisar att befintlig användardata (fonder,
 * transaktioner, inspelade förslag, avståenden, bevakningar med kandidater) är orörd efteråt med
 * icke-defaultvärden i **varje** `switch_watches`-kolumn, och att de nya kolumnerna är **null**
 * — inte 0, som hade pekat på en transaktion som inte finns.
 *
 * Version 16:s schema läses ur den incheckade, frysta `schemas/…/16.json` (androidTest-asset,
 * [exportedSchemaStatements]) och körs med rå SQL, som [Migration1516Test] — aldrig via
 * `MigrationTestHelper`, aldrig via de nuvarande entiteterna. Raderna sätts in för hand, med andra
 * värden än i 15→16 så de två testerna inte är en kopia av varandra.
 */
@RunWith(AndroidJUnit4::class)
class Migration1617Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration1617-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_16_17_lagger_till_transaktionslankarna_som_null_utan_att_rora_befintlig_data() = runTest {
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            exportedSchemaStatements(16).forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO funds (fundId, name, currency, isin, fondlistaFundId) VALUES " +
                    "('SHB0000627', 'Fond B', 'USD', 'SE0000582033', NULL)"
            )
            db.execSQL(
                "INSERT INTO transactions (id, fundId, type, epochDay, shares, pricePerShare, fee) VALUES " +
                    "(11, 'SHB0000627', 'SALJ', 19900, 7.5, 180.0, 9.0)"
            )
            db.execSQL(
                "INSERT INTO suggestion_records (id, suggestedAtEpochDay, planIndex, sellIsin, buyIsin, sellNavAtSuggestion, " +
                    "buyNavAtSuggestion, switchValueKr, followed, batchEpochMillis, kind) VALUES " +
                    "(3, 19800, 1, 'SE0000582033', 'SE0001466368', 180.0, 95.25, 4200.0, 1, 1754200000000, 'FEE')"
            )
            db.execSQL(
                "INSERT INTO suggestion_dismissals (sellIsin, buyIsin, kind, dismissedAtEpochDay) " +
                    "VALUES ('SE0000582033', 'SE0004617590', 'RISK_PLAN', 19910)"
            )
            // En stängd bevakning med värde i varje kolumn, och en öppen — båda ska läsas som okopplade.
            db.execSQL(
                "INSERT INTO switch_watches (id, sellIsin, sellFundName, soldAtEpochDay, proceedsKr, targetLevel, " +
                    "sourceRecordId, closedAtEpochDay, boughtIsin, closeReason) " +
                    "VALUES (5, 'SE0000582033', 'Fond A', 19900, 12500.0, 4, 3, 19905, 'SE0001466368', 'KOPT')"
            )
            db.execSQL(
                "INSERT INTO switch_watches (id, sellIsin, sellFundName, soldAtEpochDay, proceedsKr, targetLevel, " +
                    "sourceRecordId, closedAtEpochDay, boughtIsin, closeReason) " +
                    "VALUES (6, 'SE0005991445', 'Fond C', 19910, NULL, NULL, NULL, NULL, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO switch_watch_candidates (id, watchId, isin, name, navAtStart, navAtStartEpochDay, source, position) " +
                    "VALUES (8, 5, 'SE0001466368', 'Kandidat A', 95.25, 19900, 'MANUELL', 2)"
            )
            db.version = 16
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        db.openHelper.writableDatabase

        // Befintlig användardata är orörd, med icke-defaultvärden i varje kolumn som kan ha ett.
        val fund = db.fundDao().getAll().single()
        assertEquals("SE0000582033", fund.isin)
        assertEquals("USD", fund.currency)
        assertNull(fund.fondlistaFundId)
        val tx = db.transactionDao().getAll().single()
        assertEquals(11L, tx.id)
        assertEquals("SALJ", tx.type)
        assertEquals(7.5, tx.shares, 1e-9)
        assertEquals(9.0, tx.fee, 1e-9)
        val record = db.suggestionRecordDao().getAll().single()
        assertEquals(true, record.followed)
        assertEquals(SuggestionKind.FEE, record.toDomain().kind)
        val dismissal = db.suggestionDismissalDao().getAll().single().toDomain()
        assertEquals("SE0004617590", dismissal.buyIsin)
        assertEquals(19_910L, dismissal.dismissedAtEpochDay)

        val watches = db.switchWatchDao().getAll().map { it.toDomain() }.sortedBy { it.id }
        val closed = watches.first()
        assertEquals(5L, closed.id)
        assertEquals("Fond A", closed.sellFundName)
        assertEquals(19_900L, closed.soldAtEpochDay)
        assertEquals(12_500.0, closed.proceedsKr!!, 1e-9)
        assertEquals(4, closed.targetLevel)
        assertEquals(3L, closed.sourceRecordId)
        assertEquals(19_905L, closed.closedAtEpochDay)
        assertEquals("SE0001466368", closed.boughtIsin)
        assertEquals(SwitchWatchCloseReason.KOPT, closed.closeReason)
        assertEquals(listOf("MANUELL"), closed.candidates.map { it.source.name })
        assertEquals(listOf(2), closed.candidates.map { it.position })
        assertEquals(95.25, closed.candidates.single().navAtStart!!, 1e-9)
        val open = watches.last()
        assertTrue(open.isOpen)
        assertNull(open.proceedsKr)
        assertNull(open.targetLevel)

        // De nya kolumnerna är null — en gammal bevakning vet inte vilken transaktion den hör till,
        // och 0 hade pekat på en transaktion som inte finns.
        assertTrue(watches.all { it.sellTransactionId == null && it.buyTransactionId == null })
        assertFalse(db.switchWatchDao().hasOpenForTransaction(11))

        // Kolumnerna är skriv- och läsbara efter migreringen: dedupen per sälj fungerar för nya rader.
        db.switchWatchDao().close(6, closedAtEpochDay = 19_912, boughtIsin = "SE0001466368", closeReason = "KOPT", buyTransactionId = 11)
        assertEquals(11L, db.switchWatchDao().getById(6)!!.toDomain().buyTransactionId)

        // Indexet finns med samma namn och kolumner som entiteten deklarerar — annars hade Rooms
        // schemavalidering redan fällt öppningen ovan, men påståendet ska stå här, inte implicit.
        db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'suggestion_records'"
        ).use { cursor ->
            val names = generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList()
            assertTrue(names.toString(), "index_suggestion_records_kind_sellIsin_buyIsin" in names)
        }
        db.openHelper.readableDatabase.query("PRAGMA index_info('index_suggestion_records_kind_sellIsin_buyIsin')").use { cursor ->
            val columns = generateSequence { if (cursor.moveToNext()) cursor.getString(2) else null }.toList()
            assertEquals(listOf("kind", "sellIsin", "buyIsin"), columns)
        }

        db.close()
    }
}
