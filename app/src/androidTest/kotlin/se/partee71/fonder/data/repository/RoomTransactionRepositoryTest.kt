package se.partee71.fonder.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.AppDatabase
import se.partee71.fonder.data.room.entities.FundEntity
import se.partee71.fonder.data.room.entities.FundPriceEntity
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity
import se.partee71.fonder.data.room.entities.TransactionEntity
import se.partee71.fonder.domain.model.Fund

/**
 * Verifierar [TransactionRepository.clearAll] (SET-1, töm databasen) och att [TransactionRepository.upsertFund]
 * slår ihop mot lagrad rad i stället för att skriva över den.
 */
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
        repository = RoomTransactionRepository(db, db.fundDao(), db.transactionDao(), db.fundPriceDao(), db.suggestionRecordDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun clearAll_tommer_fonder_transaktioner_kurser_och_inspelade_forslag() = runTest {
        db.fundDao().upsert(FundEntity(fundId = "SHB0000442", name = "Fond A", currency = "SEK", isin = "SE0000582033"))
        db.transactionDao().insert(
            TransactionEntity(fundId = "SHB0000442", type = "KOP", epochDay = 100, shares = 3.0, pricePerShare = 50.0),
        )
        db.fundPriceDao().upsertAll(
            listOf(FundPriceEntity(fundId = "SHB0000442", epochDay = 100, nav = 50.0, currency = "SEK")),
        )
        // Inspelade bytesförslag (HEM-8) är användardata, inte cache — de måste med i tömningen,
        // annars visar Hem råd som prissatts mot en portfölj som inte längre finns.
        db.suggestionRecordDao().insert(
            SuggestionRecordEntity(
                suggestedAtEpochDay = 100,
                planIndex = 0,
                sellIsin = "SE0000582033",
                buyIsin = "SE0001466368",
                sellNavAtSuggestion = 50.0,
                buyNavAtSuggestion = 120.0,
                switchValueKr = 1_000.0,
                followed = null,
            ),
        )

        repository.clearAll()

        assertTrue(repository.observeFunds().first().isEmpty())
        assertTrue(repository.observeTransactions().first().isEmpty())
        assertTrue(db.fundPriceDao().getRange("SHB0000442", 0, 200).isEmpty())
        assertTrue(db.suggestionRecordDao().getAll().isEmpty())
    }

    @Test
    fun upsertFund_behaller_lagrat_isin_nar_den_inkommande_fonden_saknar_det() = runTest {
        // Regression: `@Insert(REPLACE)` skrev hela raden, så en fond från katalogen/sökträffen
        // (isin = null) raderade ett ISIN användaren själv bekräftat i Fonddetalj (NAV-2) —
        // och därmed hela kurskedjan via ISIN för den fonden.
        val fund = Fund(fundId = "SHB0000442", name = "Fond A", currency = "SEK")
        repository.upsertFund(fund)
        repository.upsertFund(fund.copy(isin = "SE0000582033", fondlistaFundId = "FL-442"))

        repository.upsertFund(fund)

        val stored = repository.observeFunds().first().single()
        assertEquals("SE0000582033", stored.isin)
        assertEquals("FL-442", stored.fondlistaFundId)
    }

    @Test
    fun upsertFund_skriver_over_lagrat_isin_med_ett_nytt_ifyllt_varde() = runTest {
        // Sammanslagningen får inte göra fältet oföränderligt: rättar användaren ett felaktigt
        // ISIN ska det nya värdet vinna.
        val fund = Fund(fundId = "SHB0000442", name = "Fond A", currency = "SEK", isin = "SE0000000000")
        repository.upsertFund(fund)

        repository.upsertFund(fund.copy(isin = "SE0000582033"))

        assertEquals("SE0000582033", repository.observeFunds().first().single().isin)
    }
}
