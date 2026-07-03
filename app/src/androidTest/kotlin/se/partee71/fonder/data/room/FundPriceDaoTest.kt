package se.partee71.fonder.data.room

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
import se.partee71.fonder.data.room.daos.FundPriceDao
import se.partee71.fonder.data.room.entities.FundPriceEntity

@RunWith(AndroidJUnit4::class)
class FundPriceDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FundPriceDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.fundPriceDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun upsertAll_round_trip_och_senaste_kurs() = runTest {
        dao.upsertAll(
            listOf(
                FundPriceEntity(fundId = "SHB0000442", epochDay = 100, nav = 140.0, currency = "SEK"),
                FundPriceEntity(fundId = "SHB0000442", epochDay = 101, nav = 145.0, currency = "SEK"),
                FundPriceEntity(fundId = "SHB0000627", epochDay = 100, nav = 200.0, currency = "NOK"),
            ),
        )

        val latest = dao.getLatest("SHB0000442")
        assertEquals(145.0, latest?.nav ?: -1.0, 1e-9)

        val range = dao.getRange("SHB0000442", fromEpochDay = 100, toEpochDay = 100)
        assertEquals(1, range.size)
        assertEquals(140.0, range.first().nav, 1e-9)

        assertNull(dao.getLatest("OKAND"))
    }

    @Test
    fun upsertAll_ersatter_befintlig_kurs_for_samma_dag() = runTest {
        dao.upsertAll(listOf(FundPriceEntity(fundId = "SHB0000442", epochDay = 100, nav = 100.0, currency = "SEK")))
        dao.upsertAll(listOf(FundPriceEntity(fundId = "SHB0000442", epochDay = 100, nav = 110.0, currency = "SEK")))

        val range = dao.getRange("SHB0000442", fromEpochDay = 100, toEpochDay = 100)
        assertEquals(1, range.size)
        assertEquals(110.0, range.first().nav, 1e-9)
    }

    @Test
    fun observeLatest_emitterar_senaste_kurs_per_fund() = runTest {
        dao.upsertAll(
            listOf(
                FundPriceEntity(fundId = "SHB0000442", epochDay = 100, nav = 140.0, currency = "SEK"),
                FundPriceEntity(fundId = "SHB0000442", epochDay = 101, nav = 145.0, currency = "SEK"),
                FundPriceEntity(fundId = "SHB0000627", epochDay = 100, nav = 200.0, currency = "SEK"),
                FundPriceEntity(fundId = "OKAND", epochDay = 100, nav = 999.0, currency = "SEK"),
            ),
        )

        val latest = dao.observeLatest(listOf("SHB0000442", "SHB0000627")).first()

        assertEquals(2, latest.size)
        assertEquals(145.0, latest.first { it.fundId == "SHB0000442" }.nav, 1e-9)
        assertEquals(200.0, latest.first { it.fundId == "SHB0000627" }.nav, 1e-9)
    }

    @Test
    fun observeLatest_uppdateras_reaktivt_vid_ny_kurs() = runTest {
        dao.upsertAll(listOf(FundPriceEntity(fundId = "SHB0000442", epochDay = 100, nav = 140.0, currency = "SEK")))
        assertEquals(140.0, dao.observeLatest(listOf("SHB0000442")).first().first().nav, 1e-9)

        dao.upsertAll(listOf(FundPriceEntity(fundId = "SHB0000442", epochDay = 101, nav = 150.0, currency = "SEK")))

        assertEquals(150.0, dao.observeLatest(listOf("SHB0000442")).first().first().nav, 1e-9)
    }
}
