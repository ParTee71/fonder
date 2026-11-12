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
import se.partee71.fonder.domain.usecase.PortfolioRiskSource
import se.partee71.fonder.domain.usecase.SwitchPlanResolver
import se.partee71.fonder.domain.usecase.SwitchRow
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.components.FundFacts
import se.partee71.fonder.ui.components.PULL_TO_REFRESH_TEST_TAG
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.common.ListUiState

/**
 * Fliken Beslut (NAV-9, issue #240) i JVM med Robolectric: riskkortet med "Ändra mål", råden (BYT-4)
 * med effekt och beslut, infokortet (BYT-5), tomt läge, vägen till Facit och skärmdumpar ljust + mörkt. Förslagens och bevakningarnas egna interaktioner testas
 * instrumenterat i `androidTest/…/ui/beslut/BeslutScreenTest` (flyttade från Hem).
 */
@RunWith(RobolectricTestRunner::class)
class BeslutScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val balanced = RiskProfile(
        targetAllocation = mapOf(2 to 0.20, 3 to 0.35, 4 to 0.45),
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
     * Mockupens läge (C2, BYT-4): ett sålt planbyte (planrad 1 ersatt av sin bevakning), ett föreslaget,
     * ett kvitterat i Facit, ett billigare alternativ, och i den hopfällda sektionen ett köpt, ett
     * utgånget och ett överhoppat byte; sju följda i facit med deras resultat i kronor. Riskkortet
     * räknas som i appen ([PortfolioRiskSource.summary]) på mockupens fördelning.
     */
    private val full: BeslutUiState = run {
        val state = BeslutUiState(
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
        val base = PortfolioRiskSource.RiskBase(
            riskProfile = balanced,
            valueByLevelKr = mapOf(2 to 8_000.0, 3 to 26_000.0, 4 to 52_000.0, 5 to 14_000.0),
            pendingProceedsByLevel = emptyMap()
        )
        state.copy(riskSummary = PortfolioRiskSource.summary(base, state.decisions.switchPlan))
    }

    /** Allt överhoppat (HEM-14), ett byte i planen och ett billigare alternativ: inget att besluta, men raden för att ångra finns kvar. */
    private val withDismissed = full.withDecisions(
        PendingDecisions(
            dismissedSuggestions = listOf(planRow2),
            dismissedCheaperAlternatives = listOf(cheaper)
        ).withSwitches(emptyList(), today)
    )

    private val planInfo = SuggestionInfo(
        row = SwitchRow.Suggested(planRow2),
        proposed = FundFacts(
            name = "Räntefond Kort",
            risk = 2,
            totalFeePercent = 0.20,
            oneYearFraction = 0.039,
            type = "Räntefond, Korta räntor",
            company = "Nordiska Fonder AB"
        ),
        alternatives = listOf(
            FundFacts(name = "Kort Ränta Index", risk = 2, totalFeePercent = 0.10, oneYearFraction = 0.034, type = "Räntefond"),
            FundFacts(name = "Obligationsfond Sverige", risk = 2, totalFeePercent = 0.45, oneYearFraction = 0.041, type = "Räntefond"),
            FundFacts(name = "Nordisk Företagsobligation", risk = 2, totalFeePercent = 0.60, oneYearFraction = -0.008, type = "Räntefond")
        )
    )

    @Test
    fun `riskkortet visar laget och malet och Andra mal oppnar riskprofilen`() {
        var opened = 0
        composeRule.setContent { FonderTheme { BeslutContent(state = full, onOpenRiskProfile = { opened++ }) } }

        composeRule.onNodeWithText("Du har mer risk än ditt mål").assertIsDisplayed()
        composeRule.onNodeWithText("Ditt mål är Balanserad tillväxt.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Mot riskprofil", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Ändra mål").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `utan profil visas Satt ett mal med vad som kravs`() {
        var opened = 0
        composeRule.setContent { FonderTheme { BeslutContent(state = BeslutUiState(list = ListUiState.Empty), onOpenRiskProfile = { opened++ }) } }

        composeRule.onNodeWithText("Sätt ett mål").assertIsDisplayed()
        composeRule.onNodeWithText("Byten föreslås när du satt ett mål och kontotypen ISK/KF.").assertIsDisplayed()
        composeRule.onNodeWithText("Ändra mål").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `med profil men utan riskkort finns Andra mal anda`() {
        // Kortets underlag läses ännu, eller målet saknar fördelning — vägen till Riskprofil ska finnas.
        var opened = 0
        composeRule.setContent { FonderTheme { BeslutContent(state = full.copy(riskSummary = null), onOpenRiskProfile = { opened++ }) } }

        composeRule.onNodeWithText("Ditt mål är Balanserad tillväxt.").assertIsDisplayed()
        composeRule.onNodeWithText("Sätt ett mål").assertDoesNotExist()
        composeRule.onNodeWithText("Ändra mål").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `utan ISK eller KF sags gaten ut och ingen omrakningsknapp finns`() {
        composeRule.setContent { FonderTheme { BeslutContent(state = full.copy(accountType = AccountType.DEPA_AF)) } }

        composeRule.onNodeWithText("Byten föreslås när du satt ett mål och kontotypen ISK/KF.").assertIsDisplayed()
        composeRule.onNodeWithText("Räkna om bytesplanen").assertDoesNotExist()
    }

    @Test
    fun `med gaten finns ingen omrakningsknapp utan dra for att uppdatera`() {
        composeRule.setContent { FonderTheme { BeslutContent(state = full) } }

        composeRule.onNodeWithText("Räkna om bytesplanen").assertDoesNotExist()
        composeRule.onNodeWithText("Byten föreslås", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag(PULL_TO_REFRESH_TEST_TAG).assertExists()
    }

    @Test
    fun `tomt lage visar tomt tillstand men behaller vagen till facit`() {
        var facit = 0
        composeRule.setContent {
            FonderTheme { BeslutContent(state = BeslutUiState(list = ListUiState.Empty, followedCount = 1), onOpenFacit = { facit++ }) }
        }

        composeRule.onNodeWithText("Inga byten att besluta om").assertIsDisplayed()
        composeRule.onNodeWithText("Så kommer du närmare målet").assertDoesNotExist()
        composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG).performScrollToNode(hasText("Facit · 1 följt förslag"))
        composeRule.onNodeWithText("Facit · 1 följt förslag").performClick()

        assertEquals(1, facit)
    }

    @Test
    fun `lasfel visar bara felet utan riskkort och facitrad`() {
        composeRule.setContent {
            FonderTheme {
                BeslutContent(state = BeslutUiState(list = ListUiState.Error(AppErrors.map(DataError.Database)), followedCount = 1))
            }
        }

        composeRule.onNodeWithText("Kunde inte visa innehållet").assertIsDisplayed()
        composeRule.onNodeWithText("Sätt ett mål").assertDoesNotExist()
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
    fun `raden visar rad som konsekvens med chip effekt och avgift`() {
        composeRule.setContent { FonderTheme { BeslutContent(state = full) } }

        val list = composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG)
        list.performScrollToNode(hasText("Så kommer du närmare målet"))
        // Föreslaget (1) + Avgift (1) + Sålt (1); det kvitterade väntar inte.
        composeRule.onNodeWithText("3 väntar på dig").assertIsDisplayed()
        // Varje rad är en nod (chip, rubrik, effekt) — exakt textmatchning per del.
        listOf(
            "Sålt · dag 2 av 30 · välj köpfond",
            "Tillväxtmarknad Bred → ?",
            "${MoneyFormat.krWhole(18_400.0)} väntar.",
            "Sänker risken · steg 2",
            "Sverige Småbolag → Räntefond Kort, ${MoneyFormat.krWhole(12_500.0)}",
            // Steg 2 räknat från mockupens fördelning: nivå 5 har 14 000 av 100 000 kr, bytet flyttar 12 500 kr.
            // En decimal, som riskkortets staplar.
            "Nivå 5 ${MoneyFormat.percent(0.14)} → ${MoneyFormat.percent(0.015)}, nivå 2 ${MoneyFormat.percent(0.08)} → ${MoneyFormat.percent(0.205)}. Dessutom 1,05 % lägre avgift per år.",
            "Sparar avgift · påverkar inte risken",
            "Sverige Småbolag → Småbolag Index",
            "Samma sorts fond, 1,40 % → 0,40 % i avgift. Ca ${MoneyFormat.krWhole(960.0)}/år.",
            "Avslutade och överhoppade (3)",
            "Så har råden gått"
        ).forEach { text ->
            list.performScrollToNode(hasText(text))
            composeRule.onAllNodesWithText(text).assertCountEquals(1)
        }
        // Inga av de gamla orden (acceptanskriterierna i #240).
        listOf("likviden", "Avgiftsskillnad", "Bytesplan · byte", "Registrera säljet", "Avstå", "Hur gick det?").forEach { old ->
            composeRule.onAllNodesWithText(old, substring = true).assertCountEquals(0)
        }
        composeRule.onNodeWithText("Inga byten att besluta om").assertDoesNotExist()
    }

    @Test
    fun `forbehallet sager att appen aldrig byter och hur ofta raden stammer`() {
        composeRule.setContent { FonderTheme { BeslutContent(state = full) } }

        val text = "Appen byter aldrig åt dig – du gör bytet hos din bank. Råden stämmer ungefär 3 gånger av 4."
        composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG).performScrollToNode(hasText(text))
        composeRule.onNodeWithText(text).assertExists()
    }

    @Test
    fun `ett steg som bygger pa ett tidigare sager det`() {
        val second = planRow2.copy(recordId = 15, planIndex = 2, sellFundName = "Energi Norden", buyFundName = "Räntefond Bred", followed = false)
        val state = full.withDecisions(PendingDecisions(switchPlan = listOf(planRow2, second)).withSwitches(emptyList(), today))
        composeRule.setContent { FonderTheme { BeslutContent(state = state) } }

        composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG).performScrollToNode(hasText("Gör steg 2 först.", substring = true))
        composeRule.onAllNodesWithText("Gör steg", substring = true).assertCountEquals(1)
    }

    @Test
    fun `salt visar ledaren med tecken och Valj kopfond oppnar bevakningen`() {
        var opened: Long? = null
        composeRule.setContent { FonderTheme { BeslutContent(state = full, onOpenSwitchWatch = { opened = it }) } }

        composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG).performScrollToNode(hasContentDescription("Välj köpfond: Tillväxtmarknad Bred"))
        composeRule.onNodeWithText("Bäst hittills: Global Indexfond A ${MoneyFormat.percentSigned(0.018)}", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Välj köpfond: Tillväxtmarknad Bred").performClick()

        assertEquals(3L, opened)
    }

    @Test
    fun `jag har salt och hoppa over visas pa ett oppet forslag och anropar sina callbacks`() {
        // HEM-14/BYT-2: "Jag har sålt" öppnar säljformuläret för förslaget, "Hoppa över" döljer paret.
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
        list.performScrollToNode(hasContentDescription("Hoppa över: Sverige Småbolag → Räntefond Kort"))
        composeRule.onNodeWithContentDescription("Hoppa över: Sverige Småbolag → Räntefond Kort").performClick()
        composeRule.onNodeWithContentDescription("Registrera sälj: Sverige Småbolag → Räntefond Kort").performClick()
        // Det sålda bytet erbjuds inte att säljas igen (BYT-1).
        composeRule.onAllNodesWithContentDescription("Registrera sälj: Tillväxtmarknad Bred → Global Indexfond A").assertCountEquals(0)

        assertEquals(12L, dismissed?.recordId)
        assertEquals(12L, followed?.recordId)
    }

    @Test
    fun `ett kvitterat forslag erbjuder Bevaka alternativ i stallet for Jag har salt`() {
        // FollowedToggleRow finns bara i Facit (SET-5) sedan Följ går via formuläret (BYT-2).
        var watched: SwitchPlanResolver.Suggestion? = null
        composeRule.setContent { FonderTheme { BeslutContent(state = full, onStartSwitchWatch = { watched = it }) } }

        composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG).performScrollToNode(hasText("Bevaka alternativ"))
        composeRule.onAllNodesWithText("Genomförd").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Registrera sälj: Energi Norden → Räntefond Bred").assertCountEquals(0)
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
        list.performScrollToNode(hasContentDescription("Hoppa över: Sverige Småbolag → Småbolag Index"))
        composeRule.onNodeWithContentDescription("Hoppa över: Sverige Småbolag → Småbolag Index").performClick()
        composeRule.onNodeWithContentDescription("Registrera sälj: Sverige Småbolag → Småbolag Index").performClick()

        assertEquals(SuggestionKind.FEE, dismissed?.kind)
        assertEquals(21L, followed?.recordId)
    }

    @Test
    fun `utropstecknet oppnar infokortet for radet`() {
        var shown: SwitchRow.Suggested? = null
        composeRule.setContent { FonderTheme { BeslutContent(state = full, onShowInfo = { shown = it }) } }

        val list = composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG)
        list.performScrollToNode(hasContentDescription("Mer om fonderna: Sverige Småbolag → Räntefond Kort"))
        composeRule.onNodeWithContentDescription("Mer om fonderna: Sverige Småbolag → Räntefond Kort").performClick()
        assertEquals(12L, shown?.suggestion?.recordId)

        list.performScrollToNode(hasContentDescription("Mer om fonderna: Sverige Småbolag → Småbolag Index"))
        composeRule.onNodeWithContentDescription("Mer om fonderna: Sverige Småbolag → Småbolag Index").performClick()
        assertEquals(21L, shown?.suggestion?.recordId)
    }

    @Test
    fun `infokortet visar fondens fakta och tre alternativ och Jag har salt stanger och foljer`() {
        var followed: SwitchPlanResolver.Suggestion? = null
        var hidden = 0
        composeRule.setContent {
            FonderTheme { BeslutContent(state = full.copy(info = planInfo), onFollow = { followed = it }, onHideInfo = { hidden++ }) }
        }

        composeRule.onNodeWithText("Vårt förslag").assertExists()
        composeRule.onNodeWithText("Nordiska Fonder AB").assertExists()
        composeRule.onNodeWithText("Räntefond, Korta räntor").assertExists()
        composeRule.onNodeWithText("Andra fonder på risknivå 2").assertExists()
        listOf("Kort Ränta Index", "Obligationsfond Sverige", "Nordisk Företagsobligation").forEach {
            composeRule.onNodeWithText(it, substring = true).assertExists()
        }
        composeRule.onNodeWithText("Jag har sålt Sverige Småbolag").performClick()

        assertEquals(12L, followed?.recordId)
        assertEquals(1, hidden)
    }

    @Test
    fun `infokortet utan alternativ sager det och saknade fakta star som okant`() {
        var hidden = 0
        val bare = planInfo.copy(proposed = FundFacts(name = "Räntefond Kort", risk = 2), alternatives = emptyList())
        composeRule.setContent { FonderTheme { BeslutContent(state = full.copy(info = bare), onHideInfo = { hidden++ }) } }

        composeRule.onNodeWithText("Inga andra köpbara fonder hittades på nivå 2.").assertExists()
        composeRule.onAllNodesWithText("okänt", substring = true).assertCountEquals(4)
        composeRule.onNodeWithText("Stäng").performClick()

        assertEquals(1, hidden)
    }

    @Test
    fun `infokortet for ett billigare alternativ visar de andra billigare`() {
        val info = SuggestionInfo(
            row = SwitchRow.Suggested(cheaper.suggestion, cheaper = cheaper),
            proposed = FundFacts(name = "Småbolag Index", risk = 5, totalFeePercent = 0.40),
            alternatives = emptyList()
        )
        composeRule.setContent { FonderTheme { BeslutContent(state = full.copy(info = info)) } }

        composeRule.onNodeWithText("Andra billigare alternativ").assertExists()
        composeRule.onNodeWithText("Inga andra billigare alternativ hittades.").assertExists()
        composeRule.onNodeWithText("Jag har sålt Sverige Småbolag").assertExists()
    }

    @Test
    fun `avslutade och overhoppade ligger hopfallda och overhoppade kan angras`() {
        var undone: SwitchPlanResolver.Suggestion? = null
        composeRule.setContent {
            FonderTheme { BeslutContent(state = withDismissed, onUndoDismissal = { undone = it }) }
        }

        composeRule.onNodeWithText("Inga byten att besluta om").assertExists()
        val list = composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG)
        list.performScrollToNode(hasText("Avslutade och överhoppade (2)"))
        composeRule.onNodeWithText("Avslutade och överhoppade (2)").performClick()
        list.performScrollToNode(hasText("Sverige Småbolag → Småbolag Index"))
        composeRule.onAllNodesWithText("Överhoppat").assertCountEquals(2)
        composeRule.onNodeWithContentDescription("Ångra: visa Sverige Småbolag → Räntefond Kort igen").performClick()
        assertEquals(12L, undone?.recordId)

        composeRule.onNodeWithContentDescription("Ångra: visa Sverige Småbolag → Småbolag Index igen").performClick()
        assertEquals(SuggestionKind.FEE, undone?.kind)
        assertEquals(21L, undone?.recordId)
    }

    @Test
    fun `avslutade byten visar kopt med datum och utganget utan angra`() {
        composeRule.setContent { FonderTheme { BeslutContent(state = full) } }

        val list = composeRule.onNodeWithTag(BESLUT_LIST_TEST_TAG)
        list.performScrollToNode(hasText("Avslutade och överhoppade (3)"))
        composeRule.onNodeWithText("Avslutade och överhoppade (3)").performClick()
        list.performScrollToNode(hasText("Global Stars → inget köp registrerat"))
        composeRule.onNodeWithText("Köpt 7 okt").assertExists()
        composeRule.onNodeWithText("Aktiefond Global → Global Indexnära").assertExists()
        composeRule.onNodeWithText("Utgånget").assertExists()
        // Bara det överhoppade kan ångras — ett avslutat byte står i Facit.
        composeRule.onAllNodesWithText("Ångra").assertCountEquals(1)
    }

    @Test
    fun `utan avslutade eller overhoppade visas ingen hopfalld sektion`() {
        val noClosed = full.withDecisions(full.decisions.copy(dismissedSuggestions = emptyList()).withSwitches(emptyList(), today))
        composeRule.setContent { FonderTheme { BeslutContent(state = noClosed) } }

        composeRule.onNodeWithText("Avslutade och överhoppade", substring = true).assertDoesNotExist()
    }

    // Lång skärm, så att hela listan – även de billigare alternativen och den hopfällda sektionen – kommer med i bilden.
    @Config(qualifiers = "w390dp-h2200dp-xxhdpi")
    @Test
    fun `skärmdump med byten och facit`() = composeRule.captureLightAndDark("BeslutScreen_full") {
        BeslutContent(state = full)
    }

    @Config(qualifiers = "w390dp-h1200dp-xxhdpi")
    @Test
    fun `skärmdump med ett överhoppat förslag`() = composeRule.captureLightAndDark("BeslutScreen_dismissed") {
        BeslutContent(state = withDismissed)
    }

    @Test
    fun `skärmdump utan något att besluta`() = composeRule.captureLightAndDark("BeslutScreen_empty") {
        BeslutContent(state = BeslutUiState(list = ListUiState.Empty))
    }

    // Infokortet ritas i ett eget fönster (bottenarket) — hela skärmen fotograferas.
    @Config(qualifiers = "w390dp-h1400dp-xxhdpi")
    @Test
    fun `skärmdump med infokortet`() = composeRule.captureLightAndDark("BeslutScreen_info", screen = true) {
        BeslutContent(state = full.copy(info = planInfo))
    }
}
