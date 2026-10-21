package se.partee71.fonder.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity

/**
 * Migration 11→12 (issue #75, fynd B): `batchEpochMillis` på `suggestion_records`. Rader
 * inspelade före migreringen saknar körnings-id och ska överleva med 0 — den enda tolkning som
 * finns för dem är dygnet, precis som förut. Samma mönster som [Migration1011Test].
 */
@RunWith(AndroidJUnit4::class)
class Migration1112Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration1112-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration_11_12 lagger till batchEpochMillis utan att rora befintliga forslag`() = runTest {
        context.createLegacyDatabase(dbName, version = 11) { db ->
            db.execSQL(
                "INSERT INTO funds (fundId, name, currency, isin, fondlistaFundId) " +
                    "VALUES ('SHB0000442', 'Handelsbanken Amerika Småbolag Tema', 'SEK', 'SE0000582033', NULL)"
            )
            db.execSQL(
                "INSERT INTO transactions (fundId, type, epochDay, shares, pricePerShare, fee) " +
                    "VALUES ('SHB0000442', 'KOP', 20000, 2.0, 150.0, 0.0)"
            )
            db.execSQL(
                "INSERT INTO suggestion_records (suggestedAtEpochDay, planIndex, sellIsin, buyIsin, " +
                    "sellNavAtSuggestion, buyNavAtSuggestion, followed, switchValueKr) " +
                    "VALUES (20658, 0, 'SE0000581434', 'SE0001466368', 100.0, 50.0, NULL, 42000.0)"
            )
        }

        val db = context.openMigratedDatabase(dbName)

        // Den gamla raden överlever oförändrad, utan körnings-id.
        val existing = db.suggestionRecordDao().getAll().single()
        assertEquals("SE0000581434", existing.sellIsin)
        assertEquals(42_000.0, existing.switchValueKr ?: -1.0, 1e-9)
        assertEquals(0L, existing.batchEpochMillis)

        // Nya förslag bär körnings-id, så två körningar samma dygn går att skilja åt.
        db.suggestionRecordDao().insert(
            SuggestionRecordEntity(
                suggestedAtEpochDay = 20658, planIndex = 0,
                sellIsin = "SE0000581434", buyIsin = "SE0001466368",
                sellNavAtSuggestion = 101.0, buyNavAtSuggestion = 51.0,
                switchValueKr = 1_000.0, followed = null, batchEpochMillis = 1_785_000_000_000
            )
        )
        val nyast = db.suggestionRecordDao().getAll().maxBy { it.id }
        assertEquals(1_785_000_000_000L, nyast.batchEpochMillis)

        // Befintliga fonder och transaktioner rörs inte.
        assertEquals("Handelsbanken Amerika Småbolag Tema", db.fundDao().getByFundId("SHB0000442")?.name)
        assertEquals(1, db.transactionDao().observeForFund("SHB0000442").first().size)

        db.close()
    }
}
