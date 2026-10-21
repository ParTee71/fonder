package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.theme.Error
import se.partee71.fonder.ui.theme.Filled
import se.partee71.fonder.ui.theme.FonderLightColorScheme
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.fonder.ui.theme.flushCardStyle
import se.partee71.ui.components.AppCard
import se.partee71.ui.components.AppCardStyle
import se.partee71.ui.components.CardSegment
import se.partee71.ui.components.LocalAppCardStyle
import se.partee71.ui.theme.Tone

/**
 * android-ui:s [AppCard] under [FonderTheme] (UI-13) i JVM: temat ger korten Fonders kontur och luft, ett
 * kort med `onClick` är **en** klickbar nod där rubrik och belopp läses i ett svep, ett kort utan har ingen
 * egen åtgärd – och korten, de tonade och bitarna fotograferas i ljust och mörkt.
 */
@RunWith(RobolectricTestRunner::class)
class AppCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `FonderTheme ger korten 1 dp kontur i outlineVariant, 16 dp luft och inget extra mellanrum`() {
        var style: AppCardStyle? = null
        var flush: AppCardStyle? = null
        composeRule.setContent {
            FonderTheme(darkTheme = false) {
                style = LocalAppCardStyle.current
                flush = flushCardStyle
            }
        }

        assertEquals(1.dp, style?.border?.width)
        assertEquals(16.dp, style?.padding)
        assertEquals(0.dp, style?.contentSpacing)
        // Ett korts bitar (CardSegment) har samma 16 dp luft som ett helt kort, så att raderna inte trycks ihop.
        assertEquals(16.dp, style?.segmentGap)
        assertEquals(0.dp, flush?.padding)
        assertEquals(0.dp, flush?.segmentGap)
        assertEquals(style?.border, flush?.border)
        // Konturens färg: outlineVariant, så att korten syns mot bakgrunden i ljust tema.
        assertEquals(androidx.compose.ui.graphics.SolidColor(FonderLightColorScheme.outlineVariant), style?.border?.brush)
    }

    @Test
    fun `ett klickbart kort är en nod och ett tryck på innehållet anropar onClick`() {
        var clicks = 0
        composeRule.setContent {
            FonderTheme {
                AppCard(onClick = { clicks++ }) {
                    Text("Global Indexfond A")
                    Text("214 300 kr")
                }
            }
        }

        composeRule.onNodeWithText("Global Indexfond A", substring = true).assertHasClickAction()
        composeRule.onNodeWithText("214 300 kr", substring = true).performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `ett kort utan onClick har ingen egen åtgärd`() {
        composeRule.setContent {
            FonderTheme { AppCard { Text("Portföljens värde") } }
        }

        composeRule.onNodeWithText("Portföljens värde")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `skärmdump av varianterna`() = composeRule.captureLightAndDark("AppCard_variants") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text("Portföljens värde", style = MaterialTheme.typography.bodySmall)
                Text("412 860 kr", style = MaterialTheme.typography.headlineMedium)
            }
            AppCard(modifier = Modifier.fillMaxWidth(), tone = Tone.Filled, onClick = {}) {
                Text("Tillväxtmarknad Bred", style = MaterialTheme.typography.titleSmall)
                Text("Avgift 372 kr per år", style = MaterialTheme.typography.bodySmall)
            }
            AppCard(modifier = Modifier.fillMaxWidth(), tone = Tone.Error, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                Text("Farozon", style = MaterialTheme.typography.titleSmall)
                Text("Det här går inte att ångra.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Test
    fun `bitar av ett kort delar kontur – tre bitar blir två konturbredder kortare`() {
        composeRule.setContent {
            FonderTheme {
                Column(modifier = Modifier.testTag("grupp")) {
                    repeat(3) { index ->
                        CardSegment(top = index == 0, bottom = index == 2, modifier = Modifier.height(50.dp)) { Text("Rad $index") }
                    }
                }
            }
        }

        // Andra och tredje biten dras upp 1 dp var, så att konturerna sammanfaller.
        composeRule.onNodeWithTag("grupp").assertHeightIsEqualTo(148.dp)
    }

    @Test
    fun `skärmdump av ett kort av bitar`() = composeRule.captureLightAndDark("AppCard_grouped") {
        Column {
            listOf("Sverige Småbolag", "Global Indexfond A", "Räntefond Kort").forEachIndexed { index, name ->
                CardSegment(top = index == 0, bottom = index == 2) { Text(name, style = MaterialTheme.typography.titleSmall) }
            }
        }
    }
}
