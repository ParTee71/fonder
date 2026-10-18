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
import se.partee71.fonder.domain.model.SwitchWatchCandidateSource
import se.partee71.fonder.domain.model.SwitchWatchCloseReason

/**
 * Migration 17→18 (ANA-13, issue #150): `switch_watches.indexOnly` — filtret "bara indexfonder" för
 * bevakningens automatiska förslag, `NOT NULL DEFAULT 0`.
 *
 * Bevisar att varje befintlig `switch_watches`-kolumn — med icke-defaultvärden, inklusive
 * transaktionslänkarna från 16→17 och en målnivå användaren nu kan byta — och kandidaterna med
 * källa och nollpunkt är orörda, att den nya kolumnen är **falsk** för gamla rader (de visade förslag
 * ur alla fonder) och att defaulten i tabellen är `0`, samma som entiteten deklarerar.
 *
 * Version 17:s schema läses ur den incheckade, frysta `schemas/…/17.json` ([exportedSchemaStatements]),
 * som i [Migration1617Test]; raderna sätts in för hand med andra värden än där.
 */
@RunWith(AndroidJUnit4::class)
class Migration1718Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration1718-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_17_18_lagger_till_indexOnly_som_falskt_utan_att_rora_bevakningarna() = runTest {
        context.deleteDatabase(dbName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { old ->
            exportedSchemaStatements(17).forEach(old::execSQL)
            old.execSQL(
                "INSERT INTO transactions (id, fundId, type, epochDay, shares, pricePerShare, fee) VALUES " +
                    "(21, 'SHB0000801', 'SALJ', 20010, 3.25, 412.0, 15.0), (22, 'SHB0000802', 'KOP', 20013, 40.0, 33.5, 0.0)"
            )
            // Öppen, kopplad till sälj 21, målnivå 6 — och stängd som köpt av köp 22 med nivå 2.
            old.execSQL(
                "INSERT INTO switch_watches (id, sellIsin, sellFundName, soldAtEpochDay, proceedsKr, targetLevel, sourceRecordId, " +
                    "closedAtEpochDay, boughtIsin, closeReason, sellTransactionId, buyTransactionId) VALUES " +
                    "(31, 'LU0099574567', 'Fond Sälj', 20010, 1324.0, 6, NULL, NULL, NULL, NULL, 21, NULL), " +
                    "(32, 'SE0000709123', 'Fond Gammal', 19980, 880.5, 2, 14, 20013, 'IE00B4L5Y983', 'KOPT', NULL, 22)"
            )
            old.execSQL(
                "INSERT INTO switch_watch_candidates (id, watchId, isin, name, navAtStart, navAtStartEpochDay, source, position) VALUES " +
                    "(41, 31, 'IE00B4L5Y983', 'Global index', 81.75, 20011, 'AUTO', 0), " +
                    "(42, 31, 'SE0015961909', 'Handplockad', NULL, NULL, 'MANUELL', 3)"
            )
            old.version = 17
        }

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        val dao = migrated.switchWatchDao()

        val (open, closed) = dao.getAll().map { it.toDomain() }.sortedBy { it.id }
        assertEquals("LU0099574567", open.sellIsin)
        assertEquals("Fond Sälj", open.sellFundName)
        assertEquals(20_010L, open.soldAtEpochDay)
        assertEquals(1_324.0, open.proceedsKr!!, 1e-9)
        assertEquals(6, open.targetLevel)
        assertNull(open.sourceRecordId)
        assertEquals(21L, open.sellTransactionId)
        assertTrue(open.isOpen)
        assertEquals(listOf("IE00B4L5Y983", "SE0015961909"), open.candidates.map { it.isin })
        assertEquals(listOf(SwitchWatchCandidateSource.AUTO, SwitchWatchCandidateSource.MANUELL), open.candidates.map { it.source })
        assertEquals(listOf(0, 3), open.candidates.map { it.position })
        assertEquals(81.75, open.candidates.first().navAtStart!!, 1e-9)
        assertEquals(20_011L, open.candidates.first().navAtStartEpochDay)

        assertEquals(2, closed.targetLevel)
        assertEquals(14L, closed.sourceRecordId)
        assertEquals(20_013L, closed.closedAtEpochDay)
        assertEquals("IE00B4L5Y983", closed.boughtIsin)
        assertEquals(SwitchWatchCloseReason.KOPT, closed.closeReason)
        assertEquals(22L, closed.buyTransactionId)
        assertEquals(listOf(21L, 22L), migrated.transactionDao().getAll().map { it.id }.sorted())

        // Den nya kolumnen: falskt för båda — gamla bevakningar föreslog ur alla fonder.
        assertFalse(open.indexOnly)
        assertFalse(closed.indexOnly)
        migrated.openHelper.readableDatabase.query("PRAGMA table_info('switch_watches')").use { cursor ->
            val columns = generateSequence { if (cursor.moveToNext()) cursor.getString(1) to cursor.getString(4) else null }.toMap()
            assertEquals("0", columns["indexOnly"])
        }

        // Kolumnen går att skriva efter migreringen, och valet rör inte målnivån.
        assertEquals(1, dao.setIndexOnly(31, true))
        assertTrue(dao.getById(31)!!.toDomain().indexOnly)
        assertEquals(6, dao.getById(31)!!.watch.targetLevel)

        migrated.close()
    }
}
