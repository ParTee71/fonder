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
            .associate { (isin, risk) -> isin to meta(isin, risk) } +
            // Ett innehav vars senaste jämförelse (ANA-9) visar ett billigare alternativ.
            mapOf(
                "SE_DYR" to meta("SE_DYR", 4, fee = 1.4, shown = listOf("SE_BILLIG")),
                "SE_BILLIG" to meta("SE_BILLIG", 4, fee = 0.4),
            ),
    )

    private fun meta(isin: String, risk: Int, fee: Double = 0.5, shown: List<String> = emptyList()) = FundMetadata(
        isin = isin, name = isin, orderbookId = isin, totalFee = fee, managementFee = fee,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(), shownAlternativeIsins = shown,
    )

    /** Ett inspelat avgiftsbyte (ANA-9) SE_DYR → SE_BILLIG, [daysAgo] dagar gammalt. */
    private fun feeRecord(id: Long, followed: Boolean? = null, daysAgo: Long = 0) = SuggestionRecord(
        id = id, suggestedAtEpochDay = LocalDate.now().minusDays(daysAgo).toEpochDay(), planIndex = 0,
        sellIsin = "SE_DYR", buyIsin = "SE_BILLIG", sellNavAtSuggestion = 100.0, buyNavAtSuggestion = 50.0,
        switchValueKr = 10_000.0, followed = followed, batchEpochMillis = 2_000, kind = SuggestionKind.FEE,
    )

    private fun record(id: Long, planIndex: Int, followed: Boolean? = null) = SuggestionRecord(
        id = id, suggestedAtEpochDay = LocalDate.now().toEpochDay(), planIndex = planIndex,
        sellIsin = "SE_SALJ_${planIndex + 1}", buyIsin = "SE_KOP_${planIndex + 1}",
        sellNavAtSuggestion = 100.0, buyNavAtSuggestion = 50.0, switchValueKr = 1_000.0,
        followed = followed, batchEpochMillis = 1_000,
    )

    /** Läser källan när [until] är uppfyllt — som standard när planen (synlig eller avstådd) har kommit fram. */
    private suspend fun TestScope.decisions(
        until: (PendingDecisions) -> Boolean = { it.switchPlan.size + it.dismissedSuggestions.size == 2 },
    ): PendingDecisions {
        val preferences = testPreferencesRepository(dispatcher, tempFolder, "pending_${System.nanoTime()}")
        preferences.setAccountType(AccountType.ISK_KF)
        val source = PendingDecisionsSource(
            preferences, records, FakeSwitchWatchRepository(), FakeCachedPriceRepository(), metadata, dismissals,
        )
        return source.observe().first(until)
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

    // --- Billigare alternativ (ANA-9) ur de inspelade avgiftsbytena ---

    @Test
    fun `ett oppet billigare alternativ visas och raknas`() = runTest(dispatcher) {
        records.records.value = listOf(record(1, 0), record(2, 1), feeRecord(10))

        val decisions = decisions()

        val alternative = decisions.cheaperAlternatives.single()
        assertEquals(10L, alternative.suggestion.recordId)
        assertEquals(SuggestionKind.FEE, alternative.suggestion.kind)
        assertEquals(100.0, alternative.annualSavingsKr!!, 1e-6)
        // Planens byten och avgiftsbytet blandas aldrig: planen har sina två byten, inget mer.
        assertEquals(listOf(1L, 2L), decisions.switchPlan.map { it.recordId })
        assertEquals(2, decisions.openSwitchCount)
        assertEquals(1, decisions.openCheaperAlternativeCount)
        assertEquals(3, decisions.openSuggestionCount)
        assertEquals(3, decisions.count)
    }

    @Test
    fun `ett genomfort billigare alternativ visas men raknas inte`() = runTest(dispatcher) {
        records.records.value = listOf(record(1, 0), record(2, 1), feeRecord(10, followed = true))
        dismissals.dismiss("SE_DYR", "SE_BILLIG", SuggestionKind.FEE, LocalDate.now())

        val decisions = decisions()

        // Kvitteringen är det starkare beskedet — även ett avstått par visas då som genomfört.
        assertTrue(decisions.cheaperAlternatives.single().suggestion.followed)
        assertTrue(decisions.dismissedCheaperAlternatives.isEmpty())
        assertEquals(0, decisions.openCheaperAlternativeCount)
        assertEquals(2, decisions.count)
    }

    @Test
    fun `ett avstatt billigare alternativ ligger bland de avstadda och raknas inte`() = runTest(dispatcher) {
        records.records.value = listOf(record(1, 0), record(2, 1), feeRecord(10))
        dismissals.dismiss("SE_DYR", "SE_BILLIG", SuggestionKind.FEE, LocalDate.now())

        val decisions = decisions()

        assertTrue(decisions.cheaperAlternatives.isEmpty())
        assertEquals(listOf(10L), decisions.dismissedCheaperAlternatives.map { it.suggestion.recordId })
        assertEquals(1, decisions.dismissedCount)
        // Avståendet gäller sorten: planens byten påverkas inte.
        assertEquals(2, decisions.switchPlan.size)
        assertEquals(2, decisions.count)
    }

    @Test
    fun `ett billigare alternativ som inte spelats in pa TTL_DAYS visas inte`() = runTest(dispatcher) {
        records.records.value = listOf(record(1, 0), record(2, 1), feeRecord(10, daysAgo = CheaperAlternatives.TTL_DAYS + 2))

        val decisions = decisions()

        assertTrue(decisions.cheaperAlternatives.isEmpty())
        assertTrue(decisions.dismissedCheaperAlternatives.isEmpty())
        assertEquals(2, decisions.count)
    }

    @Test
    fun `billigare alternativ visas utan bytesplan och raknas i det tomma laget`() = runTest(dispatcher) {
        records.records.value = listOf(feeRecord(10))

        val decisions = decisions { it.cheaperAlternatives.isNotEmpty() }

        assertTrue(decisions.switchPlan.isEmpty())
        assertEquals(1, decisions.count)
        assertTrue(!decisions.isEmpty)
    }
}
