package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.testing.testPreferencesRepository

/**
 * Bytesväljarens förval (BYT-2, issue #144): få, deterministiska regler — allt annat är en fråga
 * till användaren. Inga beloppsheuristiker.
 */
class SwitchTransitionsTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 12)
    private val day = today.toEpochDay()

    private fun row(recordId: Long, planIndex: Int, sellIsin: String = "SE_SALJ", buyIsin: String = "SE_KOP", toLevel: Int = 3, inProgress: Boolean = false) = SwitchPlanResolver.Suggestion(
        recordId = recordId, planIndex = planIndex, sellIsin = sellIsin, buyIsin = buyIsin, sellFundName = sellIsin, buyFundName = buyIsin,
        fromLevel = 5, toLevel = toLevel, feeDeltaPercent = 0.0, switchValueKr = 1_000.0, inProgress = inProgress
    )

    private fun watch(
        id: Long,
        soldAt: Long = day - 2,
        candidates: List<String> = listOf("SE_KOP"),
        sourceRecordId: Long? = null,
        sellTransactionId: Long? = 5,
        closed: Boolean = false,
        sellIsin: String = "SE_SALJ"
    ) = SwitchWatch(
        id = id, sellIsin = sellIsin, sellFundName = sellIsin, soldAtEpochDay = soldAt, targetLevel = 3, sourceRecordId = sourceRecordId,
        sellTransactionId = sellTransactionId, candidates = candidates.map { SwitchWatchCandidate(isin = it, name = it) },
        closedAtEpochDay = if (closed) day else null, closeReason = if (closed) SwitchWatchCloseReason.AVBRUTEN else null
    )

    private fun sale(isin: String? = "SE_SALJ", plan: List<SwitchPlanResolver.Suggestion> = emptyList(), watches: List<SwitchWatch> = emptyList()) = SwitchTransitions.suggestFor(TransactionType.SALJ, isin, day, plan, watches, today)

    private fun purchase(
        isin: String? = "SE_KOP",
        epochDay: Long = day,
        watches: List<SwitchWatch> = emptyList(),
        buyIsinByRecordId: Map<Long, String> = emptyMap()
    ) = SwitchTransitions.suggestFor(TransactionType.KOP, isin, epochDay, emptyList(), watches, today, buyIsinByRecordId)

    // --- Sälj ---

    @Test
    fun `salj utan planrad forvaljer inget byte och erbjuder fristaende`() {
        val choices = sale(plan = listOf(row(1, 0, sellIsin = "SE_ANNAN")))

        assertEquals(listOf(SwitchDecision.None, SwitchDecision.Standalone), choices.options)
        assertEquals(SwitchDecision.None, choices.preselected)
        assertFalse(choices.required)
        assertFalse(choices.isTrivial)
    }

    @Test
    fun `salj av saljkandidaten i en oppen planrad forvaljer raden`() {
        val choices = sale(plan = listOf(row(1, 0, sellIsin = "SE_ANNAN"), row(2, 1, toLevel = 4)))

        assertEquals(SwitchDecision.ForSuggestion(recordId = 2, toLevel = 4), choices.preselected)
        assertEquals(listOf(SwitchDecision.None, SwitchDecision.Standalone, SwitchDecision.ForSuggestion(2, 4)), choices.options)
    }

    @Test
    fun `tva planrader for samma saljfond ger ingen gissning - fragan stalls i planordning`() {
        val choices = sale(plan = listOf(row(2, 1, toLevel = 2), row(1, 0, toLevel = 4)))

        assertNull(choices.preselected)
        assertTrue(choices.required)
        assertEquals(listOf(SwitchDecision.ForSuggestion(1, 4), SwitchDecision.ForSuggestion(2, 2)), choices.options.drop(2))
    }

    @Test
    fun `en pagaende planrad har redan sitt salj och erbjuds inte`() {
        val choices = sale(plan = listOf(row(1, 0, inProgress = true)))

        assertEquals(listOf(SwitchDecision.None, SwitchDecision.Standalone), choices.options)
        assertEquals(SwitchDecision.None, choices.preselected)
    }

    @Test
    fun `salj av en fond utan ISIN kan inte bevakas - bara inget byte`() {
        val choices = sale(isin = null, plan = listOf(row(1, 0)))

        assertEquals(listOf(SwitchDecision.None), choices.options)
        assertTrue(choices.isTrivial)
        assertFalse(choices.required)
    }

    // --- Köp ---

    @Test
    fun `kop utan oppen bevakning har inget att koppla till`() {
        val choices = purchase(watches = listOf(watch(1, closed = true)))

        assertEquals(listOf(SwitchDecision.None), choices.options)
        assertEquals(SwitchDecision.None, choices.preselected)
        assertTrue(choices.isTrivial)
    }

    @Test
    fun `kop av en kandidat i den enda oppna bevakningen forvaljer avslut`() {
        val choices = purchase(watches = listOf(watch(1)))

        assertEquals(SwitchDecision.ClosesWatch(1), choices.preselected)
        assertEquals(listOf(SwitchDecision.None, SwitchDecision.ClosesWatch(1)), choices.options)
    }

    @Test
    fun `kop av den foreslagna fonden forvaljer avslut aven nar den inte ar kandidat`() {
        val choices = purchase(
            isin = "SE_FORESLAGEN",
            watches = listOf(watch(1, candidates = listOf("SE_KOP"), sourceRecordId = 7)),
            buyIsinByRecordId = mapOf(7L to "SE_FORESLAGEN")
        )

        assertEquals(SwitchDecision.ClosesWatch(1), choices.preselected)
    }

    @Test
    fun `kop av en annan fond medan en bevakning ar oppen fragar alltid - inget forval ingen beloppsheuristik`() {
        val choices = purchase(isin = "SE_ANNAN", watches = listOf(watch(1)))

        assertNull(choices.preselected)
        assertTrue(choices.required)
        assertEquals(listOf(SwitchDecision.None, SwitchDecision.ClosesWatch(1)), choices.options)
    }

    @Test
    fun `kop av en kandidat med tva oppna bevakningar fragar - aldst forst i listan`() {
        val choices = purchase(watches = listOf(watch(2, soldAt = day - 1), watch(1, soldAt = day - 3)))

        assertNull(choices.preselected)
        assertEquals(listOf(SwitchDecision.None, SwitchDecision.ClosesWatch(1), SwitchDecision.ClosesWatch(2)), choices.options)
    }

    @Test
    fun `kop fore saljdagen eller mot en utgangen bevakning erbjuds inte`() {
        val expired = watch(1, soldAt = day - SwitchWatchCalc.WATCH_TTL_DAYS - 1)
        val later = watch(2, soldAt = day + 1)

        val choices = purchase(watches = listOf(expired, later))

        assertEquals(listOf(SwitchDecision.None), choices.options)
        assertEquals(SwitchDecision.None, choices.preselected)
        // Samma dag som säljet går — pengarna fanns den dagen.
        assertEquals(SwitchDecision.ClosesWatch(3), purchase(watches = listOf(watch(3, soldAt = day))).preselected)
    }

    @Test
    fun `kop av en fond utan ISIN kan inte kopplas`() {
        val choices = purchase(isin = null, watches = listOf(watch(1)))

        assertEquals(listOf(SwitchDecision.None), choices.options)
    }

    // --- Koppling till en bevakning från en annan dag: bara efter fråga ---

    @Test
    fun `linkableWatch ger en okopplad bevakning startad samma dag - saljet sparat som inget byte kopplar annars aldrig`() {
        val sameDay = watch(1, soldAt = day, sellTransactionId = null, sourceRecordId = 7)

        assertEquals(1L, SwitchTransitions.linkableWatch("SE_SALJ", day, listOf(sameDay), today)?.id)
    }

    @Test
    fun `linkableWatch pekar ut den aldsta okopplade bevakningen inom fonstret`() {
        val watches = listOf(
            watch(1, soldAt = day, sellTransactionId = null), // samma dag — erbjuds, men äldre går före
            watch(2, soldAt = day - 3, sellTransactionId = 9), // redan kopplad
            watch(3, soldAt = day - 2, sellTransactionId = null, sellIsin = "SE_ANNAN"),
            watch(4, soldAt = day - SwitchWatchCalc.WATCH_TTL_DAYS - 1, sellTransactionId = null), // utgången
            watch(5, soldAt = day - 1, sellTransactionId = null),
            watch(6, soldAt = day - 4, sellTransactionId = null),
            watch(7, soldAt = day - 2, sellTransactionId = null, closed = true)
        )

        assertEquals(6L, SwitchTransitions.linkableWatch("SE_SALJ", day, watches, today)?.id)
        assertNull(SwitchTransitions.linkableWatch("SE_SALJ", day, listOf(watches[1], watches[3], watches[6]), today))
        assertNull(SwitchTransitions.linkableWatch(null, day, watches, today))
    }

    @Test
    fun `linkableWatch galler bade fore och efter saljdagen inom WATCH_TTL_DAYS`() {
        val before = watch(1, soldAt = day - SwitchWatchCalc.WATCH_TTL_DAYS, sellTransactionId = null)
        val after = watch(2, soldAt = day + SwitchWatchCalc.WATCH_TTL_DAYS, sellTransactionId = null)
        val tooLate = watch(3, soldAt = day + SwitchWatchCalc.WATCH_TTL_DAYS + 1, sellTransactionId = null)

        assertEquals(1L, SwitchTransitions.linkableWatch("SE_SALJ", day, listOf(before, tooLate), today)?.id)
        assertEquals(2L, SwitchTransitions.linkableWatch("SE_SALJ", day, listOf(after, tooLate), today)?.id)
        assertNull(SwitchTransitions.linkableWatch("SE_SALJ", day, listOf(tooLate), today))
    }

    @Test
    fun `en koppling som gjorde bevakningen utgangen pa saljdagen erbjuds inte`() {
        // Bevakningen är färsk, men säljet är äldre än WATCH_TTL_DAYS — flyttad dit hade nästa
        // bakgrundskörning stängt den som utgången innan köpet kunnat avsluta bytet.
        val fresh = watch(1, soldAt = day - 2, sellTransactionId = null)
        val saleDay = day - SwitchWatchCalc.WATCH_TTL_DAYS - 1

        assertNull(SwitchTransitions.linkableWatch("SE_SALJ", saleDay, listOf(fresh), today))
        assertEquals(1L, SwitchTransitions.linkableWatch("SE_SALJ", day - SwitchWatchCalc.WATCH_TTL_DAYS, listOf(fresh), today)?.id)
    }

    @Test
    fun `linkableWatches ger alla i ordning och Inputs kan utesluta dem en tidigare rad redan kopplar`() {
        val watches = listOf(watch(2, soldAt = day - 1, sellTransactionId = null), watch(1, soldAt = day - 3, sellTransactionId = null))
        val inputs = SwitchChoicesSource.Inputs(emptyList(), watches, emptyMap(), today)

        assertEquals(listOf(1L, 2L), SwitchTransitions.linkableWatches("SE_SALJ", day, watches, today).map { it.id })
        assertEquals(1L, inputs.linkableWatch("SE_SALJ", day)?.id)
        assertEquals(2L, inputs.linkableWatch("SE_SALJ", day, exclude = setOf(1L))?.id)
        assertNull(inputs.linkableWatch("SE_SALJ", day, exclude = setOf(1L, 2L)))
    }

    // --- Källan: samma plan som Beslut, öppna bevakningar och källförslagens köpfond ---

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `kallan ger formularet samma plan som Beslut och koppfonden ur bevakningens kallforslag`() = runTest(dispatcher) {
        val records = FakeSuggestionRecordRepository()
        val watches = FakeSwitchWatchRepository()
        val metadata = FakeFundMetadataRepository(
            metadataByIsin = listOf("SE_SALJ" to 5, "SE_KOP" to 3, "SE_SALJ2" to 5, "SE_KOP2" to 3).associate { (isin, risk) ->
                isin to FundMetadata(
                    isin = isin, name = isin, orderbookId = isin, totalFee = 0.5, managementFee = 0.5, category = null, fundType = null,
                    companyName = null, risk = risk, indexFund = false, startDateEpochDay = null, minimumBuy = null, tags = emptyList()
                )
            }
        )
        val preferences = testPreferencesRepository(dispatcher, tempFolder, "choices_${System.nanoTime()}")
        preferences.setAccountType(AccountType.ISK_KF)
        records.records.value = listOf(
            SuggestionRecord(id = 1, suggestedAtEpochDay = LocalDate.now().toEpochDay(), planIndex = 0, sellIsin = "SE_SALJ", buyIsin = "SE_KOP", sellNavAtSuggestion = 100.0, buyNavAtSuggestion = 50.0, batchEpochMillis = 1_000),
            SuggestionRecord(id = 2, suggestedAtEpochDay = LocalDate.now().toEpochDay(), planIndex = 1, sellIsin = "SE_SALJ2", buyIsin = "SE_KOP2", sellNavAtSuggestion = 100.0, buyNavAtSuggestion = 50.0, batchEpochMillis = 1_000)
        )
        // Rad 2 är såld: bevakning ur den, utan kandidater — köpfonden kommer ur raden.
        val watchId = watches.start(SwitchWatch(sellIsin = "SE_SALJ2", sellFundName = "SE_SALJ2", soldAtEpochDay = LocalDate.now().toEpochDay(), sourceRecordId = 2, sellTransactionId = 9))
        val source = SwitchChoicesSource(
            PendingDecisionsSource(
                preferences,
                records,
                watches,
                FakeCachedPriceRepository(),
                metadata,
                DismissedPlanSource(records, FakeSuggestionDismissalRepository(), preferences, metadata),
                InProgressSwitches(watches, records)
            ),
            watches,
            records
        )

        val inputs = source.observe { LocalDate.now() }.first { it.plan.size == 2 && it.openWatches.size == 1 }

        assertEquals(listOf(false, true), inputs.plan.map { it.inProgress })
        assertEquals(mapOf(2L to "SE_KOP2"), inputs.buyIsinByRecordId)
        assertEquals(SwitchDecision.ForSuggestion(1, 3), inputs.suggestFor(TransactionType.SALJ, "SE_SALJ", LocalDate.now().toEpochDay()).preselected)
        assertEquals(SwitchDecision.None, inputs.suggestFor(TransactionType.SALJ, "SE_SALJ2", LocalDate.now().toEpochDay()).preselected)
        assertEquals(SwitchDecision.ClosesWatch(watchId), inputs.suggestFor(TransactionType.KOP, "SE_KOP2", LocalDate.now().toEpochDay()).preselected)
        assertNull(inputs.linkableWatch("SE_SALJ2", LocalDate.now().toEpochDay()))
    }
}
