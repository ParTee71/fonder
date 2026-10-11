package se.partee71.fonder.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.repository.FakeCachedPriceRepository
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeSuggestionDismissalRepository
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.testing.testPreferencesRepository
import java.time.LocalDate

/**
 * HEM-14 genom den delade källan: Beslut, flikmärket (NAV-9) och "Att besluta" (HEM-13) läser alla
 * [PendingDecisions], så det är här ett avstående måste falla ur antalet.
 *
 * Avståendenas datum ligger med marginal från gränsen (i dag respektive dag 31): källan läser
 * `LocalDate.now()` själv, och ett test som korsar midnatt får inte byta utfall. Exakt gräns
 * testas med fast datum i [SuggestionDismissalsTest].
 */
class PendingDecisionsSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val records = FakeSuggestionRecordRepository()
    private val dismissals = FakeSuggestionDismissalRepository()
    private val metadata = FakeFundMetadataRepository(
        metadataByIsin = listOf("SE_SALJ_1" to 5, "SE_KOP_1" to 3, "SE_SALJ_2" to 6, "SE_KOP_2" to 4)
            .associate { (isin, risk) -> isin to meta(isin, risk) },
    )

    private fun meta(isin: String, risk: Int) = FundMetadata(
        isin = isin, name = isin, orderbookId = isin, totalFee = 0.5, managementFee = 0.5,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
    )

    private fun record(id: Long, planIndex: Int, followed: Boolean? = null) = SuggestionRecord(
        id = id, suggestedAtEpochDay = LocalDate.now().toEpochDay(), planIndex = planIndex,
        sellIsin = "SE_SALJ_${planIndex + 1}", buyIsin = "SE_KOP_${planIndex + 1}",
        sellNavAtSuggestion = 100.0, buyNavAtSuggestion = 50.0, switchValueKr = 1_000.0,
        followed = followed, batchEpochMillis = 1_000,
    )

    /** Läser källan när planen (synlig eller avstådd) har kommit fram. */
    private suspend fun TestScope.decisions(): PendingDecisions {
        val preferences = testPreferencesRepository(dispatcher, tempFolder, "pending_${System.nanoTime()}")
        preferences.setAccountType(AccountType.ISK_KF)
        val source = PendingDecisionsSource(
            preferences, records, FakeSwitchWatchRepository(), FakeCachedPriceRepository(), metadata, dismissals,
        )
        return source.observe().first { it.switchPlan.size + it.dismissedSuggestions.size == 2 }
    }

    @Test
    fun `ett avstatt forslag saknas i planen och raknas inte`() = runTest(dispatcher) {
        records.records.value = listOf(record(1, 0), record(2, 1))
        dismissals.dismiss("SE_SALJ_1", "SE_KOP_1", SuggestionKind.RISK_PLAN, LocalDate.now())

        val decisions = decisions()

        assertEquals(listOf(2L), decisions.switchPlan.map { it.recordId })
        assertEquals(listOf(1L), decisions.dismissedSuggestions.map { it.recordId })
        assertEquals(1, decisions.openSuggestionCount)
        assertEquals(1, decisions.count)
    }

    @Test
    fun `ett utganget avstaende pa dag 31 raknas igen`() = runTest(dispatcher) {
        records.records.value = listOf(record(1, 0), record(2, 1))
        dismissals.dismiss("SE_SALJ_1", "SE_KOP_1", SuggestionKind.RISK_PLAN, LocalDate.now().minusDays(SuggestionDismissals.TTL_DAYS + 1))

        val decisions = decisions()

        assertEquals(listOf(1L, 2L), decisions.switchPlan.map { it.recordId })
        assertTrue(decisions.dismissedSuggestions.isEmpty())
        assertEquals(2, decisions.openSuggestionCount)
        assertEquals(2, decisions.count)
    }

    @Test
    fun `ett genomfort forslag behandlas aldrig som avstatt`() = runTest(dispatcher) {
        records.records.value = listOf(record(1, 0, followed = true), record(2, 1))
        dismissals.dismiss("SE_SALJ_1", "SE_KOP_1", SuggestionKind.RISK_PLAN, LocalDate.now())

        val decisions = decisions()

        assertEquals(listOf(1L, 2L), decisions.switchPlan.map { it.recordId })
        assertTrue(decisions.switchPlan.first().followed)
        assertTrue(decisions.dismissedSuggestions.isEmpty())
        // Det genomförda är redan beslutat (SET-5) — bara det andra är öppet.
        assertEquals(1, decisions.openSuggestionCount)
        assertEquals(1, decisions.count)
    }
}
