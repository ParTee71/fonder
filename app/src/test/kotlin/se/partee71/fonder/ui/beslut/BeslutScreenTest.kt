package se.partee71.fonder.ui.beslut

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.AnnotatedString
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.partee71.fonder.data.common.AppErrors
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.RiskProfileAnswers
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.model.TimeHorizon
import se.partee71.fonder.domain.usecase.CheaperAlternative
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.OpenSwitchWatch
import se.partee71.fonder.domain.usecase.PendingDecisions
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.common.ListUiState

/**
 * Fliken Beslut (NAV-9) i JVM med Robolectric: rubrikraden mot riskprofilen, listan Byten (BYT-4)
 * med tillstånd och beslut, tomt läge, vägen till Facit och skärmdumpar ljust + mörkt. Förslagens och bevakningarnas egna interaktioner testas
 * instrumenterat i `androidTest/…/ui/beslut/BeslutScreenTest` (flyttade från Hem).
 */
@RunWith(RobolectricTestRunner::class)
class BeslutScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val balanced = RiskProfile(
        targetAllocation = mapOf(4 to 1.0),
        answers = RiskProfileAnswers(TimeHorizon.SJU_TILL_15_AR, DownturnReaction.GOR_INGET, PrimaryGoal.BALANSERAD)
    )

    /** Det billigare alternativet ur mockupen (ANA-9): 1,40 % → 0,40 % på 96 000 kr sparar 960 kr per år. */
    private val cheaper = CheaperAlternative(
        suggestion = SwitchPlanResolver.Suggestion(
            recordId = 21, planIndex = 0, sellIsin = "SE_SB", buyIsin = "SE_SBI",
            sellFundName = "Sverige Småbolag", buyFundName = "Småbolag Index",
            fromLevel = 5, toLevel = 5, feeDeltaPercent = -1.0, switchValueKr = 96_000.0, kind = SuggestionKind.FEE
        ),
        sellFeePercent = 1.40,
        buyFeePercent = 0.40
    )

    private val today = LocalDate.of(2026, 10, 13)

    private val planRow1 = SwitchPlanResolver.Suggestion(
        recordId = 11, planIndex = 0, sellIsin = "SE_TM", buyIsin = "SE_GI",
        sellFundName = "Tillväxtmarknad Bred", buyFundName = "Global Indexfond A",
        fromLevel = 6, toLevel = 4, feeDeltaPercent = -0.9, switchValueKr = 300.0, inProgress = true
    )
    private val planRow2 = SwitchPlanResolver.Suggestion(
        recordId = 12, planIndex = 1, sellIsin = "SE_SB", buyIsin = "SE_RF",
        sellFundName = "Sverige Småbolag", buyFundName = "Räntefond Kort",
        fromLevel = 5, toLevel = 2, feeDeltaPercent = -1.05, switchValueKr = 12_500.0
    )
    private val planRow3 = SwitchPlanResolver.Suggestion(
        recordId = 13, planIndex = 2, sellIsin = "SE_EN", buyIsin = "SE_RB",
        sellFundName = "Energi Norden", buyFundName = "Räntefond Bred",
        fromLevel = 6, toLevel = 3, feeDeltaPercent = -0.4, switchValueKr = 4_000.0, followed = true
    )

    /** Ett köpt och ett utgånget byte, stängda inom 14 dagar (BYT-4). */
    private val closedWatches = listOf(
        SwitchWatch(
            id = 5,
            sellIsin = "SE_AMF",
            sellFundName = "Aktiefond Global",
            soldAtEpochDay = today.toEpochDay() - 9,
            closedAtEpochDay = LocalDate.of(2026, 10, 7).toEpochDay(),
            closeReason = SwitchWatchCloseReason.KOPT,
            boughtIsin = "SE_GIX",
            candidates = listOf(SwitchWatchCandidate(isin = "SE_GIX", name = "Global Indexnära"))
        ),
        SwitchWatch(
            id = 6,
            sellIsin = "SE_NGS",
            sellFundName = "Global Stars",
            soldAtEpochDay = today.toEpochDay() - 20,
            closedAtEpochDay = today.toEpochDay() - 5,
            closeReason = SwitchWatchCloseReason.UTGANGEN
        )
    )

    /**
     * Mockupens läge (BYT-4): ett sålt planbyte (planrad 1 ersatt av sin bevakning), ett föreslaget,
     * ett kvitterat i Facit, ett billigare alternativ, och i den hopfällda sektionen ett köpt, ett
     * utgånget och ett avstått byte; sju följda i facit med deras resultat i kronor.
     */
    private val full = BeslutUiState(
        riskProfile = balanced,
        accountType = AccountType.ISK_KF,
        followedCount = 7,
        followedExcessKr = 4_210.0
    ).withDecisions(
        PendingDecisions(
            openWatches = listOf(
                OpenSwitchWatch(
                    watchId = 3, sellFundName = "Tillväxtmarknad Bred", daysWaiting = 2, candidateCount = 3,
                    leaderName = "Global Indexfond A", leaderChangeFraction = 0.018, proceedsKr = 18_400.0,
                    sourceRecordId = 11, sourceKind = SuggestionKind.RISK_PLAN
                )
            ),
            switchPlan = listOf(planRow1, planRow2, planRow3),
            cheaperAlternatives = listOf(cheaper),
            dismissedSuggestions = listOf(planRow2.copy(recordId = 14, sellFundName = "Aktiefond Investmentbolag", buyFundName = "Zero"))
        ).withSwitches(closedWatches, today)
    )

    /** Allt avstått (HEM-14), ett byte i planen och ett billigare alternativ: inget att besluta, men raden för att ångra finns kvar. */
    private val withDismissed = full.withDecisions(
        PendingDecisions(
            dismissedSuggestions = listOf(planRow2),
            dismissedCheaperAlternatives = listOf(cheaper)
        ).withSwitches(emptyList(), today)
    )

    @Test
    fun `rubrikraden visar riskprofil mal och kontotyp och Andra oppnar riskprofilen`() {
        var opened = 0
        composeRule.setContent { FonderTheme { BeslutContent(state = full, onOpenRiskProfile = { opened++ }) } }

        composeRule.onNodeWithText("Mot riskprofil 4,00 · Balanserad tillväxt · ISK/KF").assertIsDisplayed()
        composeRule.onNodeWithText("Ändra").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `utan profil sags det ut i stallet for en gissad niva`() {
        composeRule.setContent { FonderTheme { BeslutContent(state = BeslutUiState(list = ListUiState.Empty)) } }

        composeRule.onNodeWithText("Ingen riskprofil satt").assertIsDisplayed()
    }

    @Test
    fun `tomt lage visar tomt tillstand men behaller vagen till facit`() {
        var facit = 0
        composeRule.setContent {
            FonderTheme { BeslutContent(state = BeslutUiState(list = ListUiState.Empty, followedCount = 1), onOpenFacit = { facit++ }) }
        }

        composeRule.onNodeWithText("Inga byten att besluta om").assertIsDisplayed()
        composeRule.onNodeWithText("Byten").assertDoesNotExist()
        composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG).performScrollToNode(hasText("Facit · 1 följt förslag"))
        composeRule.onNodeWithText("Facit · 1 följt förslag").performClick()

        assertEquals(1, facit)
    }

    @Test
    fun `lasfel visar bara felet utan profilrad och facitrad`() {
        composeRule.setContent {
            FonderTheme {
                BeslutContent(state = BeslutUiState(list = ListUiState.Error(AppErrors.map(DataError.Database)), followedCount = 1))
            }
        }

        composeRule.onNodeWithText("Kunde inte visa innehållet").assertIsDisplayed()
        composeRule.onNodeWithText("Ingen riskprofil satt").assertDoesNotExist()
        composeRule.onNodeWithText("Facit · 1 följt förslag").assertDoesNotExist()
    }

    @Test
    fun `facitraden visar de foljda radernas resultat i kronor med tecken`() {
        composeRule.setContent { FonderTheme { BeslutContent(state = full.copy(followedExcessKr = -1_250.0)) } }

        composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG).performScrollToNode(hasText("Facit · 7 följda förslag"))
        // Beloppet sitter i samma klickbara kort som antalet, och tecknet bär förlusten även utan
        // färg (ReturnColors får aldrig stå ensam).
        composeRule.onNode(hasText("Facit · 7 följda förslag"))
            .assert(hasText(MoneyFormat.krSigned(-1_250.0)))
            .assertIsDisplayed()
    }

    @Test
    fun `facitraden visar bara antalet utan matbart resultat`() {
        composeRule.setContent { FonderTheme { BeslutContent(state = full.copy(followedExcessKr = null)) } }

        composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG).performScrollToNode(hasText("Facit · 7 följda förslag"))
        // Kortet bär bara antalet — inget belopp, och särskilt inte "0 kr" (ANA-4-principen).
        composeRule.onNode(hasText("Facit · 7 följda förslag"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString("Facit · 7 följda förslag"))))
    }

    @Test
    fun `listan Byten visar ett byte per rad med tillstandschip i mockupens ordning`() {
        composeRule.setContent { FonderTheme { BeslutContent(state = full) } }

        composeRule.onNodeWithText("Byten").assertIsDisplayed()
        // Föreslaget (2) + Avgift (1) + Sålt (1); det kvitterade väntar inte.
        composeRule.onNodeWithText("3 väntar på dig").assertIsDisplayed()
        // Det sålda planbytet ersätter planrad 1 — ingen "1. Sälj Tillväxtmarknad Bred …" finns kvar.
        composeRule.onAllNodesWithText("1. Sälj", substring = true).assertCountEquals(0)
        val list = composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG)
        // Varje rad är en nod (chip, ursprung, rubrik, underlag) — exakt textmatchning per del.
        listOf(
            "Sålt · dag 2 av 14",
            "Bytesplan · byte 1",
            "Tillväxtmarknad Bred → välj köpfond",
            "${MoneyFormat.kr(18_400.0)} väntar på likviden · 3 bevakade alternativ",
            "Bytesplan · byte 2",
            "2. Sälj Sverige Småbolag (nivå 5) → Köp Räntefond Kort (nivå 2)",
            "Sälj Sverige Småbolag → Köp Småbolag Index",
            "Sparar ca ${MoneyFormat.kr(960.0)}/år · avgift 1,40 % → 0,40 %",
            "Avslutade och avstådda (3)",
            "Hur gick det?"
        ).forEach { text ->
            list.performScrollToNode(hasText(text))
            composeRule.onAllNodesWithText(text).assertCountEquals(1)
        }
        // Chipen för förslagen: planens "Föreslaget" och det billigare alternativets "Avgift".
        list.performScrollToNode(hasText("2. Sälj Sverige Småbolag (nivå 5) → Köp Räntefond Kort (nivå 2)"))
        composeRule.onNode(hasText("2. Sälj Sverige Småbolag (nivå 5) → Köp Räntefond Kort (nivå 2)")).assert(hasText("Föreslaget"))
        list.performScrollToNode(hasText("Sälj Sverige Småbolag → Köp Småbolag Index"))
        composeRule.onNode(hasText("Sälj Sverige Småbolag → Köp Småbolag Index")).assert(hasText("Avgift"))
        composeRule.onNodeWithText("Inga byten att besluta om").assertDoesNotExist()
    }

    @Test
    fun `salt visar ledaren med tecken och oppnar bevakningen`() {
        var opened: Long? = null
        composeRule.setContent { FonderTheme { BeslutContent(state = full, onOpenSwitchWatch = { opened = it }) } }

        composeRule.onNodeWithText("Bäst sedan säljdagen: Global Indexfond A ${MoneyFormat.percentSigned(0.018)}", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Öppna bevakningen: Tillväxtmarknad Bred").performClick()

        assertEquals(3L, opened)
    }

    @Test
    fun `registrera saljet och avsta visas pa ett oppet forslag och anropar sina callbacks`() {
        // HEM-14/BYT-2: "Registrera säljet" öppnar säljformuläret för förslaget, "Avstå" döljer paret.
        var followed: SwitchPlanResolver.Suggestion? = null
        var dismissed: SwitchPlanResolver.Suggestion? = null
        composeRule.setContent {
            FonderTheme {
                BeslutContent(
                    state = full,
                    onFollow = { followed = it },
                    onDismissSuggestion = { dismissed = it }
                )
            }
        }

        val list = composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG)
        list.performScrollToNode(hasContentDescription("Avstå: sälj Sverige Småbolag, köp Räntefond Kort"))
        composeRule.onNodeWithContentDescription("Avstå: sälj Sverige Småbolag, köp Räntefond Kort").performClick()
        composeRule.onNodeWithContentDescription("Registrera säljet: sälj Sverige Småbolag, köp Räntefond Kort").performClick()
        // Det sålda bytet erbjuds inte att säljas igen (BYT-1).
        composeRule.onAllNodesWithContentDescription("Registrera säljet: sälj Tillväxtmarknad Bred, köp Global Indexfond A").assertCountEquals(0)

        assertEquals(12L, dismissed?.recordId)
        assertEquals(12L, followed?.recordId)
    }

    @Test
    fun `ett kvitterat forslag erbjuder Bevaka alternativ i stallet for Registrera saljet`() {
        // FollowedToggleRow finns bara i Facit (SET-5) sedan Följ går via formuläret (BYT-2).
        var watched: SwitchPlanResolver.Suggestion? = null
        composeRule.setContent { FonderTheme { BeslutContent(state = full, onStartSwitchWatch = { watched = it }) } }

        composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG).performScrollToNode(hasText("Bevaka alternativ"))
        composeRule.onAllNodesWithText("Genomförd").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Registrera säljet: sälj Energi Norden, köp Räntefond Bred").assertCountEquals(0)
        composeRule.onNodeWithText("Bevaka alternativ").performClick()

        assertEquals(13L, watched?.recordId)
    }

    @Test
    fun `ett billigare alternativ har samma tva beslut som planen`() {
        var followed: SwitchPlanResolver.Suggestion? = null
        var dismissed: SwitchPlanResolver.Suggestion? = null
        composeRule.setContent {
            FonderTheme { BeslutContent(state = full, onFollow = { followed = it }, onDismissSuggestion = { dismissed = it }) }
        }

        val list = composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG)
        list.performScrollToNode(hasContentDescription("Avstå: sälj Sverige Småbolag, köp Småbolag Index"))
        composeRule.onNodeWithContentDescription("Avstå: sälj Sverige Småbolag, köp Småbolag Index").performClick()
        composeRule.onNodeWithContentDescription("Registrera säljet: sälj Sverige Småbolag, köp Småbolag Index").performClick()

        assertEquals(SuggestionKind.FEE, dismissed?.kind)
        assertEquals(21L, followed?.recordId)
    }

    @Test
    fun `avslutade och avstadda ligger hopfallda och avstadda kan angras`() {
        var undone: SwitchPlanResolver.Suggestion? = null
        composeRule.setContent {
            FonderTheme { BeslutContent(state = withDismissed, onUndoDismissal = { undone = it }) }
        }

        composeRule.onNodeWithText("Inga byten att besluta om").assertIsDisplayed()
        val list = composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG)
        list.performScrollToNode(hasText("Avslutade och avstådda (2)"))
        composeRule.onNodeWithText("Avslutade och avstådda (2)").performClick()
        list.performScrollToNode(hasText("Sälj Sverige Småbolag → Köp Småbolag Index"))
        composeRule.onAllNodesWithText("Avstått").assertCountEquals(2)
        composeRule.onNodeWithContentDescription("Ångra avstående: sälj Sverige Småbolag, köp Räntefond Kort").performClick()
        assertEquals(12L, undone?.recordId)

        composeRule.onNodeWithContentDescription("Ångra avstående: sälj Sverige Småbolag, köp Småbolag Index").performClick()
        assertEquals(SuggestionKind.FEE, undone?.kind)
        assertEquals(21L, undone?.recordId)
    }

    @Test
    fun `avslutade byten visar kopt med datum och utganget utan angra`() {
        composeRule.setContent { FonderTheme { BeslutContent(state = full) } }

        val list = composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG)
        list.performScrollToNode(hasText("Avslutade och avstådda (3)"))
        composeRule.onNodeWithText("Avslutade och avstådda (3)").performClick()
        list.performScrollToNode(hasText("Global Stars → inget köp registrerat"))
        composeRule.onNodeWithText("Köpt 7 okt").assertExists()
        composeRule.onNodeWithText("Aktiefond Global → Global Indexnära").assertExists()
        composeRule.onNodeWithText("Utgånget").assertExists()
        // Bara det avstådda kan ångras — ett avslutat byte står i Facit.
        composeRule.onAllNodesWithText("Ångra").assertCountEquals(1)
    }

    @Test
    fun `utan avslutade eller avstadda visas ingen hopfalld sektion`() {
        val noClosed = full.withDecisions(full.decisions.copy(dismissedSuggestions = emptyList()).withSwitches(emptyList(), today))
        composeRule.setContent { FonderTheme { BeslutContent(state = noClosed) } }

        composeRule.onNodeWithText("Avslutade och avstådda", substring = true).assertDoesNotExist()
    }

    // Lång skärm, så att hela listan – även de billigare alternativen och den hopfällda sektionen – kommer med i bilden.
    @Config(qualifiers = "w390dp-h1600dp-xxhdpi")
    @Test
    fun `skärmdump med byten och facit`() = composeRule.captureLightAndDark("BeslutScreen_full") {
        BeslutContent(state = full)
    }

    @Test
    fun `skärmdump med ett avstått förslag`() = composeRule.captureLightAndDark("BeslutScreen_dismissed") {
        BeslutContent(state = withDismissed)
    }

    @Test
    fun `skärmdump utan något att besluta`() = composeRule.captureLightAndDark("BeslutScreen_empty") {
        BeslutContent(state = BeslutUiState(list = ListUiState.Empty, riskProfile = balanced, accountType = AccountType.ISK_KF))
    }
}
