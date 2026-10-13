package se.partee71.fonder.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.AppDatabase
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.usecase.SwitchWatchCalc
import java.time.LocalDate

/**
 * Kopplingen mellan en bevakning utan känd transaktion och säljtransaktionen mot riktig Room
 * (BYT-1/SLD-5, issue #143): bara **samma** säljdag hittas, och kopplingen rör varken säljdagen
 * eller kandidaternas ankrade nollpunkt — den kan inte återskapas (ANA-12). Utgången eller inte
 * avgör use caset ([se.partee71.fonder.domain.usecase.RecordTransaction.startWatchForSale]).
 */
@RunWith(AndroidJUnit4::class)
class RoomSwitchWatchRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SwitchWatchRepository

    private val today: LocalDate = LocalDate.of(2026, 10, 12)
    private val soldAt = today.minusDays(2).toEpochDay()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repository = RoomSwitchWatchRepository(db, db.switchWatchDao())
    }

    @After
    fun tearDown() = db.close()

    private fun watch(soldAtEpochDay: Long = soldAt, sellTransactionId: Long? = null) = SwitchWatch(
        sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = soldAtEpochDay, proceedsKr = 4_200.0, targetLevel = 3,
        sellTransactionId = sellTransactionId,
        candidates = listOf(
            SwitchWatchCandidate(isin = "SE_A", name = "Kandidat A", navAtStart = 101.5, navAtStartEpochDay = soldAtEpochDay),
            SwitchWatchCandidate(isin = "SE_B", name = "Kandidat B"),
        ),
    )

    @Test
    fun samma_dag_kopplas_med_likviden_och_kandidaternas_nollpunkt_och_dagen_ror_sig_inte() = runTest {
        val id = repository.start(watch())

        val found = repository.findOpenWithoutTransactionFor("SE_SALJ", soldAt)
        assertEquals(id, found?.id)
        repository.linkSellTransaction(id, transactionId = 42, proceedsKr = 1_475.0)

        val linked = repository.observe(id).first()!!
        assertEquals(42L, linked.sellTransactionId)
        assertEquals(1_475.0, linked.proceedsKr!!, 1e-9)
        assertEquals(soldAt, linked.soldAtEpochDay)
        assertEquals(listOf("SE_A", "SE_B"), linked.candidates.map { it.isin })
        assertEquals(101.5, linked.candidates.first().navAtStart!!, 1e-9)
        assertEquals(soldAt, linked.candidates.first().navAtStartEpochDay)
        assertNull(linked.candidates.last().navAtStart)
        // Kopplad: matchar bara på sin transaktion, inte längre som okopplad.
        assertNull(repository.findOpenWithoutTransactionFor("SE_SALJ", soldAt))
        assertEquals(true, repository.hasOpenForTransaction(42))
    }

    @Test
    fun en_annan_dag_en_kopplad_eller_en_stangd_bevakning_hittas_inte_men_en_utgangen_oppen_gor_det() = runTest {
        repository.start(watch(soldAtEpochDay = soldAt + 1))
        repository.start(watch(sellTransactionId = 7))
        val closed = repository.start(watch(soldAtEpochDay = soldAt - 1))
        repository.close(closed, SwitchWatchCloseReason.AVBRUTEN, today)
        val expiredDay = today.minusDays(SwitchWatchCalc.WATCH_TTL_DAYS + 3).toEpochDay()
        val expired = repository.start(watch(soldAtEpochDay = expiredDay))

        assertNull(repository.findOpenWithoutTransactionFor("SE_SALJ", soldAt))
        assertNull(repository.findOpenWithoutTransactionFor("SE_SALJ", soldAt - 1))
        // Utgången men inte stängd: fortfarande öppen i tabellen — use caset avgör att den blockerar.
        assertEquals(expired, repository.findOpenWithoutTransactionFor("SE_SALJ", expiredDay)?.id)
    }

    @Test
    fun close_stanger_bara_en_gang_och_sager_vem_som_gjorde_det() = runTest {
        val id = repository.start(watch())

        assertEquals(true, repository.close(id, SwitchWatchCloseReason.KOPT, today, boughtIsin = "SE_A"))
        assertEquals(false, repository.close(id, SwitchWatchCloseReason.AVBRUTEN, today))
        assertEquals(false, repository.close(404, SwitchWatchCloseReason.AVBRUTEN, today))

        val closed = repository.observe(id).first()!!
        assertEquals(SwitchWatchCloseReason.KOPT, closed.closeReason)
        assertEquals("SE_A", closed.boughtIsin)
    }
}
