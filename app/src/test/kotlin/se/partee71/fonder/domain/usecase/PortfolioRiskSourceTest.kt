package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.testing.testPreferencesRepository

/**
 * [PortfolioRiskSource] (issue #238): profilen och bevakningarna ur repositorierna, likviden på
 * målnivån (BYT-3) och planen lagd på utan sålda och genomförda byten.
 */
class PortfolioRiskSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val watches = FakeSwitchWatchRepository()
    private val records = FakeSuggestionRecordRepository()
    private val today = LocalDate.now()

    private val sold = Fund(fundId = "A", name = "A", isin = "SE_A")
    private val sell = Transaction(id = 7, fundId = "A", type = TransactionType.SALJ, epochDay = today.toEpochDay(), shares = 10.0, pricePerShare = 100.0)

    /** Nivå 4 för 1 000 kr kvar i innehaven, 1 000 kr såld och väntar på köp på nivå 2. */
    private val byRiskLevel = PortfolioExposureCalc.Dimension(
        buckets = listOf(PortfolioExposureCalc.Bucket("4", 1_000.0, 1.0)),
        unknownValueKr = 0.0,
        unknownFraction = 0.0,
        unknownCount = 0
    )

    private fun watch() = SwitchWatch(
        id = 1,
        sellIsin = "SE_A",
        sellFundName = "A",
        soldAtEpochDay = today.toEpochDay(),
        proceedsKr = 1_000.0,
        targetLevel = 2,
        sellTransactionId = 7,
        candidates = emptyList()
    )

    private fun suggestion(planIndex: Int, amount: Double?, inProgress: Boolean = false, followed: Boolean = false) = SwitchPlanResolver.Suggestion(
        recordId = planIndex.toLong(), planIndex = planIndex, sellIsin = "S$planIndex", buyIsin = "K$planIndex",
        sellFundName = "S", buyFundName = "K", fromLevel = 4, toLevel = 2, feeDeltaPercent = 0.0,
        switchValueKr = amount, followed = followed, inProgress = inProgress
    )

    @Test
    fun `likviden som vantar pa kop raknas pa bevakningens malniva`() = runTest(dispatcher) {
        val preferences = testPreferencesRepository(dispatcher, tempFolder, "risk")
        preferences.setRiskProfile(RiskProfile(targetAllocation = mapOf(2 to 0.5, 4 to 0.5)))
        watches.watches.value = listOf(watch())
        val source = PortfolioRiskSource(preferences, watches, records)

        val context = source.observeContext().first()
        val base = source.base(context, listOf(sold), listOf(sell), byRiskLevel, emptyMap(), today)!!
        val summary = PortfolioRiskSource.summary(base, emptyList())!!

        assertEquals(mapOf(2 to 1_000.0), base.pendingProceedsByLevel)
        assertEquals(listOf(0.5, 0.5), summary.effect.levels.map { it.actualFraction })
        assertEquals(SwitchEffectCalc.Direction.IN_LINE, summary.effect.direction)
    }

    @Test
    fun `utan profil finns inget underlag`() = runTest(dispatcher) {
        val preferences = testPreferencesRepository(dispatcher, tempFolder, "ingen")
        val source = PortfolioRiskSource(preferences, watches, records)

        val context = source.observeContext().first()

        assertNull(source.base(context, emptyList(), emptyList(), byRiskLevel, emptyMap(), today))
    }

    @Test
    fun `salda och genomforda byten raknas inte en gang till`() {
        val base = PortfolioRiskSource.RiskBase(RiskProfile(targetAllocation = mapOf(2 to 0.5, 4 to 0.5)), mapOf(4 to 1_000.0), emptyMap())

        val summary = PortfolioRiskSource.summary(
            base,
            listOf(
                suggestion(planIndex = 1, amount = 200.0),
                suggestion(planIndex = 0, amount = 300.0, inProgress = true),
                suggestion(planIndex = 2, amount = 100.0, followed = true)
            )
        )!!

        assertEquals(200.0, summary.effect.totalToMoveKr!!, 1e-9)
        assertEquals(1, summary.effect.steps.size)
        assertEquals(0.8, summary.effect.steps.single()!!.from.afterFraction, 1e-9)
    }
}
