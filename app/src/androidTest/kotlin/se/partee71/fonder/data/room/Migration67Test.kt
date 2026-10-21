package se.partee71.fonder.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.entities.FxRateEntity

/**
 * Migration 6→7 (issue #43) lägger till `fx_rates` för Riksbankens dagsnoterade växelkurser,
 * så fondlistas kurser i fondens egen valuta kan räknas om till kronor i stället för att
 * kastas (KRAVLISTA TP-19/TP-20). Tömmer samtidigt `fund_prices`: Avanza och fondlista
 * skiljer sig någon promille i växelkurstidpunkt, och utan tömning hade den skillnaden synts
 * som en konstlad dagsrörelse just där källan byter för en fond.
 *
 * Samma mönster som de övriga migreringstesterna.
 */
@RunWith(AndroidJUnit4::class)
class Migration67Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration67-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migration_6_7_lagger_till_fx_rates_och_tommer_kurscachen() = runTest {
        context.createLegacyDatabase(dbName, version = 6) { db ->
            db.execSQL(
                "INSERT INTO funds (fundId, name, currency, isin, fondlistaFundId) " +
                    "VALUES ('LU0496367417', 'Franklin Gold', 'USD', 'LU0496367417', '0P0000O30D')"
            )
            db.execSQL(
                "INSERT INTO transactions (fundId, type, epochDay, shares, pricePerShare, fee) " +
                    "VALUES ('LU0496367417', 'KOP', 20000, 2.0, 150.0, 0.0)"
            )
            db.execSQL("INSERT INTO fund_prices VALUES ('LU0496367417', 20658, 164.35, 'SEK')")
        }

        val db = context.openMigratedDatabase(dbName)

        // Kurscachen är tömd — hämtas om vid nästa uppdatering, nu via fondlista + konvertering.
        assertTrue(
            db.fundPriceDao().getRange("LU0496367417", fromEpochDay = Long.MIN_VALUE, toEpochDay = Long.MAX_VALUE)
                .isEmpty()
        )

        // fx_rates är skrivbar direkt efter migreringen.
        db.fxRateDao().upsertAll(listOf(FxRateEntity("USD", 20658, 9.73973)))
        assertEquals(9.73973, db.fxRateDao().getLatest("USD")?.rate ?: -1.0, 1e-9)

        // Fonderna och transaktionerna rörs inte — bara den härledda kurscachen (NFR-1).
        assertEquals("Franklin Gold", db.fundDao().getByFundId("LU0496367417")?.name)
        assertEquals("0P0000O30D", db.fundDao().getByFundId("LU0496367417")?.fondlistaFundId)
        assertEquals(1, db.transactionDao().observeForFund("LU0496367417").first().size)

        db.close()
    }
}
