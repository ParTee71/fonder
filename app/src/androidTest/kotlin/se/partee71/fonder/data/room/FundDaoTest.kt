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
import se.partee71.fonder.data.room.daos.FundDao
import se.partee71.fonder.data.room.daos.TransactionDao
import se.partee71.fonder.data.room.entities.FundEntity
import se.partee71.fonder.data.room.entities.TransactionEntity

@RunWith(AndroidJUnit4::class)
class FundDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var fundDao: FundDao
    private lateinit var transactionDao: TransactionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        fundDao = db.fundDao()
        transactionDao = db.transactionDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun fund_round_trip() = runTest {
        val fund = FundEntity(isin = "SE0000000001", name = "Fond A", currency = "SEK")
        fundDao.upsert(fund)

        assertEquals(fund, fundDao.getByIsin("SE0000000001"))
        assertEquals(listOf(fund), fundDao.observeAll().first())

        fundDao.deleteByIsin("SE0000000001")
        assertNull(fundDao.getByIsin("SE0000000001"))
    }

    @Test
    fun transaction_round_trip_and_link_to_fund() = runTest {
        fundDao.upsert(FundEntity(isin = "SE0000000001", name = "Fond A", currency = "SEK"))
        val id = transactionDao.insert(
            TransactionEntity(
                fundIsin = "SE0000000001",
                type = "KOP",
                epochDay = 100,
                shares = 3.0,
                pricePerShare = 50.0,
            ),
        )

        val stored = transactionDao.observeForFund("SE0000000001").first()
        assertEquals(1, stored.size)
        assertEquals(3.0, stored.first().shares, 1e-9)
        assertEquals(150.0, stored.first().toDomain().amount, 1e-9)

        transactionDao.deleteById(id)
        assertEquals(0, transactionDao.getAll().size)
    }
}
