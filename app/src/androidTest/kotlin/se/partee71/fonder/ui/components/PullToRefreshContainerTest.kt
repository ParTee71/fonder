package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.ui.theme.FonderTheme

/** Instrumenterat test av den delade dra-ned-behållaren (UI-11). */
@RunWith(AndroidJUnit4::class)
class PullToRefreshContainerTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Innehållet måste vara skrollbart — gesten kopplar sig via nested scroll, se komponentens KDoc. */
    private fun setContent(refreshing: Boolean, onRefresh: () -> Unit) {
        composeRule.setContent {
            FonderTheme {
                PullToRefreshContainer(refreshing = refreshing, onRefresh = onRefresh) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(20) { index -> Text("Rad $index") }
                    }
                }
            }
        }
    }

    @Test
    fun innehallet_visas_genom_behallaren() {
        setContent(refreshing = false) {}

        composeRule.onNodeWithText("Rad 0").assertIsDisplayed()
    }

    @Test
    fun en_dragning_nedat_begar_en_uppdatering() {
        var refreshes = 0
        setContent(refreshing = false) { refreshes++ }

        composeRule.onNodeWithTag(PULL_TO_REFRESH_TEST_TAG).performTouchInput { swipeDown() }

        // waitUntil, inte en direkt assertion: gesten släpper vid tröskeln och callbacken kommer
        // först när utdragningen animerat klart. Att läsa av direkt efter svepet hade gett ett
        // test som ibland faller — och ett flaky test är en bugg, inte en omkörning.
        composeRule.waitUntil(timeoutMillis = 5_000) { refreshes == 1 }
        assertEquals(1, refreshes)
    }

    @Test
    fun en_pagaende_uppdatering_begar_ingen_till() {
        // Snurran syns redan; ett nytt svep ska inte köa en andra körning ovanpå den pågående.
        var refreshes = 0
        setContent(refreshing = true) { refreshes++ }

        composeRule.onNodeWithTag(PULL_TO_REFRESH_TEST_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertEquals(0, refreshes)
    }
}
