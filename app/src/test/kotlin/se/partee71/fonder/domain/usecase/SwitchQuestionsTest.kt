package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.TransactionType
import java.time.LocalDate

/**
 * Bytesväljarens frågor i formuläret och PDF-importen (BYT-2, issue #144): Följ och "Köpte den
 * här" förväljer sitt förslag respektive sin bevakning, en bevakning från en annan dag frågas
 * alltid om, och i importen kan ett köp avsluta bevakningen som en säljrad före den startar.
 */
class SwitchQuestionsTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 12)
    private val day = today.toEpochDay()

    private fun row(
        recordId: Long,
        planIndex: Int = 0,
        sellIsin: String = "SE_SALJ",
        buyIsin: String = "SE_KOP",
        toLevel: Int = 3,
        inProgress: Boolean = false,
        kind: SuggestionKind = SuggestionKind.RISK_PLAN,
    ) = SwitchPlanResolver.Suggestion(
        recordId = recordId, planIndex = planIndex, sellIsin = sellIsin, buyIsin = buyIsin, sellFundName = sellIsin, buyFundName = buyIsin,
        fromLevel = 5, toLevel = toLevel, feeDeltaPercent = 0.0, switchValueKr = 1_000.0, inProgress = inProgress, kind = kind,
    )

    private fun watch(id: Long, soldAt: Long = day - 2, candidates: List<String> = listOf("SE_KOP"), sellTransactionId: Long? = 5) = SwitchWatch(
        id = id, sellIsin = "SE_SALJ", sellFundName = "Säljfond", soldAtEpochDay = soldAt, targetLevel = 3,
        sellTransactionId = sellTransactionId, candidates = candidates.map { SwitchWatchCandidate(isin = it, name = it) },
    )

    private fun inputs(
        plan: List<SwitchPlanResolver.Suggestion> = emptyList(),
        watches: List<SwitchWatch> = emptyList(),
        cheaper: List<SwitchPlanResolver.Suggestion> = emptyList(),
    ) = SwitchChoicesSource.Inputs(plan, watches, emptyMap(), today, cheaper)

    private fun decide(decision: SwitchDecision) = SwitchOption.Decide(decision)

    /** Följ på förslaget [recordId], slaget upp i Besluts källa som formuläret gör. */
    private fun follow(inputs: SwitchChoicesSource.Inputs, fundIsin: String, recordId: Long, lookedUp: FollowedSuggestion? = null) =
        SwitchQuestions.forTransaction(inputs, TransactionType.SALJ, fundIsin, day, followed = SwitchQuestions.followed(inputs, recordId, lookedUp))

    @Test
    fun `folj pa en rad som inte star i Besluts kalla anvander raden uppslagen pa id`() {
        val lookedUp = FollowedSuggestion(
            recordId = 5, sellIsin = "SE_SALJ", buyIsin = "SE_BILLIG", sellFundName = "Sälj", buyFundName = "Billig",
            toLevel = 4, switchValueKr = null, planIndex = null,
        )

        val question = follow(inputs(), "SE_SALJ", 5, lookedUp)

        assertEquals(decide(SwitchDecision.ForSuggestion(5, 4)), question.preselected)
        assertEquals(decide(SwitchDecision.None), follow(inputs(), "SE_SALJ", 6, lookedUp).preselected)
    }

    // --- Formuläret ---

    @Test
    fun `utan nagot att koppla till ar fragan trivial och inget byte forvalt`() {
        val question = SwitchQuestions.forTransaction(inputs(), TransactionType.KOP, "SE_KOP", day)

        assertTrue(question.isTrivial)
        assertFalse(question.required)
        assertEquals(decide(SwitchDecision.None), question.preselected)
    }

    @Test
    fun `folj forvaljer forslaget aven nar fonden finns i flera planrader`() {
        val plan = listOf(row(1, planIndex = 0), row(2, planIndex = 1, buyIsin = "SE_ANNAN", toLevel = 2))

        val question = follow(inputs(plan), "SE_SALJ", 2)

        assertEquals(decide(SwitchDecision.ForSuggestion(2, 2)), question.preselected)
        assertFalse(question.required)
    }

    @Test
    fun `folj pa ett billigare alternativ erbjuder och forvaljer det`() {
        val cheaper = row(9, sellIsin = "SE_SALJ", buyIsin = "SE_BILLIG", toLevel = 5, kind = SuggestionKind.FEE)

        val question = follow(inputs(cheaper = listOf(cheaper)), "SE_SALJ", 9)

        assertEquals(decide(SwitchDecision.ForSuggestion(9, 5)), question.preselected)
        assertTrue(decide(SwitchDecision.ForSuggestion(9, 5)) in question.options)
        assertTrue(decide(SwitchDecision.Standalone) in question.options)
    }

    @Test
    fun `folj pa en pagaende rad eller ett annat fonds forslag ger planens vanliga forval`() {
        val plan = listOf(row(1, inProgress = true))

        val pagaende = follow(inputs(plan), "SE_SALJ", 1)
        val annanFond = follow(inputs(listOf(row(1))), "SE_ANNAN", 1)

        assertEquals(decide(SwitchDecision.None), pagaende.preselected)
        assertTrue(pagaende.options.none { it is SwitchOption.Decide && it.decision is SwitchDecision.ForSuggestion })
        assertEquals(decide(SwitchDecision.None), annanFond.preselected)
    }

    @Test
    fun `kopte den har forvaljer bevakningen aven for en fond som inte ar kandidat`() {
        val watches = listOf(watch(7, candidates = listOf("SE_KANDIDAT")))

        val utan = SwitchQuestions.forTransaction(inputs(watches = watches), TransactionType.KOP, "SE_ANNAN", day)
        val med = SwitchQuestions.forTransaction(inputs(watches = watches), TransactionType.KOP, "SE_ANNAN", day, closesWatchId = 7)

        assertTrue(utan.required)
        assertEquals(decide(SwitchDecision.ClosesWatch(7)), med.preselected)
    }

    @Test
    fun `kopte den har for en bevakning som inte kan avslutas ger inget forval for den`() {
        // Köpet före säljdagen erbjuds inte som avslut — då heller inte från "Köpte den här".
        val watches = listOf(watch(7, soldAt = day + 1))

        val question = SwitchQuestions.forTransaction(inputs(watches = watches), TransactionType.KOP, "SE_KOP", day, closesWatchId = 7)

        assertEquals(listOf<SwitchOption>(decide(SwitchDecision.None)), question.options)
        assertEquals(decide(SwitchDecision.None), question.preselected)
    }

    @Test
    fun `en okopplad bevakning fran en annan dag blir ett alternativ och fragan kravs`() {
        val watches = listOf(watch(4, soldAt = day - 3, sellTransactionId = null))

        val question = SwitchQuestions.forTransaction(inputs(watches = watches), TransactionType.SALJ, "SE_SALJ", day)

        assertTrue(SwitchOption.LinkOtherDay(4) in question.options)
        assertTrue(question.required)
        assertNull(question.resolve(null))
        // Skrivs som ett bytesval i samma anrop som säljet — inte som "Inget byte" plus ett anrop efteråt.
        assertEquals(SwitchDecision.LinksWatch(4), SwitchOption.LinkOtherDay(4).decision)
    }

    @Test
    fun `i importen kopplas en bevakning av hogst en saljrad - nasta rad far nasta bevakning`() {
        val watches = listOf(watch(4, soldAt = day - 3, sellTransactionId = null), watch(5, soldAt = day - 1, sellTransactionId = null))
        val rows = listOf(importRow(TransactionType.SALJ, "SE_SALJ"), importRow(TransactionType.SALJ, "SE_SALJ"), importRow(TransactionType.SALJ, "SE_SALJ"))

        val questions = SwitchQuestions.forImport(
            inputs(watches = watches),
            rows,
            listOf(SwitchOption.LinkOtherDay(4), SwitchOption.LinkOtherDay(5), null),
        )

        assertTrue(SwitchOption.LinkOtherDay(4) in questions[0].options)
        assertFalse(SwitchOption.LinkOtherDay(4) in questions[1].options)
        assertTrue(SwitchOption.LinkOtherDay(5) in questions[1].options)
        // Båda tagna: tredje raden har ingen bevakning att koppla och frågan är inte längre obligatorisk.
        assertTrue(questions[2].options.none { it is SwitchOption.LinkOtherDay })
        assertFalse(questions[2].required)
    }

    @Test
    fun `ett val som inte langre finns bland alternativen faller tillbaka pa forvalet`() {
        val question = SwitchQuestions.forTransaction(inputs(), TransactionType.SALJ, "SE_SALJ", day)

        assertEquals(decide(SwitchDecision.Standalone), question.resolve(decide(SwitchDecision.Standalone)))
        assertEquals(decide(SwitchDecision.None), question.resolve(decide(SwitchDecision.ClosesWatch(3))))
    }

    // --- Importen ---

    private fun importRow(type: TransactionType, isin: String?, epochDay: Long = day) = SwitchImportRow(type, isin, epochDay)

    @Test
    fun `kop av forslagets kopfond efter saljraden for planraden forvaljer bytet som saljraden startar`() {
        val plan = listOf(row(1, buyIsin = "SE_KOP"))
        val rows = listOf(importRow(TransactionType.SALJ, "SE_SALJ", day - 1), importRow(TransactionType.KOP, "SE_KOP"))

        val questions = SwitchQuestions.forImport(inputs(plan), rows, listOf(null, null))

        assertEquals(decide(SwitchDecision.ForSuggestion(1, 3)), questions[0].preselected)
        assertEquals(decide(SwitchDecision.ClosesWatchOfEntry(0)), questions[1].preselected)
    }

    @Test
    fun `kop av en annan fond efter en fristaende saljrad fragar`() {
        val rows = listOf(importRow(TransactionType.SALJ, "SE_SALJ"), importRow(TransactionType.KOP, "SE_ANNAN"))

        val questions = SwitchQuestions.forImport(inputs(), rows, listOf(decide(SwitchDecision.Standalone), null))

        assertTrue(decide(SwitchDecision.ClosesWatchOfEntry(0)) in questions[1].options)
        assertTrue(questions[1].required)
    }

    @Test
    fun `saljraden med inget byte eller ett salj efter kopdagen ger inget alternativ`() {
        val rows = listOf(
            importRow(TransactionType.SALJ, "SE_SALJ"),
            importRow(TransactionType.SALJ, "SE_ANDRA", day + 2),
            importRow(TransactionType.KOP, "SE_KOP"),
        )

        val questions = SwitchQuestions.forImport(
            inputs(),
            rows,
            listOf(decide(SwitchDecision.None), decide(SwitchDecision.Standalone), null),
        )

        assertTrue(questions[2].isTrivial)
        assertEquals(decide(SwitchDecision.None), questions[2].preselected)
    }

    @Test
    fun `en befintlig bevakning och en ny fran importen ger inget forval`() {
        val plan = listOf(row(1, buyIsin = "SE_KOP"))
        val rows = listOf(importRow(TransactionType.SALJ, "SE_SALJ"), importRow(TransactionType.KOP, "SE_KOP"))

        val questions = SwitchQuestions.forImport(inputs(plan, watches = listOf(watch(7))), rows, listOf(null, null))

        assertEquals(
            listOf(decide(SwitchDecision.None), decide(SwitchDecision.ClosesWatch(7)), decide(SwitchDecision.ClosesWatchOfEntry(0))),
            questions[1].options,
        )
        assertTrue(questions[1].required)
    }
}
