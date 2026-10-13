package se.partee71.fonder.domain.usecase

import app.cash.turbine.test
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeSuggestionDismissalRepository
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.testing.testPreferencesRepository
import java.time.LocalDate

/** De avstådda planbytena (HEM-14), resolvade — en källa för Beslut och Fonddetalj. */
class DismissedPlanSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val records = FakeSuggestionRecordRepository()
    private val dismissals = FakeSuggestionDismissalRepository()
    private val metadata = FakeFundMetadataRepository(
        metadataByIsin = listOf("SE_SALJ" to 5, "SE_KOP" to 3, "SE_ANNAN" to 3).associate { (isin, risk) ->
            isin to FundMetadata(
                isin = isin, name = "Fond $isin", orderbookId = isin, totalFee = 0.5, managementFee = 0.5,
                category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
                startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
            )
        },
    )
    private var today: LocalDate = LocalDate.of(2026, 10, 12)

    private suspend fun source(accountType: AccountType? = AccountType.ISK_KF): DismissedPlanSource {
        val preferences = testPreferencesRepository(dispatcher, tempFolder, "dismissed_${System.nanoTime()}")
        accountType?.let { preferences.setAccountType(it) }
        return DismissedPlanSource(records, dismissals, preferences, metadata) { today }
    }

    private fun record(id: Long, buyIsin: String = "SE_KOP", followed: Boolean? = null) = SuggestionRecord(
        id = id, suggestedAtEpochDay = today.toEpochDay(), planIndex = 0, sellIsin = "SE_SALJ", buyIsin = buyIsin,
        sellNavAtSuggestion = 100.0, buyNavAtSuggestion = 50.0, followed = followed,
    )

    @Test
    fun `ett utganget avstaende faller ur vid nasta emission utan att tabellen rorts`() = runTest(dispatcher) {
        // Giltigheten räknas vid varje emission, som partition gör — inte bara när avståendena skrivs.
        records.records.value = listOf(record(1))
        dismissals.dismiss("SE_SALJ", "SE_KOP", SuggestionKind.RISK_PLAN, today)

        source().observe().test {
            var plan = awaitItem()
            while (plan.suggestions.isEmpty()) plan = awaitItem()
            assertEquals(listOf(1L), plan.suggestions.map { it.recordId })
            // Resolverad som Beslut och Fonddetalj visar den: namn ur cachen, planens sort.
            assertEquals("Fond SE_SALJ", plan.suggestions.single().sellFundName)

            today = today.plusDays(SuggestionDismissals.TTL_DAYS + 1)
            records.records.value = listOf(record(1), record(2, buyIsin = "SE_ANNAN"))

            assertTrue(awaitItem().suggestions.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ett genomfort forslag och ett avgiftsbyte raknas aldrig som avstadda planbyten`() = runTest(dispatcher) {
        records.records.value = listOf(record(1, followed = true), record(2, buyIsin = "SE_FEE").copy(kind = SuggestionKind.FEE))
        dismissals.dismiss("SE_SALJ", "SE_KOP", SuggestionKind.RISK_PLAN, today)
        dismissals.dismiss("SE_SALJ", "SE_FEE", SuggestionKind.FEE, today)

        source().observe().test {
            var plan = awaitItem()
            while (plan.dismissals.size < 2) plan = awaitItem()
            assertTrue(plan.suggestions.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `utan ISK eller KF finns inga avstadda planbyten - samma grind som planen`() = runTest(dispatcher) {
        records.records.value = listOf(record(1))
        dismissals.dismiss("SE_SALJ", "SE_KOP", SuggestionKind.RISK_PLAN, today)

        source(AccountType.DEPA_AF).observe().test {
            var plan = awaitItem()
            while (plan.dismissals.isEmpty()) plan = awaitItem()
            assertTrue(plan.suggestions.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
