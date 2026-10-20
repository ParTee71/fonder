package se.partee71.fonder.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Komponentgalleriet (debug-skärmen, skill shared-ui-components): topprad med bakåtpil, alla avsnitt,
 * dialogerna öppnas och stängs, och diagrammet ritas först när sektionen fälls ut. Skärmdumpen ljust +
 * mörkt tas med diagrammet hopfällt (Vico ritar på en annan tråd) och klockan stegad för indikatorerna.
 */
@RunWith(RobolectricTestRunner::class)
class ComponentGalleryTest {

    @get:Rule
    val rule = createComposeRule()

    private var backs = 0

    private fun setGallery() = rule.setContent { FonderTheme { ComponentGallery(onBack = { backs++ }) } }

    /** Galleriets lodräta rullning – knappgrupperna har egna vågräta. */
    private fun scrollTo(text: String) = rule.onNode(verticalScroll).performScrollToNode(hasText(text))

    private val verticalScroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    @Test
    fun `topprad med bakåtpil och alla avsnitt`() {
        setGallery()

        rule.onNodeWithText("Komponenter").assertIsDisplayed()
        // Exemplen längre ned har egna bakåtpilar – galleriets är den i dess topprad.
        rule.onNode(hasContentDescription("Tillbaka") and hasAnyAncestor(hasTestTag(COMPONENT_GALLERY_TOP_BAR_TAG))).performClick()
        assertEquals(1, backs)
        listOf(
            "Knappar",
            "Kort, rader och märken",
            "Fält och val",
            "Belopp och andelar",
            "Status, laddning och tomt",
            "Navigering",
            "Dialoger",
            "Från android-ui",
            "Diagram"
        ).forEach { section ->
            scrollTo(section)
            rule.onNodeWithText(section).assertIsDisplayed()
        }
    }

    @Test
    fun `bekräftelsedialogen öppnas och stängs`() {
        setGallery()
        scrollTo("Confirm")

        rule.onNodeWithText("Confirm").performClick()
        rule.onNodeWithText("Ta bort transaktionen?").assertIsDisplayed()
        rule.onNodeWithText("Avbryt").performClick()

        rule.onNodeWithText("Ta bort transaktionen?").assertDoesNotExist()
    }

    @Test
    fun `diagrammet ritas först när sektionen fälls ut`() {
        setGallery()
        rule.onNode(hasContentDescription("Kursdiagram", substring = true)).assertDoesNotExist()

        scrollTo("Diagram")
        rule.onNodeWithText("Diagram").performClick()

        scrollTo("FundLineChart")
        rule.onAllNodes(hasContentDescription("Kursdiagram", substring = true)).onFirst().assertExists()
    }

    @Test
    @Config(qualifiers = "w390dp-h2400dp-xxhdpi")
    fun `skärmdump av galleriet`() = rule.captureLightAndDark("ComponentGallery_default", animated = true) {
        ComponentGallery(onBack = {})
    }
}
