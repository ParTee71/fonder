package se.partee71.fonder.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.daos.SwitchWatchDao
import se.partee71.fonder.data.room.entities.SwitchWatchCandidateEntity
import se.partee71.fonder.data.room.entities.SwitchWatchEntity
import se.partee71.fonder.domain.model.SwitchWatchCloseReason

/** Läs- och skrivvägarna för ett pågående byte (ANA-12, issue #114). */
@RunWith(AndroidJUnit4::class)
class SwitchWatchDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SwitchWatchDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).build()
        dao = db.switchWatchDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertWatch(
        sellIsin: String = "SE_SALJ",
        soldAtEpochDay: Long = 19_900,
        closedAtEpochDay: Long? = null,
    ): Long = dao.insertWatch(
        SwitchWatchEntity(
            sellIsin = sellIsin,
            sellFundName = "Såld fond",
            soldAtEpochDay = soldAtEpochDay,
            proceedsKr = 10_000.0,
            targetLevel = 4,
            sourceRecordId = null,
            closedAtEpochDay = closedAtEpochDay,
            boughtIsin = null,
            closeReason = null,
        ),
    )

    @Test
    fun kandidater_lases_i_positionsordning_oavsett_insattningsordning() = runTest {
        val watchId = insertWatch()
        dao.insertCandidates(
            listOf(
                SwitchWatchCandidateEntity(watchId = watchId, isin = "SE_C", name = "C", navAtStart = null, navAtStartEpochDay = null, position = 2),
                SwitchWatchCandidateEntity(watchId = watchId, isin = "SE_A", name = "A", navAtStart = null, navAtStartEpochDay = null, position = 0),
                SwitchWatchCandidateEntity(watchId = watchId, isin = "SE_B", name = "B", navAtStart = null, navAtStartEpochDay = null, position = 1),
            ),
        )

        val watch = dao.getById(watchId)!!.toDomain()

        assertEquals(listOf("SE_A", "SE_B", "SE_C"), watch.candidates.map { it.isin })
    }

    @Test
    fun observeOpen_ger_bara_oppna_bevakningar_senast_salda_forst() = runTest {
        insertWatch(sellIsin = "SE_GAMMAL", soldAtEpochDay = 19_800)
        insertWatch(sellIsin = "SE_NY", soldAtEpochDay = 19_900)
        insertWatch(sellIsin = "SE_STANGD", soldAtEpochDay = 19_950, closedAtEpochDay = 19_955)

        val open = dao.observeOpen().first().map { it.watch.sellIsin }

        assertEquals(listOf("SE_NY", "SE_GAMMAL"), open)
    }

    @Test
    fun close_stanger_bara_en_oppen_bevakning_och_behaller_raden() = runTest {
        val watchId = insertWatch()

        dao.close(watchId, closedAtEpochDay = 19_905, boughtIsin = "SE_A", closeReason = SwitchWatchCloseReason.KOPT.name)
        // Ett andra avslut ska inte kunna skriva om det första — kvitteringen är vad användaren gjorde.
        dao.close(watchId, closedAtEpochDay = 19_910, boughtIsin = "SE_B", closeReason = SwitchWatchCloseReason.AVBRUTEN.name)

        val watch = dao.getById(watchId)!!.toDomain()
        assertFalse(watch.isOpen)
        assertEquals(19_905L, watch.closedAtEpochDay)
        assertEquals("SE_A", watch.boughtIsin)
        assertEquals(SwitchWatchCloseReason.KOPT, watch.closeReason)
        // Raden finns kvar i historiken, den raderas aldrig.
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun setNavAtStart_ankrar_nollpunkten() = runTest {
        val watchId = insertWatch()
        dao.insertCandidates(
            listOf(SwitchWatchCandidateEntity(watchId = watchId, isin = "SE_A", name = "A", navAtStart = null, navAtStartEpochDay = null)),
        )
        val candidateId = dao.getById(watchId)!!.candidates.single().id

        dao.setNavAtStart(candidateId, nav = 101.5, epochDay = 19_901)

        val candidate = dao.getById(watchId)!!.candidates.single()
        assertEquals(101.5, candidate.navAtStart!!, 1e-9)
        assertEquals(19_901L, candidate.navAtStartEpochDay)
    }

    @Test
    fun kandidater_kaskadraderas_med_sin_bevakning() = runTest {
        val watchId = insertWatch()
        dao.insertCandidates(
            listOf(SwitchWatchCandidateEntity(watchId = watchId, isin = "SE_A", name = "A", navAtStart = null, navAtStartEpochDay = null)),
        )

        dao.deleteAll()

        assertTrue(dao.getAll().isEmpty())
        val kvar = db.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM switch_watch_candidates")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        assertEquals(0, kvar)
    }

    @Test
    fun hasOpenFor_nycklar_pa_fond_och_saljdag() = runTest {
        // En fond kan säljas i omgångar: bara det sälj som redan bevakas ska sluta erbjuda en
        // bevakning, inte varje framtida sälj i samma fond.
        insertWatch(sellIsin = "SE_SALJ", soldAtEpochDay = 19_900)

        assertTrue(dao.hasOpenFor("SE_SALJ", 19_900))
        assertFalse(dao.hasOpenFor("SE_SALJ", 19_950))
        assertFalse(dao.hasOpenFor("SE_ANNAN", 19_900))
    }

    @Test
    fun en_stangd_bevakning_blockerar_inte_en_ny_for_samma_salj() = runTest {
        val watchId = insertWatch(sellIsin = "SE_SALJ", soldAtEpochDay = 19_900)
        dao.close(watchId, closedAtEpochDay = 19_905, boughtIsin = null, closeReason = SwitchWatchCloseReason.AVBRUTEN.name)

        assertFalse(dao.hasOpenFor("SE_SALJ", 19_900))
    }

    @Test
    fun observeById_ger_null_for_en_bevakning_som_inte_finns() = runTest {
        assertNull(dao.observeById(404).first())
    }
}
