package se.partee71.fonder.data.room

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.daos.FundMetadataDao
import se.partee71.fonder.data.room.entities.FundMetadataEntity

/** DAO-rundtur för [FundMetadataDao] (KRAVLISTA TP-21, issue #57) — samma mönster som [FxRateDaoTest]. */
@RunWith(AndroidJUnit4::class)
class FundMetadataDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FundMetadataDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.fundMetadataDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(
        isin: String,
        name: String = isin,
        availableAtHandelsbanken: Boolean? = null,
    ) = FundMetadataEntity(
        isin = isin, name = name, orderbookId = isin, totalFee = 0.21, managementFee = 0.2,
        category = "Sverige", fundType = "EQUITY_FUND", companyName = "Länsförsäkringar", risk = 4,
        indexFund = true, startDateEpochDay = 12000, minimumBuy = 100.0,
        tagsJson = """[{"title":"Sverige","category":"COMMON_REGION"}]""",
        availableAtHandelsbanken = availableAtHandelsbanken, availabilityResolvedAtEpochDay = null,
        fetchedAtEpochDay = 20000,
    )

    @Test
    fun `upsert och getByIsin rundtur`() = runTest {
        dao.upsert(entity("SE1", name = "Fond Ett"))

        val loaded = dao.getByIsin("SE1")

        assertEquals("Fond Ett", loaded?.name)
        assertEquals(0.21, loaded?.totalFee ?: -1.0, 1e-9)
        assertEquals("Sverige", loaded?.category)
        assertTrue(loaded?.indexFund ?: false)
    }

    @Test
    fun `getByIsin ar null for okand isin`() = runTest {
        assertNull(dao.getByIsin("OKAND"))
    }

    @Test
    fun `upsertAll skriver flera rader och getAll laser tillbaka alla`() = runTest {
        dao.upsertAll(listOf(entity("SE1"), entity("SE2")))

        val all = dao.getAll()

        assertEquals(2, all.size)
        assertEquals(setOf("SE1", "SE2"), all.map { it.isin }.toSet())
    }

    @Test
    fun `upsert ersatter befintlig rad for samma isin`() = runTest {
        dao.upsert(entity("SE1", availableAtHandelsbanken = null))
        dao.upsert(entity("SE1", availableAtHandelsbanken = true))

        val all = dao.getAll()

        assertEquals(1, all.size)
        assertEquals(true, all.first().availableAtHandelsbanken)
    }

    @Test
    fun `deleteAll tommer tabellen`() = runTest {
        dao.upsert(entity("SE1"))

        dao.deleteAll()

        assertTrue(dao.getAll().isEmpty())
    }
}
