package se.partee71.fonder.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.partee71.fonder.data.repository.FakeCachedPriceRepository
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.data.repository.FakeTransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord

/**
 * Facit räknat på ett ställe (SET-5, NAV-9) — [FacitSummary] läser cachen och ger både Facit-skärmens
 * summeringar och Beslut-flikens resultat för de följda råden ([FacitSnapshot.followedExcessKr]).
 */
class FacitSummaryTest {

    private val suggestionRepo = FakeSuggestionRecordRepository()
    private val transactionRepo = FakeTransactionRepository()
    private val priceRepo = FakeCachedPriceRepository()
    private val metadataRepo = FakeFundMetadataRepository()

    private val summary = FacitSummary(suggestionRepo, transactionRepo, priceRepo, metadataRepo)

    private fun record(
        id: Long,
        followed: Boolean? = true,
        switchValueKr: Double? = 10_000.0,
        kind: SuggestionKind = SuggestionKind.RISK_PLAN,
    ) = SuggestionRecord(
        id = id, suggestedAtEpochDay = 20_000, planIndex = 0,
        sellIsin = "SE_SELL", buyIsin = "SE_BUY",
        sellNavAtSuggestion = 100.0, buyNavAtSuggestion = 200.0,
        switchValueKr = switchValueKr, followed = followed, kind = kind,
    )

    /** Säljfonden är bevakad (kurs under fondens fundId), köpfonden inte (kurs under ISIN:et). */
    private fun prices(sellNavNow: Double?, buyNavNow: Double?) {
        transactionRepo.funds.value = listOf(Fund(fundId = "SHB1", name = "Såld fond", isin = "SE_SELL"))
        priceRepo.latestPrices.value = buildMap {
            sellNavNow?.let { put("SHB1", FundPrice(fundId = "SHB1", epochDay = 20_100, nav = it)) }
            buyNavNow?.let { put("SE_BUY", FundPrice(fundId = "SE_BUY", epochDay = 20_100, nav = it)) }
        }
    }

    @Test
    fun `vinst - de foljda radernas meravkastning i kronor`() = runTest {
        prices(sellNavNow = 105.0, buyNavNow = 224.0) // +12 % mot +5 % → +7 pp
        suggestionRepo.records.value = listOf(record(id = 1), record(id = 2, followed = null))

        val snapshot = summary.observe().first()

        assertEquals(1, snapshot.followedCount)
        assertEquals(700.0, snapshot.followedExcessKr!!, 1e-9)
    }

    @Test
    fun `forlust - negativ meravkastning ger negativt belopp`() = runTest {
        prices(sellNavNow = 110.0, buyNavNow = 200.0) // 0 % mot +10 % → −10 pp
        suggestionRepo.records.value = listOf(record(id = 1))

        val snapshot = summary.observe().first()

        assertEquals(-1_000.0, snapshot.followedExcessKr!!, 1e-9)
    }

    @Test
    fun `utan data - inget belopp, aldrig noll`() = runTest {
        prices(sellNavNow = 105.0, buyNavNow = null)
        suggestionRepo.records.value = listOf(record(id = 1))

        val snapshot = summary.observe().first()

        assertEquals(1, snapshot.followedCount)
        assertNull(snapshot.followedExcessKr)
    }

    @Test
    fun `utan foljda forslag - inget belopp`() = runTest {
        prices(sellNavNow = 105.0, buyNavNow = 224.0)
        suggestionRepo.records.value = listOf(record(id = 1, followed = false))

        val snapshot = summary.observe().first()

        assertEquals(0, snapshot.followedCount)
        assertNull(snapshot.followedExcessKr)
    }

    @Test
    fun `foljd rad utan kant belopp ger inget pahittat belopp`() = runTest {
        prices(sellNavNow = 105.0, buyNavNow = 224.0)
        suggestionRepo.records.value = listOf(record(id = 1, switchValueKr = null))

        assertNull(summary.observe().first().followedExcessKr)
    }

    @Test
    fun `beloppet ar summan av Facits tva genomforda-rader`() = runTest {
        // Sorterna summeras var för sig i Facit (issue #91); kronorna för de följda läggs ihop på
        // Beslut, eftersom de redan är vägda med beloppet och mäts mot samma nollpunkt.
        prices(sellNavNow = 105.0, buyNavNow = 224.0)
        suggestionRepo.records.value = listOf(
            record(id = 1),
            record(id = 2, switchValueKr = 20_000.0, kind = SuggestionKind.FEE),
        )

        val snapshot = summary.observe().first()

        assertEquals(700.0, snapshot.planFollowedSummary.totalExcessKr!!, 1e-9)
        assertEquals(1_400.0, snapshot.feeFollowedSummary.totalExcessKr!!, 1e-9)
        assertEquals(2_100.0, snapshot.followedExcessKr!!, 1e-9)
        assertEquals(2, snapshot.followedCount)
    }

    @Test
    fun `tom historik ger ett tomt facit`() = runTest {
        val snapshot = summary.observe().first()

        assertEquals(0, snapshot.rows.size)
        assertEquals(0, snapshot.followedCount)
        assertNull(snapshot.followedExcessKr)
    }
}
