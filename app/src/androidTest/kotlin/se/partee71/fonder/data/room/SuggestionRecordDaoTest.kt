package se.partee71.fonder.data.room

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.daos.SuggestionRecordDao
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity

/**
 * DAO-rundtur för [SuggestionRecordDao] — facit-inspelningen (HEM-8) och dess redovisning
 * (SET-5, issue #80). Samma mönster som [FundMetadataDaoTest].
 */
@RunWith(AndroidJUnit4::class)
class SuggestionRecordDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SuggestionRecordDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.suggestionRecordDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(
        suggestedAtEpochDay: Long = 20_000,
        planIndex: Int = 0,
        sellIsin: String = "SE_SELL",
        buyIsin: String = "SE_BUY",
        followed: Boolean? = null,
        batchEpochMillis: Long = 0,
    ) = SuggestionRecordEntity(
        suggestedAtEpochDay = suggestedAtEpochDay,
        planIndex = planIndex,
        sellIsin = sellIsin,
        buyIsin = buyIsin,
        sellNavAtSuggestion = 100.0,
        buyNavAtSuggestion = 200.0,
        switchValueKr = 10_000.0,
        followed = followed,
        batchEpochMillis = batchEpochMillis,
    )

    @Test
    fun `setFollowed skriver och lases tillbaka`() = runTest {
        val id = dao.insert(entity())

        assertNull(dao.getAll().single().followed)

        dao.setFollowed(id, true)
        assertEquals(true, dao.getAll().single().followed)

        // Går också att ångra — markeringen är användarens, inte en engångsflagga.
        dao.setFollowed(id, false)
        assertEquals(false, dao.getAll().single().followed)
    }

    @Test
    fun `setFollowed ror bara den angivna raden`() = runTest {
        val forsta = dao.insert(entity(planIndex = 0))
        dao.insert(entity(planIndex = 1))

        dao.setFollowed(forsta, true)

        val rows = dao.getAll().sortedBy { it.planIndex }
        assertEquals(true, rows[0].followed)
        assertNull(rows[1].followed)
    }

    @Test
    // Inget kommatecken i namnet: ett instrumenterat testnamn blir ett dex-metodnamn, och D8
    // vägrar representera "," (mellanslag och bindestreck går bra, se övriga testnamn här).
    fun `observeHistory ger alla batchar - nyast forst`() = runTest {
        // observeLatestBatch ger med flit bara den senaste körningen — facit ska se hela
        // historiken, annars kan utfallet aldrig utvärderas över tid (SET-5).
        dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 1_000, planIndex = 0))
        dao.insert(entity(suggestedAtEpochDay = 20_001, batchEpochMillis = 2_000, planIndex = 0))
        dao.insert(entity(suggestedAtEpochDay = 20_001, batchEpochMillis = 2_000, planIndex = 1))

        val history = dao.observeHistory().first()

        assertEquals(3, history.size)
        assertEquals(listOf(20_001L, 20_001L, 20_000L), history.map { it.suggestedAtEpochDay })
        assertEquals(listOf(0, 1, 0), history.map { it.planIndex })
    }

    @Test
    fun `observeHistory haller ihop en batch aven nar tva korningar landat samma dygn`() = runTest {
        // Backstopen kör var 12:e timme, så två planer kan ha samma dygn men olika körning.
        dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 1_000, planIndex = 0))
        dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 2_000, planIndex = 0))
        dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 1_000, planIndex = 1))

        val history = dao.observeHistory().first()

        assertEquals(listOf(2_000L, 1_000L, 1_000L), history.map { it.batchEpochMillis })
        assertEquals(listOf(0, 0, 1), history.map { it.planIndex })
    }

    @Test
    fun `observeLatestBatch paverkas inte av att historiken kan lasas`() = runTest {
        dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 1_000))
        dao.insert(entity(suggestedAtEpochDay = 20_001, batchEpochMillis = 2_000))

        val senaste = dao.observeLatestBatch().first()

        assertEquals(1, senaste.size)
        assertEquals(20_001L, senaste.single().suggestedAtEpochDay)
        assertTrue(dao.observeHistory().first().size == 2)
    }
}
