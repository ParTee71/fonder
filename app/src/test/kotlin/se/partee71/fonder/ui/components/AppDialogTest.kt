package se.partee71.fonder.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Den delade [ConfirmDialog]/[AppDialog] och [LoadingDialog] (UI-13) i JVM: bekräfta och avbryt går
 * till var sin callback, och dialogerna fotograferas i ljust och mörkt (den destruktiva med
 * bekräftelseknappen i felfärgen).
 */
@RunWith(RobolectricTestRunner::class)
class AppDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun setConfirmDialog() = composeRule.setContent {
        FonderTheme {
            ConfirmDialog(
                title = "Ta bort transaktionen?",
                body = "Köp av Räntefond Kort 12 aug, 10 000 kr. Det går inte att ångra.",
                confirmLabel = "Ta bort",
                onConfirm = { events += "bekräfta" },
                onDismiss = { events += "avbryt" },
                destructive = true
            )
        }
    }

    @Test
    fun `bekräfta anropar onConfirm`() {
        setConfirmDialog()

        composeRule.onNodeWithText("Ta bort transaktionen?").assertIsDisplayed()
        composeRule.onNodeWithText("Ta bort").performClick()

        assertEquals(listOf("bekräfta"), events)
    }

    @Test
    fun `avbryt anropar onDismiss`() {
        setConfirmDialog()

        composeRule.onNodeWithText("Avbryt").performClick()

        assertEquals(listOf("avbryt"), events)
    }

    @Test
    fun `väntedialogen visar rubrik och text`() {
        composeRule.setContent {
            FonderTheme { LoadingDialog(title = "Importerar", body = "Matchar fonder och hämtar kurser …") }
        }

        composeRule.onNode(isDialog()).assertExists()
        composeRule.onNodeWithText("Matchar fonder och hämtar kurser …").assertExists()
    }

    @Test
    fun `skärmdump av destruktiv bekräftelse`() = composeRule.captureLightAndDark("ConfirmDialog_destructive", screen = true) {
        ConfirmDialog(
            title = "Ta bort transaktionen?",
            body = "Köp av Räntefond Kort 12 aug, 10 000 kr. Det går inte att ångra.",
            confirmLabel = "Ta bort",
            onConfirm = {},
            onDismiss = {},
            destructive = true
        )
    }

    @Test
    fun `skärmdump av väntedialogen`() = composeRule.captureLightAndDark("LoadingDialog_import", screen = true, animated = true) {
        LoadingDialog(title = "Importerar", body = "Matchar fonder och hämtar kurser …")
    }
}
