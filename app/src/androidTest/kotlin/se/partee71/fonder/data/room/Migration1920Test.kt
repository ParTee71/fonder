package se.partee71.fonder.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity
import se.partee71.fonder.domain.model.SuggestionKind

/**
 * Migration 19→20 (SET-5, issue #197): `suggestion_records.navEpochDay` — dagen förslagets två
 * NAV-utgångslägen gäller, nullbar utan default.
 *
 * Bevisar att varje befintlig kolumn i de inspelade förslagen — med icke-defaultvärden: följt och
 * inte följt, belopp och inget belopp, körnings-id, plats och sort — är orörd, att den nya kolumnen
 * är **null** för gamla rader (de vet inte vilken dag kurserna var från, och facit mäter dem från
 * förslagsdagen), att den saknar default i tabellen, och att den går att skriva efteråt. Version 19:s
 * schema läses ur den incheckade, frysta `schemas/…/19.json` ([exportedSchemaStatements]).
 */
@RunWith(AndroidJUnit4::class)
class Migration1920Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration1920-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_19_20_lagger_till_navEpochDay_som_null_utan_att_rora_forslagen() = runTest {
        context.createLegacyDatabase(dbName, version = 19, schema = exportedSchemaStatements(19)) { db ->
            db.execSQL(
                "INSERT INTO transactions (id, fundId, type, epochDay, shares, pricePerShare, fee) VALUES " +
                    "(31, 'SHB0000911', 'KOP', 20120, 14.75, 233.4, 19.0)"
            )
            db.execSQL(
                "INSERT INTO suggestion_records (id, suggestedAtEpochDay, planIndex, sellIsin, buyIsin, sellNavAtSuggestion, " +
                    "buyNavAtSuggestion, switchValueKr, followed, batchEpochMillis, kind) VALUES " +
                    "(51, 20300, 2, 'SE0000709123', 'IE00B4L5Y983', 233.4, 81.75, 6250.5, 1, 1760000000000, 'RISK_PLAN'), " +
                    "(52, 20301, 0, 'SE0000709123', 'SE0015961909', 233.4, 12.5, NULL, 0, 1760090000000, 'FEE'), " +
                    "(53, 20150, 1, 'LU0099574567', 'SE0001466368', 41.0, 95.25, 900.0, NULL, 0, 'RISK_PLAN')"
            )
        }

        val db = context.openMigratedDatabase(dbName)
        val dao = db.suggestionRecordDao()

        val (planned, fee, old) = dao.getAll().sortedBy { it.id }
        assertEquals(20_300L, planned.suggestedAtEpochDay)
        assertEquals(2, planned.planIndex)
        assertEquals("SE0000709123", planned.sellIsin)
        assertEquals("IE00B4L5Y983", planned.buyIsin)
        assertEquals(233.4, planned.sellNavAtSuggestion, 1e-9)
        assertEquals(81.75, planned.buyNavAtSuggestion, 1e-9)
        assertEquals(6_250.5, planned.switchValueKr!!, 1e-9)
        assertEquals(true, planned.followed)
        assertEquals(1_760_000_000_000L, planned.batchEpochMillis)
        assertEquals(SuggestionKind.RISK_PLAN, planned.toDomain().kind)

        assertEquals(SuggestionKind.FEE, fee.toDomain().kind)
        assertNull(fee.switchValueKr)
        assertEquals(false, fee.followed)
        assertEquals(12.5, fee.buyNavAtSuggestion, 1e-9)
        assertNull(old.followed)
        assertEquals(0L, old.batchEpochMillis)

        // Den nya kolumnen: null för alla gamla rader — mäts från förslagsdagen, som förut.
        assertEquals(listOf(null, null, null), listOf(planned, fee, old).map { it.navEpochDay })
        assertEquals(listOf(20_300L, 20_301L, 20_150L), listOf(planned, fee, old).map { it.toDomain().navBaseEpochDay })
        db.openHelper.readableDatabase.query("PRAGMA table_info('suggestion_records')").use { cursor ->
            val columns = generateSequence { if (cursor.moveToNext()) cursor.getString(1) to (cursor.getInt(3) to cursor.getString(4)) else null }.toMap()
            assertEquals(0 to null, columns["navEpochDay"])
        }
        assertEquals(1, db.transactionDao().getAll().size)

        // Kolumnen går att skriva efter migreringen.
        dao.insert(
            SuggestionRecordEntity(
                id = 54, suggestedAtEpochDay = 20_400, planIndex = 0, sellIsin = "SE0000709123", buyIsin = "IE00B4L5Y983",
                sellNavAtSuggestion = 240.0, buyNavAtSuggestion = 83.0, switchValueKr = 1_000.0, followed = null, navEpochDay = 20_398
            )
        )
        assertEquals(20_398L, dao.getAll().single { it.id == 54L }.navEpochDay)

        db.close()
    }
}
