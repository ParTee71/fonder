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
import se.partee71.fonder.domain.model.SuggestionKind

/**
 * Migration 12→13 (issue #91): `kind` på `suggestion_records`. Defaulten `RISK_PLAN` är inte
 * kosmetisk — varje rad inspelad före migreringen **är** ett bytesplansbyte, och skulle den
 * landa som något annat läste Hem plötsligt ett avgiftsbyte som "1. Sälj X → Köp Y" i en plan
 * det aldrig ingick i. Samma mönster som [Migration1112Test].
 */
@RunWith(AndroidJUnit4::class)
class Migration1213Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration1213-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration_12_13 lagger till kind och tolkar befintliga forslag som bytesplansbyten`() = runTest {
        context.createLegacyDatabase(dbName, version = 12) { db ->
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
                    "sellNavAtSuggestion, buyNavAtSuggestion, followed, switchValueKr, batchEpochMillis) " +
                    "VALUES (20658, 0, 'SE0000581434', 'SE0001466368', 100.0, 50.0, 1, 42000.0, 1785000000000)"
            )
        }

        val db = context.openMigratedDatabase(dbName)

        // Den gamla raden överlever oförändrad — och som ett bytesplansbyte, inte som något nytt.
        val existing = db.suggestionRecordDao().getAll().single()
        assertEquals("SE0000581434", existing.sellIsin)
        assertEquals(42_000.0, existing.switchValueKr ?: -1.0, 1e-9)
        assertEquals(1_785_000_000_000L, existing.batchEpochMillis)
        assertEquals(SuggestionKind.RISK_PLAN, existing.toDomain().kind)

        // …och den syns fortfarande på Hem, som bara läser bytesplanens rader.
        assertEquals(1, db.suggestionRecordDao().observeLatestBatch().first().size)

        // Nya avgiftsbyten kan spelas in bredvid dem.
        db.suggestionRecordDao().insert(
            SuggestionRecordEntity(
                suggestedAtEpochDay = 20658, planIndex = 0,
                sellIsin = "SE0000581434", buyIsin = "SE0009778954",
                sellNavAtSuggestion = 101.0, buyNavAtSuggestion = 51.0,
                switchValueKr = 1_000.0, followed = null, batchEpochMillis = 1_785_000_000_000,
                kind = SuggestionKind.FEE.name
            )
        )
        assertEquals(SuggestionKind.FEE, db.suggestionRecordDao().getAll().maxBy { it.id }.toDomain().kind)
        // Avgiftsraden hamnar aldrig i den batch Hem visar.
        assertEquals(1, db.suggestionRecordDao().observeLatestBatch().first().size)

        // Befintliga fonder och transaktioner rörs inte.
        assertEquals("Handelsbanken Amerika Småbolag Tema", db.fundDao().getByFundId("SHB0000442")?.name)
        assertEquals(1, db.transactionDao().observeForFund("SHB0000442").first().size)

        db.close()
    }
}
