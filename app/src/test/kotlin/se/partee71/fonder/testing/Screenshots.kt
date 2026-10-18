package se.partee71.fonder.testing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.ThresholdValidator
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import se.partee71.fonder.ui.theme.FonderTheme

private const val SCREENSHOT_TAG = "screenshot_root"
private const val ANIMATION_STEP_MS = 400L
private const val READY_TIMEOUT_MS = 10_000L

/**
 * Andel pixlar som får skilja innan `verifyRoborazziDebug` fäller: 0,1 %. Robolectrics
 * NATIVE-rendering är bitidentisk mellan körningar på samma maskin (verifierat två gånger i rad),
 * och färgnyanser under Roborazzis avståndsgräns räknas redan inte som skillnad – marginalen är
 * till för enstaka kantutjämnade pixlar om CI-maskinens rendering skiljer sig något. En riktig
 * ändring (färg, form, avstånd, rad som flyttar) berör långt fler pixlar: i den minsta bilden
 * (1170 × 131) tolereras ~150 pixlar, i den största helskärmsbilden (1170 × 4200) ~4 900. Ett
 * enstaka ändrat tecken i en helskärmsbild (uppmätt ~250 pixlar) passerar alltså – texter
 * fångas av Compose-testernas textkontroller, skärmdumparna vaktar utseendet.
 */
private const val CHANGE_THRESHOLD = 0.001f
private val screenshotOptions = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(resultValidator = ThresholdValidator(CHANGE_THRESHOLD))
)

/**
 * Tar skärmdumpar i ljust och mörkt tema: `<name>_light.png` och `<name>_dark.png` i Roborazzis
 * outputDir (`app/src/test/screenshots`, skill testing-strategy). [name] följer `<Komponent>_<variant>`.
 *
 * Renderar i **testets egen** [ComposeContentTestRule] och fotograferar en nod i den. Den tidigare
 * varianten (`captureRoboImage(path) { … }`) startade en andra aktivitet bredvid compose-regelns;
 * Roborazzi såg då två fönster, föll tillbaka på att fotografera hela skärmen och fick bara med
 * regelns tomma fönster – bilden blev en tom bakgrund. Temat växlas här med ett state i samma
 * komposition, eftersom `setContent` bara får anropas en gång per test.
 *
 * Innehållet läggs på temats `background` med 16 dp marginal, så att mörkt läge syns som mörkt
 * och komponenten inte klistras mot bildkanten.
 *
 * [animated] för innehåll med en oändlig animation: compose-klockan stegas fram en fast tid i
 * stället för att vänta på vila, så bilden blir densamma varje gång.
 *
 * [ready] för innehåll som ritas klart utanför compose-klockan (diagrammets modell byggs på en
 * annan tråd): bilden tas först när villkoret är sant, så den aldrig fångar ett halvfärdigt läge.
 *
 * [screen] fotograferar hela skärmen med alla fönster i stället för noden – för det som ritas i
 * ett eget fönster (dialoger, menyer), som inte finns i nodens bild.
 *
 * Gör ingenting i en vanlig `testDebugUnitTest` – bilderna skrivs bara av `recordRoborazziDebug`
 * och jämförs av `verifyRoborazziDebug`, som CI-jobbet `build-and-test` kör (skill ci-budget),
 * med toleransen [CHANGE_THRESHOLD].
 */
@OptIn(ExperimentalRoborazziApi::class) // captureScreenRoboImage med RoborazziOptions
fun ComposeContentTestRule.captureLightAndDark(
    name: String,
    screen: Boolean = false,
    animated: Boolean = false,
    ready: (ComposeContentTestRule.() -> Boolean)? = null,
    content: @Composable () -> Unit
) {
    var dark by mutableStateOf(false)
    // En oändlig animation (laddningsindikatorn) blir aldrig "idle": klockan stegas då för hand.
    if (animated) mainClock.autoAdvance = false
    setContent {
        FonderTheme(darkTheme = dark) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                Box(
                    modifier = Modifier
                        .testTag(SCREENSHOT_TAG)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp)
                ) {
                    content()
                }
            }
        }
    }
    for (suffix in listOf("light", "dark")) {
        dark = suffix == "dark"
        if (animated) mainClock.advanceTimeBy(ANIMATION_STEP_MS) else waitForIdle()
        if (ready != null) waitUntil(READY_TIMEOUT_MS) { ready() }
        val file = "${name}_$suffix.png"
        if (screen) {
            captureScreenRoboImage(file, screenshotOptions)
        } else {
            onNodeWithTag(SCREENSHOT_TAG).captureRoboImage(file, screenshotOptions)
        }
    }
}

/**
 * Sant när den första noden som [matcher] pekar ut innehåller annat än en enda färg — för
 * [captureLightAndDark]s `ready`. Vico bygger diagrammets modell på en annan tråd än
 * compose-klockan; utan väntan fångade bilden ibland bara periodknapparna. Standard är första
 * noden med en `contentDescription`, vilket i ett ensamt diagram är diagrammet självt.
 */
fun ComposeContentTestRule.isDrawn(
    matcher: SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)
): Boolean {
    val pixels = onAllNodes(matcher).onFirst().captureToImage().toPixelMap()
    val first = pixels[0, 0]
    return (0 until pixels.height step 4).any { y -> (0 until pixels.width step 4).any { x -> pixels[x, y] != first } }
}
