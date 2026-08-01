package se.partee71.fonder.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Isolerat Compose-test av den delade proportionella exponeringsraden (POR-9, issue #66,
 * regel 4) — Portfölj kan lita på att etikett/procent-renderingen redan är verifierad.
 */
@RunWith(AndroidJUnit4::class)
class ExposureBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visar_etikett_och_procent() {
        composeRule.setContent {
            FonderTheme { ExposureBar(label = "Sverige", fraction = 0.417) }
        }

        composeRule.onNodeWithText("Sverige").assertExists()
        composeRule.onNodeWithText("41,7 %").assertExists()
    }

    @Test
    fun renderar_noll_procent_utan_krasch() {
        composeRule.setContent {
            FonderTheme { ExposureBar(label = "Okänd region", fraction = 0.0) }
        }

        composeRule.onNodeWithText("Okänd region").assertExists()
        composeRule.onNodeWithText("0,0 %").assertExists()
    }

    @Test
    fun renderar_hundra_procent_utan_krasch() {
        composeRule.setContent {
            FonderTheme { ExposureBar(label = "Aktiefond", fraction = 1.0) }
        }

        composeRule.onNodeWithText("Aktiefond").assertExists()
        composeRule.onNodeWithText("100,0 %").assertExists()
    }
}
