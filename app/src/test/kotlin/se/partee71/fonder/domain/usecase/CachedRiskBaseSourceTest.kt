package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.repository.FakeCachedPriceRepository
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.data.repository.FakeTransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.testing.testPreferencesRepository

/**
 * [CachedRiskBaseSource] (issue #240): Besluts riskkort räknas ur innehaven, kurscachen och den
 * **cachade** metadatan — samma underlag som Översiktens kort, utan nätverk.
 */
class CachedRiskBaseSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val transactions = FakeTransactionRepository()
    private val prices = FakeCachedPriceRepository()
    private val metadata = FakeFundMetadataRepository()
    private val today = LocalDate.now()

    private fun meta(isin: String, risk: Int) = FundMetadata(
        isin = isin, name = isin, orderbookId = isin, totalFee = 0.5, managementFee = 0.5,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList()
    )

    private fun buy(id: Long, fundId: String, shares: Double) = Transaction(id = id, fundId = fundId, type = TransactionType.KOP, epochDay = today.toEpochDay() - 30, shares = shares, pricePerShare = 100.0)

    @Test
    fun `innehavens varde per niva ur kurscachen och den cachade metadatan`() = runTest(dispatcher) {
        val preferences = testPreferencesRepository(dispatcher, tempFolder, "cached_risk")
        preferences.setRiskProfile(RiskProfile(targetAllocation = mapOf(2 to 0.5, 5 to 0.5)))
        transactions.funds.value = listOf(Fund(fundId = "A", name = "A", isin = "SE_A"), Fund(fundId = "B", name = "B", isin = "SE_B"))
        transactions.transactions.value = listOf(buy(1, "A", 10.0), buy(2, "B", 30.0))
        prices.latestPrices.value = mapOf(
            "A" to FundPrice(fundId = "A", epochDay = today.toEpochDay(), nav = 120.0),
            "B" to FundPrice(fundId = "B", epochDay = today.toEpochDay(), nav = 100.0)
        )
        metadata.metadataByIsin = mapOf("SE_A" to meta("SE_A", 2), "SE_B" to meta("SE_B", 5))
        val source = CachedRiskBaseSource(transactions, prices, metadata, PortfolioRiskSource(preferences, FakeSwitchWatchRepository(), FakeSuggestionRecordRepository()))

        val base = source.observe().first { it != null }!!

        assertEquals(1_200.0, base.valueByLevelKr.getValue(2), 1e-9)
        assertEquals(3_000.0, base.valueByLevelKr.getValue(5), 1e-9)
        assertTrue(base.pendingProceedsByLevel.isEmpty())
    }

    @Test
    fun `utan profil finns inget underlag`() = runTest(dispatcher) {
        val preferences = testPreferencesRepository(dispatcher, tempFolder, "cached_risk_none")
        val source = CachedRiskBaseSource(transactions, prices, metadata, PortfolioRiskSource(preferences, FakeSwitchWatchRepository(), FakeSuggestionRecordRepository()))

        assertNull(source.observe().first())
    }
}
