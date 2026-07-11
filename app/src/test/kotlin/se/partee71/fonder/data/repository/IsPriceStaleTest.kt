package se.partee71.fonder.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundPrice
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Testar [isPriceStale] mot [se.partee71.fonder.domain.usecase.NavCalendar] direkt (issue #27,
 * TP-17) — helg och kväll ska inte längre räknas som inaktuellt bara för att kursen inte är från
 * exakt idag, se [se.partee71.fonder.domain.usecase.NavCalendarTest] för själva kalenderlogiken.
 */
class IsPriceStaleTest {

    private var cachedPrice: FundPrice? = null

    private val fakeRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? = cachedPrice
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = flowOf(emptyMap())
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice> = emptyList()
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())
        override suspend fun refresh(fundId: String): Boolean = true
        override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate): Boolean = true
        override suspend fun suggestIsin(fundName: String): String? = null
        override suspend fun findFundByIsin(isin: String): Fund? = null
        override suspend fun fetchFundCatalog(): FundCatalog = FundCatalog(emptyList(), emptyList())
    }

    @Test
    fun `ingen cachad kurs alls ar inaktuell`() = runTest {
        cachedPrice = null
        assertTrue(fakeRepo.isPriceStale("F1", LocalDateTime.of(2026, 7, 15, 19, 0)))
    }

    @Test
    fun `fredagens kurs ar inte inaktuell pa lordagen`() = runTest {
        // 2026-07-10 är en fredag, 2026-07-11 en lördag.
        cachedPrice = FundPrice(fundId = "F1", epochDay = LocalDate.of(2026, 7, 10).toEpochDay(), nav = 100.0)
        val saturday = LocalDateTime.of(2026, 7, 11, 12, 0)
        assertFalse(fakeRepo.isPriceStale("F1", saturday))
    }

    @Test
    fun `gardagens kurs ar inte inaktuell en vardagsmorgon fore publicering`() = runTest {
        // 2026-07-14 är en tisdag, 2026-07-15 en onsdag.
        cachedPrice = FundPrice(fundId = "F1", epochDay = LocalDate.of(2026, 7, 14).toEpochDay(), nav = 100.0)
        val wednesdayMorning = LocalDateTime.of(2026, 7, 15, 8, 0)
        assertFalse(fakeRepo.isPriceStale("F1", wednesdayMorning))
    }

    @Test
    fun `gardagens kurs ar inaktuell en vardagskvall efter publicering`() = runTest {
        cachedPrice = FundPrice(fundId = "F1", epochDay = LocalDate.of(2026, 7, 14).toEpochDay(), nav = 100.0)
        val wednesdayEvening = LocalDateTime.of(2026, 7, 15, 19, 0)
        assertTrue(fakeRepo.isPriceStale("F1", wednesdayEvening))
    }

    @Test
    fun `en vecka gammal kurs ar alltid inaktuell`() = runTest {
        cachedPrice = FundPrice(fundId = "F1", epochDay = LocalDate.of(2026, 7, 1).toEpochDay(), nav = 100.0)
        assertTrue(fakeRepo.isPriceStale("F1", LocalDateTime.of(2026, 7, 15, 8, 0)))
    }
}
