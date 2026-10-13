package se.partee71.fonder.ui.navigation

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import se.partee71.fonder.MainActivity
import se.partee71.fonder.data.repository.SwitchWatchRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.testing.NetworkGuard
import se.partee71.fonder.ui.portfolj.PORTFOLJ_LIST_TEST_TAG
import java.time.LocalDate
import javax.inject.Inject

/**
 * Navigeringen på enhet med appens riktiga Hilt-graf (NAV-7, ANA-12): `MainActivity` startas med
 * en fond i Innehav och en pågående bevakning i Beslut, med minnesbaserad lagring
 * (`InMemoryStorageModule`) och utan nätverk (`OfflineNetworkModule` – varje anrop besvaras som
 * ett nätverksfel, och testet fälls om en riktig anslutning ändå görs). Bevisar att skärmarna med
 * navigationsargument får dem via assisted injection – Fonddetalj sin fond (`FondDetaljViewModel`)
 * och Pågående byte sin bevakning (`SwitchWatchViewModel`) – och att bakåt leder tillbaka till
 * fliken man kom från.
 */
@HiltAndroidTest
class AppNavigationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var transactions: TransactionRepository

    @Inject
    lateinit var switchWatches: SwitchWatchRepository

    @Before
    fun setUp() {
        NetworkGuard.reset()
        // HiltTestApplication är ingen Configuration.Provider – appens WorkManager-statusflöde
        // (NAV-6) behöver en initierad WorkManager.
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        hiltRule.inject()
        val today = LocalDate.now().toEpochDay()
        runBlocking {
            transactions.upsertFund(Fund(fundId = FUND_ID, name = FUND_NAME))
            transactions.addTransaction(
                Transaction(
                    fundId = FUND_ID,
                    type = TransactionType.KOP,
                    epochDay = today - 30,
                    shares = 10.0,
                    pricePerShare = 100.0,
                ),
            )
            switchWatches.start(
                SwitchWatch(sellIsin = SELL_ISIN, sellFundName = SOLD_FUND_NAME, soldAtEpochDay = today),
            )
        }
    }

    @Test
    fun innehav_oppnar_fonddetalj_och_bakat_leder_tillbaka() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(hasContentDescription("Innehav"))
            composeRule.onNodeWithContentDescription("Innehav").performClick()
            // Fondraden ligger i en LazyColumn under korten och komponeras inte förrän listan
            // skrollats dit (issue #66) – skrolla listan till raden i stället för att vänta på den.
            waitFor(hasTestTag(PORTFOLJ_LIST_TEST_TAG))
            composeRule.onNodeWithTag(PORTFOLJ_LIST_TEST_TAG).performScrollToNode(hasText(FUND_NAME))
            composeRule.onAllNodesWithText(FUND_NAME).onFirst().performClick()

            // Fonddetalj med topprad och bakåtpil. När övergången är klar är Innehav borta, så
            // fondens namn kommer från FondDetaljViewModel – som fick fundId ur nyckeln.
            waitFor(hasContentDescription("Tillbaka"))
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Fond").assertExists()
            waitFor(hasText(FUND_NAME))
            composeRule.onNodeWithContentDescription("Tillbaka").performClick()

            waitFor(hasContentDescription("Innehav"))
            composeRule.onNodeWithContentDescription("Innehav").assertIsSelected()
            composeRule.onNodeWithContentDescription("Tillbaka").assertDoesNotExist()
        }
    }

    @Test
    fun beslut_oppnar_pagaende_byte_och_bakat_leder_tillbaka() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(beslutTab)
            composeRule.onNode(beslutTab).performClick()
            waitFor(hasText(SOLD_FUND_NAME, substring = true))
            composeRule.onNodeWithText("Öppna bevakningen").performScrollTo().performClick()

            // Pågående byte. När övergången är klar är Beslut borta, så säljfondens namn kommer från
            // SwitchWatchViewModel – som fick bevakningens id ur nyckeln.
            waitFor(hasContentDescription("Tillbaka"))
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Pågående byte").assertExists()
            waitFor(hasText(SOLD_FUND_NAME, substring = true))
            composeRule.onNodeWithContentDescription("Tillbaka").performClick()

            waitFor(hasText("Öppna bevakningen"))
            composeRule.onNodeWithContentDescription("Tillbaka").assertDoesNotExist()
        }
    }

    /** Flikens namn, med eller utan kopparmärkets antal (NAV-9). */
    private val beslutTab = hasContentDescription("Beslut") or hasContentDescription("Beslut, 1 nya")

    @After
    fun ingetRiktigtNatverk() {
        assertEquals("riktiga nätverksanrop", 0, NetworkGuard.realConnectionAttempts.get())
    }

    private fun waitFor(matcher: SemanticsMatcher) =
        composeRule.waitUntil(TIMEOUT_MS) { composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private companion object {
        const val FUND_ID = "TEST0000001"
        const val FUND_NAME = "Navigeringsfonden Test"
        const val SELL_ISIN = "SE0000000001"
        const val SOLD_FUND_NAME = "Såld testfond"
        const val TIMEOUT_MS = 10_000L
    }
}
