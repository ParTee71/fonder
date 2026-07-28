package se.partee71.fonder.data.room

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.daos.FxRateDao
import se.partee71.fonder.data.room.entities.FxRateEntity

/** DAO-rundtur för [FxRateDao] (KRAVLISTA TP-20, issue #43) — samma mönster som [FundPriceDaoTest]. */
@RunWith(AndroidJUnit4::class)
class FxRateDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FxRateDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.fxRateDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun upsertAll_round_trip_getOldest_och_getLatest() = runTest {
        dao.upsertAll(
            listOf(
                FxRateEntity(currency = "USD", epochDay = 100, rate = 9.5),
                FxRateEntity(currency = "USD", epochDay = 101, rate = 9.6),
                FxRateEntity(currency = "EUR", epochDay = 100, rate = 11.0),
            ),
        )

        assertEquals(9.5, dao.getOldest("USD")?.rate ?: -1.0, 1e-9)
        assertEquals(9.6, dao.getLatest("USD")?.rate ?: -1.0, 1e-9)
        assertEquals(11.0, dao.getOldest("EUR")?.rate ?: -1.0, 1e-9)
        assertNull(dao.getOldest("NOK"))

        val range = dao.getRange("USD", fromEpochDay = 100, toEpochDay = 100)
        assertEquals(1, range.size)
        assertEquals(9.5, range.first().rate, 1e-9)
    }

    @Test
    fun upsertAll_ersatter_befintlig_kurs_for_samma_dag() = runTest {
        dao.upsertAll(listOf(FxRateEntity(currency = "USD", epochDay = 100, rate = 9.5)))
        dao.upsertAll(listOf(FxRateEntity(currency = "USD", epochDay = 100, rate = 9.9)))

        val range = dao.getRange("USD", 100, 100)
        assertEquals(1, range.size)
        assertEquals(9.9, range.first().rate, 1e-9)
    }

    @Test
    fun deleteAll_tommer_tabellen() = runTest {
        dao.upsertAll(listOf(FxRateEntity(currency = "USD", epochDay = 100, rate = 9.5)))

        dao.deleteAll()

        assertNull(dao.getLatest("USD"))
    }
}
