package se.partee71.fonder.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.entities.SuggestionCandidateEntity
import se.partee71.fonder.domain.model.SuggestionKind

/**
 * Migration 21→22 (HEM-8, issue #239): `suggestion_candidates` — de sparade köpkandidaterna per
 * bytesplansförslag, med främmande nyckel och `ON DELETE CASCADE` till `suggestion_records`.
 *
 * Bevisar att befintliga förslag — med varje kolumn satt — är orörda och **utan** kandidater (vilka
 * alternativ en gammal körning valde bland går inte att återskapa), att den nya tabellen går att
 * skriva och läsa genom relationen, och att kaskaden gäller i det migrerade schemat. Version 21:s
 * schema läses ur den incheckade, frysta `schemas/…/21.json` ([exportedSchemaStatements]).
 */
@RunWith(AndroidJUnit4::class)
class Migration2122Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration2122-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_21_22_pa_en_tom_databas_ger_en_tom_kandidattabell() = runTest {
        context.createLegacyDatabase(dbName, version = 21, schema = exportedSchemaStatements(21)) { }

        val db = context.openMigratedDatabase(dbName)

        assertTrue(db.suggestionRecordDao().getAllWithCandidates().isEmpty())
        assertEquals(0, db.countRows("suggestion_candidates"))
        db.close()
    }

    @Test
    fun migration_21_22_lagger_till_kandidaterna_utan_att_rora_forslagen() = runTest {
        context.createLegacyDatabase(dbName, version = 21, schema = exportedSchemaStatements(21)) { db ->
            db.execSQL(
                "INSERT INTO suggestion_records (id, suggestedAtEpochDay, planIndex, sellIsin, buyIsin, sellNavAtSuggestion, " +
                    "buyNavAtSuggestion, switchValueKr, followed, batchEpochMillis, kind, navEpochDay) VALUES " +
                    "(5, 20300, 1, 'SE0000582033', 'SE0001466368', 201.5, 95.25, 4200.0, 1, 1754200000000, 'RISK_PLAN', 20298), " +
                    "(6, 20301, 0, 'SE0000582033', 'SE0005991445', 190.0, 70.0, NULL, NULL, 0, 'FEE', NULL)"
            )
        }

        val db = context.openMigratedDatabase(dbName)
        val dao = db.suggestionRecordDao()

        val (plan, fee) = dao.getAllWithCandidates().map { it.toDomain() }
        assertEquals(5L, plan.id)
        assertEquals(20_300L, plan.suggestedAtEpochDay)
        assertEquals(1, plan.planIndex)
        assertEquals("SE0000582033", plan.sellIsin)
        assertEquals("SE0001466368", plan.buyIsin)
        assertEquals(201.5, plan.sellNavAtSuggestion, 1e-9)
        assertEquals(95.25, plan.buyNavAtSuggestion, 1e-9)
        assertEquals(4_200.0, plan.switchValueKr!!, 1e-9)
        assertEquals(true, plan.followed)
        assertEquals(1_754_200_000_000L, plan.batchEpochMillis)
        assertEquals(SuggestionKind.RISK_PLAN, plan.kind)
        assertEquals(20_298L, plan.navEpochDay)
        assertEquals(SuggestionKind.FEE, fee.kind)
        assertEquals(null, fee.followed)
        // Gamla förslag får inga påhittade alternativ.
        assertTrue(plan.candidates.isEmpty() && fee.candidates.isEmpty())

        // Den nya tabellen går att skriva och läses genom relationen.
        dao.insertCandidates(
            listOf(
                SuggestionCandidateEntity(
                    recordId = 5, isin = "SE0000709123", name = "Alternativ Global", position = 0, totalFee = 0.25, risk = 4,
                    category = "Globalfond", fundType = "Aktiefond", companyName = "Fondbolaget AB", developmentOneYear = 0.083
                )
            )
        )
        val candidate = dao.getAllWithCandidates().single { it.record.id == 5L }.toDomain().candidates.single()
        assertEquals("SE0000709123", candidate.isin)
        assertEquals(0.083, candidate.developmentOneYear!!, 1e-9)

        // Kaskaden gäller i det migrerade schemat, inte bara i ett nybyggt.
        db.openHelper.writableDatabase.execSQL("DELETE FROM suggestion_records WHERE id = 5")
        assertEquals(0, db.countRows("suggestion_candidates"))
        assertEquals(1, dao.getAll().size)

        db.close()
    }

    private fun AppDatabase.countRows(table: String): Int = openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }
}
