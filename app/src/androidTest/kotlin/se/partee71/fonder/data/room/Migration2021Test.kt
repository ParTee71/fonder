package se.partee71.fonder.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.entities.TransactionEntity
import se.partee71.fonder.domain.model.TransactionType

/**
 * Migration 20→21 (IMP-2, issue #222): `transactions.dateEstimated` — köpdatumet uppskattades av
 * innehavsimporten, `NOT NULL DEFAULT 0`.
 *
 * Bevisar att varje befintlig kolumn i transaktionerna — med icke-defaultvärden: köp och sälj,
 * avgift och bråkdelsandelar — är orörd, att den nya kolumnen är **falsk** för gamla rader, att
 * defaulten står i tabellen, och att kolumnen går att skriva och rätta efteråt. Version 20:s schema
 * läses ur den incheckade, frysta `schemas/…/20.json` ([exportedSchemaStatements]).
 */
@RunWith(AndroidJUnit4::class)
class Migration2021Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration2021-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_20_21_lagger_till_dateEstimated_som_falskt_utan_att_rora_transaktionerna() = runTest {
        context.createLegacyDatabase(dbName, version = 20, schema = exportedSchemaStatements(20)) { db ->
            db.execSQL("INSERT INTO funds (fundId, name, currency, isin, fondlistaFundId) VALUES ('SHB0000911', 'Testfond Global', 'SEK', 'SE0000709123', 'F911')")
            db.execSQL(
                "INSERT INTO transactions (id, fundId, type, epochDay, shares, pricePerShare, fee) VALUES " +
                    "(31, 'SHB0000911', 'KOP', 20120, 14.75, 233.4, 19.0), " +
                    "(32, 'SHB0000911', 'SALJ', 20200, 3.125, 250.5, 0.0)"
            )
        }

        val db = context.openMigratedDatabase(dbName)
        val dao = db.transactionDao()

        val (buy, sale) = dao.getAll().sortedBy { it.id }.map { it.toDomain() }
        assertEquals("SHB0000911", buy.fundId)
        assertEquals(TransactionType.KOP, buy.type)
        assertEquals(20_120L, buy.epochDay)
        assertEquals(14.75, buy.shares, 1e-9)
        assertEquals(233.4, buy.pricePerShare, 1e-9)
        assertEquals(19.0, buy.fee, 1e-9)
        assertEquals(TransactionType.SALJ, sale.type)
        assertEquals(20_200L, sale.epochDay)
        assertEquals(3.125, sale.shares, 1e-9)
        assertEquals(250.5, sale.pricePerShare, 1e-9)

        // Den nya kolumnen: falsk för alla gamla rader, och defaulten står i tabellen.
        assertEquals(listOf(false, false), listOf(buy, sale).map { it.dateEstimated })
        db.openHelper.readableDatabase.query("PRAGMA table_info('transactions')").use { cursor ->
            val columns = generateSequence { if (cursor.moveToNext()) cursor.getString(1) to (cursor.getInt(3) to cursor.getString(4)) else null }.toMap()
            assertEquals(1 to "0", columns["dateEstimated"])
        }
        assertEquals("SE0000709123", db.fundDao().getByFundId("SHB0000911")!!.isin)

        // Kolumnen går att skriva och rätta efter migreringen.
        val id = dao.insert(
            TransactionEntity(fundId = "SHB0000911", type = "KOP", epochDay = 19_500, shares = 5.0, pricePerShare = 200.0, dateEstimated = true)
        )
        assertEquals(true, dao.getAll().single { it.id == id }.dateEstimated)
        dao.correctDate(id, epochDay = 19_480)
        val corrected = dao.getAll().single { it.id == id }
        assertEquals(19_480L, corrected.epochDay)
        assertEquals(false, corrected.dateEstimated)

        db.close()
    }
}
