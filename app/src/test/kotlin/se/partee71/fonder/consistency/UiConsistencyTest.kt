package se.partee71.fonder.consistency

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regel 4 i bygget (skill shared-ui-components). Mönstren står bara i `ui-forbidden.txt`,
 * undantagen bara i `ui-allowlist.txt` – samma filer som hooken `regel4-check` läser.
 * Baslinjen i allowlisten genereras av `tools/regel4-baseline.mjs`.
 */
class UiConsistencyTest {

    private val repoRoot = File("").absoluteFile.let { if (it.name == "app") it.parentFile else it }
    private val patterns = Regel4.parsePatterns(Regel4.readResource("ui-forbidden.txt"))

    // Lat, så att en trasig rad ger ett tydligt fel i testet i stället för i klassens konstruktor.
    private val allowlist by lazy { Regel4.parseAllowlist(Regel4.readResource("ui-allowlist.txt")) }
    private val examples = Regel4.parseSections(Regel4.readResource("ui-forbidden-examples.txt"))
    private val production = Konsist.scopeFromProduction(moduleName = "app", sourceSetName = "main")

    private val featureFile = "app/src/main/kotlin/se/partee71/fonder/ui/hem/HemRow.kt"
    private val componentFile = "app/src/main/kotlin/se/partee71/fonder/ui/components/X.kt"
    private val diagramFile = "app/src/main/kotlin/se/partee71/fonder/ui/diagram/X.kt"

    private fun relativePath(path: String) = File(path).relativeTo(repoRoot).invariantSeparatorsPath

    @Test
    fun `mönsterfilen har alla scope och en ersättning per mönster`() {
        assertEquals(setOf("feature", "component-only", "vico", "dispatchers", "events"), patterns.keys)
        patterns.values.flatten().forEach { assertTrue(it.regex.pattern, it.replacement.isNotBlank()) }
    }

    @Test
    fun `Konsist hittar appens källfiler`() {
        // Kontrollprov: utan det skulle en tom skanning tyst ge grönt.
        val files = production.files.map { relativePath(it.path) }
        assertTrue(
            "Konsist hittade inte HemScreen.kt – fel modul eller arbetskatalog? ${files.take(5)}",
            "app/src/main/kotlin/se/partee71/fonder/ui/hem/HemScreen.kt" in files
        )
    }

    @Test
    fun `ingen kod i app-src-main använder förbjudna mönster`() {
        val hits = production.files.flatMap { file ->
            val rel = relativePath(file.path)
            Regel4.findViolations(rel, file.text, patterns, allowlist).map { hit ->
                "  $rel:${hit.line}  `${hit.match.trim()}`  → använd ${hit.replacement}"
            }
        }
        if (hits.isNotEmpty()) {
            fail(
                "Regel 4: förbjudna mönster (skill shared-ui-components).\n" + hits.joinToString("\n") +
                    "\nUndantag bara via ui-allowlist.txt med motivering och användarens ok."
            )
        }
    }

    @Test
    fun `UiState-typer är @Immutable eller @Stable`() {
        // En lista, karta eller mängd i tillståndet är ImmutableList/ImmutableMap/ImmutableSet; annoteringen gör hela typen stabil (skill compose-expert).
        val states = production.classes().filter { it.hasDataModifier && !it.hasPrivateModifier && it.name.endsWith("State") && it.resideInPackage("..ui..") } +
            production.interfaces().withNameEndingWith("UiState").filter { it.resideInPackage("..ui..") }
        assertTrue("hittade inga UiState-typer i ui/", states.isNotEmpty())
        states.forEach { state ->
            assertTrue("${state.name} ska vara @Immutable eller @Stable", state.hasAnnotationWithName("Immutable", "Stable"))
        }
    }

    // De delade exemplen körs med tom allowlist utom sektionen "allowlist" – precis som i hooktestet.

    @Test
    fun `delade exempel - tillåtna och förbjudna rader tolkas likadant som i hooken`() {
        examples.getValue("ok").forEach { line ->
            assertEquals(line, emptyList<Regel4.Hit>(), Regel4.findViolations(featureFile, line, patterns, emptyList()))
        }
        examples.getValue("stopp").forEach { line ->
            assertTrue(line, Regel4.findViolations(featureFile, line, patterns, emptyList()).isNotEmpty())
        }
        examples.getValue("komponent-ok").forEach { line ->
            assertEquals(line, emptyList<Regel4.Hit>(), Regel4.findViolations(componentFile, line, patterns, emptyList()))
        }
        examples.getValue("komponent-stopp").forEach { line ->
            assertTrue(line, Regel4.findViolations(diagramFile, line, patterns, emptyList()).isNotEmpty())
        }
        examples.getValue("diagram-ok").forEach { line ->
            assertEquals(line, emptyList<Regel4.Hit>(), Regel4.findViolations(diagramFile, line, patterns, emptyList()))
        }
        examples.getValue("diagram-stopp").forEach { line ->
            assertTrue(line, Regel4.findViolations(componentFile, line, patterns, emptyList()).isNotEmpty())
        }
    }

    @Test
    fun `delade exempel - scope per sökväg`() {
        examples.getValue("scope").forEach { line ->
            val parts = line.split("\t")
            val expected = parts.getOrElse(1) { "" }.split(",").filter { it.isNotEmpty() }
            assertEquals(parts[0], expected, Regel4.scopesFor(parts[0]))
        }
    }

    @Test
    fun `delade exempel - ett undantag gäller bara sin fil och sin symbol`() {
        examples.getValue("allowlist").forEach { example ->
            val (allowLine, code, expected) = example.split("\t")
            val hits = Regel4.findViolations(featureFile, code, patterns, Regel4.parseAllowlist(allowLine))
            assertEquals(example, expected, if (hits.isEmpty()) "ok" else "stopp")
        }
    }

    @Test
    fun `delade exempel - allowlist-rad utan symbol eller motivering avvisas`() {
        examples.getValue("allowlist-fel").forEach { line ->
            val error = runCatching { Regel4.parseAllowlist(line) }.exceptionOrNull()
            assertTrue(line, error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty(), error?.message.orEmpty().startsWith("ui-allowlist.txt rad 1"))
        }
    }
}
