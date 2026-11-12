package se.partee71.fonder.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * [copyableFundName] (UI-16, issue #245) i JVM med Robolectric: långtryck kopierar bara namnet och
 * öppnar inte den klickbara raden runt det, ett vanligt tryck öppnar raden utan att kopiera, och
 * TalkBack-åtgärden "Kopiera fondnamn" kopierar.
 */
@RunWith(RobolectricTestRunner::class)
class CopyableFundNameTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val clipboard: ClipboardManager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)

    private var clicks = 0

    @Before
    fun clearClipboard() {
        clipboard.clearPrimaryClip()
    }

    private fun copied(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    private fun setNameInClickableRow() = composeRule.setContent {
        FonderTheme {
            Box(Modifier.clickable { clicks++ }) {
                Column {
                    Text("Länsförsäkringar Global Index", Modifier.copyableFundName("Länsförsäkringar Global Index"))
                    Text("Risk 4 av 7 · 0,20 %")
                }
            }
        }
    }

    @Test
    fun `langtryck kopierar namnet och oppnar inte raden`() {
        setNameInClickableRow()

        composeRule.onNodeWithText("Länsförsäkringar Global Index").performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals("Länsförsäkringar Global Index", copied())
        assertEquals(0, clicks)
    }

    @Test
    fun `vanligt tryck oppnar raden utan att kopiera`() {
        setNameInClickableRow()

        composeRule.onNodeWithText("Länsförsäkringar Global Index").performClick()
        composeRule.waitForIdle()

        assertEquals(1, clicks)
        assertNull(copied())
    }

    @Test
    fun `en rorelse ar ingen kopiering`() {
        setNameInClickableRow()

        composeRule.onNodeWithText("Länsförsäkringar Global Index").performTouchInput { swipeUp(durationMillis = 1_000) }
        composeRule.waitForIdle()

        assertNull(copied())
    }

    @Test
    fun `talkback-atgarden kopierar namnet`() {
        setNameInClickableRow()

        val node = composeRule.onNodeWithText("Länsförsäkringar Global Index").fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].single { it.label == "Kopiera fondnamn" }
        composeRule.runOnUiThread { action.action() }
        composeRule.waitForIdle()

        assertEquals("Länsförsäkringar Global Index", copied())
        assertEquals(0, clicks)
    }

    @Test
    fun `langtryck pa ett billigare alternativ i Fonddetalj faller inte ut raden`() {
        var expanded = 0
        composeRule.setContent {
            FonderTheme {
                ExpandableInfoRow(explanation = "Sparar 240 kr per år.", onExpand = { expanded++ }) {
                    PeriodRow(label = "Indexfond Global", amount = 240.0, fraction = null, modifier = Modifier.copyableFundName("Indexfond Global"))
                }
            }
        }

        composeRule.onNodeWithText("Indexfond Global", useUnmergedTree = true).performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals("Indexfond Global", copied())
        assertEquals(0, expanded)
        composeRule.onNodeWithText("Sparar 240 kr per år.").assertDoesNotExist()
    }

    @Test
    fun `alternativraden i infokortet kopierar bara namnet`() {
        composeRule.setContent {
            FonderTheme {
                FundFactsRow(FundFacts(name = "Spiltan Räntefond Sverige", risk = 2, totalFeePercent = 0.25, oneYearFraction = 0.031, type = "Räntefond"))
            }
        }

        composeRule.onNodeWithText("Spiltan Räntefond Sverige", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals("Spiltan Räntefond Sverige", copied())
    }
}
