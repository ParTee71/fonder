package se.partee71.fonder.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.AppDatabase
import se.partee71.fonder.domain.model.SuggestionCandidate
import se.partee71.fonder.domain.model.SuggestionRecord

/**
 * [SuggestionRecordRepository.commitBatch] mot riktig Room (HEM-8, issue #143): flytt och
 * inspelning av en omräknad plan är **en** transaktion — antingen hela körningen eller ingenting.
 */
@RunWith(AndroidJUnit4::class)
class RoomSuggestionRecordRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SuggestionRecordRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repository = RoomSuggestionRecordRepository(db, db.suggestionRecordDao())
    }

    @After
    fun tearDown() = db.close()

    private fun record(id: Long = 0, planIndex: Int, buyIsin: String, batchEpochMillis: Long) = SuggestionRecord(
        id = id, suggestedAtEpochDay = 20_000, planIndex = planIndex, sellIsin = "SE_SALJ", buyIsin = buyIsin,
        sellNavAtSuggestion = 100.0, buyNavAtSuggestion = 50.0, switchValueKr = 1_000.0, batchEpochMillis = batchEpochMillis
    )

    @Test
    fun commitBatch_flyttar_och_spelar_in_som_en_korning() = runTest {
        repository.record(record(planIndex = 0, buyIsin = "SE_A", batchEpochMillis = 1_000))
        val existing = repository.observeHistory().first().single()

        repository.commitBatch(
            moves = listOf(SuggestionRecordRepository.BatchMove(existing.id, batchEpochMillis = 2_000, planIndex = 1, switchValueKr = 2_500.0)),
            records = listOf(record(planIndex = 0, buyIsin = "SE_B", batchEpochMillis = 2_000))
        )

        val latest = repository.observeLatestBatch().first()
        assertEquals(listOf("SE_B", "SE_A"), latest.map { it.buyIsin })
        assertEquals(listOf(0, 1), latest.map { it.planIndex })
        assertEquals(existing.id, latest.last().id)
        assertEquals(2_500.0, latest.last().switchValueKr!!, 1e-9)
        assertEquals(2, repository.observeHistory().first().size)
    }

    @Test
    fun commitBatch_rullar_tillbaka_flytten_nar_inspelningen_faller() = runTest {
        // En rad med ett id som redan finns får insättningen att falla — då ska inte heller
        // flytten ha skett: en halv körning utan sitt steg 0 hade visat ingen plan alls.
        repository.record(record(planIndex = 0, buyIsin = "SE_A", batchEpochMillis = 1_000))
        val existing = repository.observeHistory().first().single()

        try {
            repository.commitBatch(
                moves = listOf(SuggestionRecordRepository.BatchMove(existing.id, batchEpochMillis = 2_000, planIndex = 1, switchValueKr = 2_500.0)),
                records = listOf(record(id = existing.id, planIndex = 0, buyIsin = "SE_B", batchEpochMillis = 2_000))
            )
            fail("insättningen skulle ha fallit på id-krocken")
        } catch (expected: SQLiteConstraintException) {
            // väntat
        }

        val kept = repository.observeHistory().first().single()
        assertEquals(1_000L, kept.batchEpochMillis)
        assertEquals(0, kept.planIndex)
        assertEquals(1_000.0, kept.switchValueKr!!, 1e-9)
        assertTrue(repository.observeLatestBatch().first().single().id == existing.id)
    }

    private fun candidates(vararg isins: String) = isins.mapIndexed { position, isin -> SuggestionCandidate(isin = isin, name = "Fond $isin", position = position, totalFee = 0.2, risk = 4) }

    @Test
    fun commitBatch_skriver_nya_radens_kandidater_och_byter_den_flyttades() = runTest {
        // Issue #239: en flyttad rad får den nya körningens alternativ, inte de den spelades in med.
        repository.record(record(planIndex = 0, buyIsin = "SE_A", batchEpochMillis = 1_000).copy(candidates = candidates("SE_GAMMAL1", "SE_GAMMAL2")))
        val existing = repository.observeHistory().first().single()
        assertEquals(listOf("SE_GAMMAL1", "SE_GAMMAL2"), existing.candidates.map { it.isin })
        assertEquals(existing.id, existing.candidates.first().recordId)

        repository.commitBatch(
            moves = listOf(
                SuggestionRecordRepository.BatchMove(existing.id, batchEpochMillis = 2_000, planIndex = 1, switchValueKr = 2_500.0, candidates = candidates("SE_NY"))
            ),
            records = listOf(record(planIndex = 0, buyIsin = "SE_B", batchEpochMillis = 2_000).copy(candidates = candidates("SE_B1", "SE_B2", "SE_B3")))
        )

        val latest = repository.observeLatestBatch().first()
        assertEquals(listOf(listOf("SE_B1", "SE_B2", "SE_B3"), listOf("SE_NY")), latest.map { row -> row.candidates.map { it.isin } })
        assertEquals(listOf(0, 1, 2), latest.first().candidates.map { it.position })
        assertEquals(
            1,
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM suggestion_candidates WHERE recordId = ${existing.id}").use {
                it.moveToFirst()
                it.getInt(0)
            }
        )
    }

    @Test
    fun commitBatch_hoppar_over_en_flyttad_rad_som_forsvunnit_utan_att_falla() = runTest {
        // Raden lästes före transaktionen och kan ha gallrats eller ersatts av en återställning sedan.
        // Dess kandidater får då inte skrivas — de hade fällt främmande nyckeln och hela körningen.
        repository.commitBatch(
            moves = listOf(SuggestionRecordRepository.BatchMove(id = 999, batchEpochMillis = 2_000, planIndex = 1, switchValueKr = 2_500.0, candidates = candidates("SE_X"))),
            records = listOf(record(planIndex = 0, buyIsin = "SE_B", batchEpochMillis = 2_000).copy(candidates = candidates("SE_B1")))
        )

        val stored = repository.observeHistory().first().single()
        assertEquals("SE_B", stored.buyIsin)
        assertEquals(listOf("SE_B1"), stored.candidates.map { it.isin })
    }
}
