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
        sellTransactionId: Long? = null,
        targetLevel: Int? = 4,
    ): Long = dao.insertWatch(
        SwitchWatchEntity(
            sellIsin = sellIsin,
            sellFundName = "Såld fond",
            soldAtEpochDay = soldAtEpochDay,
            proceedsKr = 10_000.0,
            targetLevel = targetLevel,
            sourceRecordId = null,
            closedAtEpochDay = closedAtEpochDay,
            boughtIsin = null,
            closeReason = null,
            sellTransactionId = sellTransactionId,
        ),
    )

    @Test
    fun fillMissingTargetLevel_skriver_bara_nar_nivan_saknas() = runTest {
        // Issue #149: målnivån fylls i efterhand, men en befintlig nivå skrivs aldrig över.
        val withoutLevel = insertWatch(sellIsin = "SE_UTAN", targetLevel = null)
        val withLevel = insertWatch(sellIsin = "SE_MED", targetLevel = 4)

        assertEquals(1, dao.fillMissingTargetLevel(withoutLevel, 3))
        assertEquals(0, dao.fillMissingTargetLevel(withoutLevel, 6))
        assertEquals(0, dao.fillMissingTargetLevel(withLevel, 6))
        assertEquals(0, dao.fillMissingTargetLevel(9_999, 6))

        assertEquals(3, dao.getById(withoutLevel)!!.watch.targetLevel)
        assertEquals(4, dao.getById(withLevel)!!.watch.targetLevel)
    }

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
    fun observeClosedSince_ger_stangda_fran_och_med_dagen_senast_stangda_forst() = runTest {
        // BYT-4: Besluts hopfällda sektion visar stängda byten i 14 dagar efter stängningen.
        insertWatch(sellIsin = "SE_OPPEN")
        insertWatch(sellIsin = "SE_GAMMAL", closedAtEpochDay = 19_885)
        insertWatch(sellIsin = "SE_GRANS", closedAtEpochDay = 19_886)
        insertWatch(sellIsin = "SE_NY", closedAtEpochDay = 19_899)

        val closed = dao.observeClosedSince(19_886).first().map { it.toDomain() }

        assertEquals(listOf("SE_NY", "SE_GRANS"), closed.map { it.sellIsin })
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

    // --- Transaktionslänkarna (BYT-1, Room 16→17) ---

    @Test
    fun hasOpenForTransaction_nycklar_pa_saljtransaktionen_och_bara_oppna() = runTest {
        val watchId = insertWatch(sellTransactionId = 42)
        insertWatch(sellIsin = "SE_ANNAN", sellTransactionId = null)

        assertTrue(dao.hasOpenForTransaction(42))
        assertFalse(dao.hasOpenForTransaction(43))

        dao.close(watchId, closedAtEpochDay = 19_905, boughtIsin = null, closeReason = SwitchWatchCloseReason.AVBRUTEN.name)
        assertFalse(dao.hasOpenForTransaction(42))
    }

    @Test
    fun getOpenWithoutTransactionFor_ger_oppna_bevakningar_utan_lank_samma_dag_aldst_forst() = runTest {
        // En bevakning med länk räknas bara mot sin transaktion, så ett andra sälj kan få en egen
        // bevakning; en utan länk med samma säljdag är säljets och ska kopplas.
        insertWatch(sellIsin = "SE_SALJ", soldAtEpochDay = 19_900, sellTransactionId = 42)
        assertTrue(dao.getOpenWithoutTransactionFor("SE_SALJ", 19_900).isEmpty())
        assertTrue(dao.hasOpenFor("SE_SALJ", 19_900))

        val second = insertWatch(sellIsin = "SE_SALJ", soldAtEpochDay = 19_900, sellTransactionId = null)
        val third = insertWatch(sellIsin = "SE_SALJ", soldAtEpochDay = 19_900, sellTransactionId = null)
        insertWatch(sellIsin = "SE_SALJ", soldAtEpochDay = 19_901, sellTransactionId = null)
        insertWatch(sellIsin = "SE_ANNAN", soldAtEpochDay = 19_900, closedAtEpochDay = 19_905)

        val found = dao.getOpenWithoutTransactionFor("SE_SALJ", 19_900).map { it.watch.id }

        // En annan dag (19 901) och stängda räknas inte; ordningen är insättningsordning.
        assertEquals(listOf(second, third), found)
        assertTrue(dao.getOpenWithoutTransactionFor("SE_ANNAN", 19_900).isEmpty())
    }

    @Test
    fun linkSellTransaction_kopplar_satter_likvid_men_ror_inte_dagen_och_skriver_aldrig_over_en_lank() = runTest {
        val watchId = insertWatch(sellIsin = "SE_SALJ", soldAtEpochDay = 19_903, sellTransactionId = null)

        dao.linkSellTransaction(watchId, transactionId = 42, proceedsKr = 1_475.0)
        dao.linkSellTransaction(watchId, transactionId = 43, proceedsKr = 999.0)

        val watch = dao.getById(watchId)!!.toDomain()
        assertEquals(42L, watch.sellTransactionId)
        assertEquals(1_475.0, watch.proceedsKr!!, 1e-9)
        assertEquals(19_903L, watch.soldAtEpochDay)
        assertTrue(dao.hasOpenForTransaction(42))
        assertTrue(dao.getOpenWithoutTransactionFor("SE_SALJ", 19_903).isEmpty())
    }

    @Test
    fun linkSellTransactionOnDay_flyttar_dagen_och_resetCandidateAnchors_nollar_bara_nollpunkten() = runTest {
        val watchId = insertWatch(sellIsin = "SE_SALJ", soldAtEpochDay = 19_903, sellTransactionId = null)
        val other = insertWatch(sellIsin = "SE_ANNAN", soldAtEpochDay = 19_903, sellTransactionId = null)
        dao.insertCandidates(
            listOf(
                SwitchWatchCandidateEntity(watchId = watchId, isin = "SE_A", name = "A", navAtStart = 10.0, navAtStartEpochDay = 19_903, position = 0),
                SwitchWatchCandidateEntity(watchId = watchId, isin = "SE_B", name = "B", navAtStart = 20.0, navAtStartEpochDay = 19_903, source = "MANUELL", position = 1),
                SwitchWatchCandidateEntity(watchId = other, isin = "SE_C", name = "C", navAtStart = 30.0, navAtStartEpochDay = 19_903, position = 0),
            ),
        )

        assertEquals(1, dao.linkSellTransactionOnDay(watchId, transactionId = 42, proceedsKr = 1_475.0, soldAtEpochDay = 19_900))
        assertEquals(0, dao.linkSellTransactionOnDay(watchId, transactionId = 43, proceedsKr = 999.0, soldAtEpochDay = 19_890))
        dao.resetCandidateAnchors(watchId)

        val watch = dao.getById(watchId)!!.toDomain()
        assertEquals(42L, watch.sellTransactionId)
        assertEquals(19_900L, watch.soldAtEpochDay)
        assertEquals(1_475.0, watch.proceedsKr!!, 1e-9)
        assertEquals(listOf("SE_A", "SE_B"), watch.candidates.map { it.isin })
        assertTrue(watch.candidates.all { it.navAtStart == null && it.navAtStartEpochDay == null })
        assertEquals("MANUELL", dao.getById(watchId)!!.candidates.first { it.isin == "SE_B" }.source)
        // En annan bevaknings nollpunkt rörs inte.
        assertEquals(30.0, dao.getById(other)!!.toDomain().candidates.single().navAtStart!!, 1e-9)
    }

    @Test
    fun en_stangd_bevakning_lankas_aldrig_och_adoptSuggestion_ror_bara_en_bevakning_utan_forslag() = runTest {
        val closed = insertWatch(sellIsin = "SE_SALJ", soldAtEpochDay = 19_903, closedAtEpochDay = 19_905, sellTransactionId = null)
        val open = insertWatch(sellIsin = "SE_SALJ", soldAtEpochDay = 19_903, sellTransactionId = null)

        assertEquals(0, dao.linkSellTransaction(closed, transactionId = 42, proceedsKr = 1.0))
        assertEquals(0, dao.linkSellTransactionOnDay(closed, transactionId = 42, proceedsKr = 1.0, soldAtEpochDay = 19_900))
        assertNull(dao.getById(closed)!!.toDomain().sellTransactionId)

        dao.adoptSuggestion(open, sourceRecordId = 7, targetLevel = 3)
        dao.adoptSuggestion(open, sourceRecordId = 8, targetLevel = 5)

        val adopted = dao.getById(open)!!.toDomain()
        assertEquals(7L, adopted.sourceRecordId)
        assertEquals(3, adopted.targetLevel)
    }

    @Test
    fun close_som_kopt_skriver_koptransaktionen_och_raden_behaller_saljlanken() = runTest {
        val watchId = insertWatch(sellTransactionId = 42)

        dao.close(watchId, closedAtEpochDay = 19_905, boughtIsin = "SE_A", closeReason = SwitchWatchCloseReason.KOPT.name, buyTransactionId = 77)

        val watch = dao.getById(watchId)!!.toDomain()
        assertEquals(42L, watch.sellTransactionId)
        assertEquals(77L, watch.buyTransactionId)
        assertEquals("SE_A", watch.boughtIsin)
    }

    @Test
    fun clearTransactionLinks_nollar_bara_lanken_till_den_borttagna_transaktionen() = runTest {
        // TRX-2: bevakningen ligger kvar med allt annat orört — bara pekaren på raden som inte
        // finns längre försvinner, och en annan bevaknings länkar rörs inte.
        val sold = insertWatch(sellTransactionId = 42)
        dao.close(sold, closedAtEpochDay = 19_905, boughtIsin = "SE_A", closeReason = SwitchWatchCloseReason.KOPT.name, buyTransactionId = 77)
        val other = insertWatch(sellIsin = "SE_ANNAN", sellTransactionId = 43)

        dao.clearTransactionLinks(42)
        dao.clearTransactionLinks(999)

        val cleared = dao.getById(sold)!!.toDomain()
        assertNull(cleared.sellTransactionId)
        assertEquals(77L, cleared.buyTransactionId)
        assertEquals("SE_A", cleared.boughtIsin)
        assertEquals(SwitchWatchCloseReason.KOPT, cleared.closeReason)
        assertEquals(43L, dao.getById(other)!!.toDomain().sellTransactionId)
        assertEquals(2, dao.getAll().size)

        dao.clearTransactionLinks(77)
        assertNull(dao.getById(sold)!!.toDomain().buyTransactionId)
    }
}
