package se.partee71.fonder.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Den delade [ReminderRow] (HEM-12) i JVM med Robolectric: rubrik, undertext och åtgärd läses som
 * **en** klickbar nod (UI-3), tryckytan är minst 48 dp, och ett tryck var som helst på raden
 * utför åtgärden.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var clicks = 0

    private fun setRow() = composeRule.setContent {
        FonderTheme {
            ReminderRow(
                title = "Ändringar sedan senaste säkerhetskopian",
                body = "3 nya transaktioner · senast 2026-10-03",
                actionLabel = "Säkerhetskopiera",
                onAction = { clicks++ }
            )
        }
    }

    @Test
    fun `rubrik, undertext och atgard ar en sammanhallen klickbar nod`() {
        setRow()

        val node = composeRule.onNodeWithText("Ändringar sedan senaste säkerhetskopian")
        node.assertHasClickAction()
        node.assertHeightIsAtLeast(48.dp)
        val texts = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        assertEquals(
            listOf("Ändringar sedan senaste säkerhetskopian", "3 nya transaktioner · senast 2026-10-03", "Säkerhetskopiera"),
            texts
        )
        assertEquals("Säkerhetskopiera", node.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
    }

    @Test
    fun `ett tryck pa undertexten utfor atgarden`() {
        setRow()

        composeRule.onNodeWithText("3 nya transaktioner · senast 2026-10-03").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `skärmdump ljust och mörkt`() {
        composeRule.captureLightAndDark("ReminderRow_backup") {
            ReminderRow(
                title = "Ändringar sedan senaste säkerhetskopian",
                body = "3 nya transaktioner · senast 2026-10-03",
                actionLabel = "Säkerhetskopiera",
                onAction = {}
            )
        }
    }

    // --- Varianten "Att besluta" (HEM-13): kopparton, antal och pil ---

    @Test
    fun `att besluta-varianten ar en klickbar nod med pil i stallet for etikett`() {
        composeRule.setContent {
            FonderTheme {
                ReminderRow(
                    title = "Att besluta",
                    body = "2 bytesförslag · 1 pågående byte",
                    actionLabel = "Visa beslut",
                    onAction = { clicks++ },
                    count = 3,
                    tone = ReminderRowTone.Accent,
                    chevron = true
                )
            }
        }

        val node = composeRule.onNodeWithText("Att besluta")
        node.assertHasClickAction()
        node.assertHeightIsAtLeast(48.dp)
        // Märket är dekorativt (antalet står i undertexten) och etiketten blir klickåtgärdens namn.
        val texts = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        assertEquals(listOf("Att besluta", "2 bytesförslag · 1 pågående byte"), texts)
        assertEquals("Visa beslut", node.fetchSemanticsNode().config[SemanticsActions.OnClick].label)

        node.performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `skärmdump att besluta ljust och mörkt`() {
        composeRule.captureLightAndDark("ReminderRow_decisions") {
            ReminderRow(
                title = "Att besluta",
                body = "2 bytesförslag · 1 pågående byte",
                actionLabel = "Visa beslut",
                onAction = {},
                count = 3,
                tone = ReminderRowTone.Accent,
                chevron = true
            )
        }
    }
}
