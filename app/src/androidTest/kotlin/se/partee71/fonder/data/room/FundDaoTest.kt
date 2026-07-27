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
        val fund = FundEntity(fundId = "SHB0000442", name = "Fond A", currency = "SEK")
        fundDao.upsert(fund)

        assertEquals(fund, fundDao.getByFundId("SHB0000442"))
        assertEquals(listOf(fund), fundDao.observeAll().first())

        fundDao.deleteByFundId("SHB0000442")
        assertNull(fundDao.getByFundId("SHB0000442"))
    }

    @Test
    fun fund_isin_survives_round_trip() = runTest {
        // ISIN fylls numera automatiskt vid tillägg via fondsök och vid importmatchning
        // (KRAVLISTA TP-18, issue #37) — fältet måste överleva skrivning/läsning oförändrat,
        // så det inte tyst tappas på vägen till (den planerade) backup-kedjan, NFR-1.
        val fund = FundEntity(fundId = "0P0001KRE7", name = "CPR Invest Global Gold Mines A USD Acc", currency = "USD", isin = "LU1989766289")
        fundDao.upsert(fund)

        assertEquals("LU1989766289", fundDao.getByFundId("0P0001KRE7")?.isin)
        assertEquals(fund, fundDao.observeAll().first().single())

        // En senare uppsert utan ISIN ska inte behålla ett gammalt värde i smyg.
        fundDao.upsert(fund.copy(isin = null))
        assertNull(fundDao.getByFundId("0P0001KRE7")?.isin)
    }

    @Test
    fun fund_fondlistaFundId_survives_round_trip() = runTest {
        // Uppslaget fondlista-id för en fond vars identitet är dess ISIN (issue #39). Härledd
        // cache-data, men persisterad — måste överleva skrivning/läsning oförändrad och ingå i
        // backup-kedjan när den byggs (NFR-1, backup är ännu en stub).
        val fund = FundEntity(
            fundId = "LU0496367417",
            name = "Franklin Gold and Prec Mtls A(acc)USD",
            currency = "USD",
            isin = "LU0496367417",
            fondlistaFundId = "0P0000O30D",
        )
        fundDao.upsert(fund)

        assertEquals("0P0000O30D", fundDao.getByFundId("LU0496367417")?.fondlistaFundId)
        assertEquals(fund, fundDao.observeAll().first().single())

        // Identiteten följer fundId — uppslaget id är bara en hämtningsnyckel.
        assertEquals("LU0496367417", fundDao.getByFundId("LU0496367417")?.fundId)

        fundDao.upsert(fund.copy(fondlistaFundId = null))
        assertNull(fundDao.getByFundId("LU0496367417")?.fondlistaFundId)
    }

    @Test
    fun transaction_round_trip_and_link_to_fund() = runTest {
        fundDao.upsert(FundEntity(fundId = "SHB0000442", name = "Fond A", currency = "SEK"))
        val id = transactionDao.insert(
            TransactionEntity(
                fundId = "SHB0000442",
                type = "KOP",
                epochDay = 100,
                shares = 3.0,
                pricePerShare = 50.0,
            ),
        )

        val stored = transactionDao.observeForFund("SHB0000442").first()
        assertEquals(1, stored.size)
        assertEquals(3.0, stored.first().shares, 1e-9)
        assertEquals(150.0, stored.first().toDomain().amount, 1e-9)

        transactionDao.deleteById(id)
        assertEquals(0, transactionDao.getAll().size)
    }
}
