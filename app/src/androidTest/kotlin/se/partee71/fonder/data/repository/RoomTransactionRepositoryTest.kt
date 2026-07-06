package se.partee71.fonder.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.AppDatabase
import se.partee71.fonder.data.room.entities.FundEntity
import se.partee71.fonder.data.room.entities.FundPriceEntity
import se.partee71.fonder.data.room.entities.TransactionEntity

/** Verifierar [TransactionRepository.clearAll] — se SET-1 (töm databasen, Inställningar). */
@RunWith(AndroidJUnit4::class)
class RoomTransactionRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TransactionRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        repository = RoomTransactionRepository(db, db.fundDao(), db.transactionDao(), db.fundPriceDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun clearAll_tommer_fonder_transaktioner_och_cachade_kurser() = runTest {
        db.fundDao().upsert(FundEntity(fundId = "SHB0000442", name = "Fond A", currency = "SEK", isin = "SE0000582033"))
        db.transactionDao().insert(
            TransactionEntity(fundId = "SHB0000442", type = "KOP", epochDay = 100, shares = 3.0, pricePerShare = 50.0),
        )
        db.fundPriceDao().upsertAll(
            listOf(FundPriceEntity(fundId = "SHB0000442", epochDay = 100, nav = 50.0, currency = "SEK")),
        )

        repository.clearAll()

        assertTrue(repository.observeFunds().first().isEmpty())
        assertTrue(repository.observeTransactions().first().isEmpty())
        assertTrue(db.fundPriceDao().getRange("SHB0000442", 0, 200).isEmpty())
    }
}
