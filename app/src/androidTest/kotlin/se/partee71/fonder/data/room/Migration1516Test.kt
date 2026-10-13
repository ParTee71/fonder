package se.partee71.fonder.data.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.entities.SuggestionDismissalEntity
import se.partee71.fonder.domain.model.SuggestionKind

/**
 * Migration 15→16 (HEM-14): `suggestion_dismissals` — bytesförslag användaren avstått från.
 *
 * Helt ny tabell: testet bevisar att befintlig användardata (fonder, transaktioner, inspelade
 * förslag med kvittering, pågående byten med kandidater) är orörd efteråt och att den nya tabellen
 * är tom, läs- och skrivbar och håller en rad per fondpar och sort.
 *
 * Version 15:s schema byggs med rå SQL, inte via `MigrationTestHelper` eller de nuvarande
 * entiteterna — men satserna läses ur den incheckade, frysta `schemas/…/15.json` (monterad som
 * androidTest-asset, [exportedSchemaStatements]) i stället för att skrivas av en gång till: version
 * 15 har åtta tabeller som alla måste finnas för Rooms schemaverifiering, och en tredje kopia av dem
 * bredvid [Migration1415Test] vore just den dubbelkod `cpdCheck` fäller. Raderna sätts in för hand.
 */
@RunWith(AndroidJUnit4::class)
class Migration1516Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration1516-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_15_16_lagger_till_avstaendena_utan_att_rora_befintlig_data() = runTest {
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            exportedSchemaStatements(15).forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO funds (fundId, name, currency, isin, fondlistaFundId) " +
                    "VALUES ('SHB0000442', 'Fond A', 'SEK', 'SE0000582033', 'SHB0000442')",
            )
            db.execSQL(
                "INSERT INTO transactions (id, fundId, type, epochDay, shares, pricePerShare, fee) " +
                    "VALUES (11, 'SHB0000442', 'SALJ', 19900, 4.0, 201.5, 12.0)",
            )
            db.execSQL(
                "INSERT INTO suggestion_records (id, suggestedAtEpochDay, planIndex, sellIsin, buyIsin, " +
                    "sellNavAtSuggestion, buyNavAtSuggestion, switchValueKr, followed, batchEpochMillis, kind) " +
                    "VALUES (3, 19800, 1, 'SE0000582033', 'SE0001466368', 201.5, 95.25, 4200.0, 0, 1754200000000, 'FEE')",
            )
            db.execSQL(
                "INSERT INTO switch_watches (id, sellIsin, sellFundName, soldAtEpochDay, proceedsKr, targetLevel, " +
                    "sourceRecordId, closedAtEpochDay, boughtIsin, closeReason) " +
                    "VALUES (5, 'SE0000582033', 'Fond A', 19900, 12500.0, 4, 3, 19905, 'SE0001466368', 'KOPT')",
            )
            db.execSQL(
                "INSERT INTO switch_watch_candidates (id, watchId, isin, name, navAtStart, navAtStartEpochDay, source, position) " +
                    "VALUES (8, 5, 'SE0001466368', 'Kandidat A', 95.25, 19900, 'MANUELL', 2)",
            )
            db.version = 15
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

        db.openHelper.writableDatabase

        // Befintlig användardata är orörd, med icke-defaultvärden i varje kolumn som kan ha ett.
        assertEquals("SE0000582033", db.fundDao().getAll().single().isin)
        val tx = db.transactionDao().getAll().single()
        assertEquals("SALJ", tx.type)
        assertEquals(12.0, tx.fee, 1e-9)
        val record = db.suggestionRecordDao().getAll().single()
        assertEquals(false, record.followed)
        assertEquals(1, record.planIndex)
        assertEquals(4_200.0, record.switchValueKr!!, 1e-9)
        assertEquals(1_754_200_000_000L, record.batchEpochMillis)
        assertEquals(SuggestionKind.FEE, record.toDomain().kind)
        val watch = db.switchWatchDao().getAll().single()
        assertEquals("SE0001466368", watch.watch.boughtIsin)
        assertEquals("KOPT", watch.watch.closeReason)
        assertEquals(listOf("MANUELL"), watch.candidates.map { it.source })
        assertEquals(listOf(2), watch.candidates.map { it.position })

        // Den nya tabellen börjar tom — ingen gammal rad påstås vara avstådd.
        val dao = db.suggestionDismissalDao()
        assertTrue(dao.getAll().isEmpty())

        // En rad per fondpar och sort: att avstå igen skriver över datumet, en annan sort är en egen rad.
        dao.upsert(SuggestionDismissalEntity("SE0000582033", "SE0001466368", SuggestionKind.RISK_PLAN.name, 19_910))
        dao.upsert(SuggestionDismissalEntity("SE0000582033", "SE0001466368", SuggestionKind.RISK_PLAN.name, 19_920))
        dao.upsert(SuggestionDismissalEntity("SE0000582033", "SE0001466368", SuggestionKind.FEE.name, 19_915))
        val stored = dao.getAll().map { it.toDomain() }
        assertEquals(listOf(SuggestionKind.FEE, SuggestionKind.RISK_PLAN), stored.map { it.kind })
        assertEquals(listOf(19_915L, 19_920L), stored.map { it.dismissedAtEpochDay })

        db.close()
    }
}
