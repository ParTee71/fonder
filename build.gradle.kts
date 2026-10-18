plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.spotless)
}

// Kodstil (ktlint + compose-rules) bara lokalt, inte i CI (regel 5): ./gradlew spotlessApply
// före push rättar, spotlessCheck kontrollerar. Reglerna står här och inte i .editorconfig,
// eftersom Spotless inte läser en .editorconfig-sektion per filändelse pålitligt.
val ktlintRules = mapOf(
    "ktlint_code_style" to "android_studio",
    "max_line_length" to "off",
    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
    "compose_allowed_composition_locals" to
        "LocalDarkTheme",
    // Befintliga avvikelser som kräver API-ändringar i komponenter – rättas i egna PR:er
    // (skill android-projekt → Nuvarande läge). Ta bort raden när regeln är rättad.
    "ktlint_compose_parameter-naming" to "disabled",
    "ktlint_compose_lambda-param-event-trailing" to "disabled",
    "ktlint_compose_lambda-param-in-effect" to "disabled",
    "ktlint_compose_content-slot-reused" to "disabled",
    "ktlint_compose_multiple-emitters-check" to "disabled",
    "ktlint_compose_modifier-naming" to "disabled",
    "ktlint_compose_modifier-missing-check" to "disabled",
    "ktlint_compose_param-order-check" to "disabled",
    // Autorättningen flyttar bara den sista av två hiltViewModel() till en parameter och bryter koden.
    "ktlint_compose_vm-injection-check" to "disabled"
)
spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(ktlintRules)
            .customRuleSets(listOf(libs.compose.rules.ktlint.get().toString()))
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRules)
    }
}

// Copy-paste-detektor (regel 4, skill shared-ui-components): PMD CPD för Kotlin via PMD:s CLI, som
// i Dagboken (Gradle-pluginen de.aaschmid.cpd fungerar inte med Gradle 9). Tröskeln 80 tokens är
// Dagbokens och sänks/höjs bara med motivering i skillen.
//
// CPD saknar egen baslinje. Kopiorna som fanns när kontrollen infördes står i
// config/cpd-baseline.txt – en rad per uppsättning filer som delar en kopia. Bygget fälls av en
// kopia vars filuppsättning inte står där: en ny kopia, eller en känd kopia som sprids till en ny
// fil. Baslinjen ska bara krympa; skriv om den efter en sammanslagning med
// `./gradlew cpdCheck -PcpdWriteBaseline` (aldrig för att få in en ny kopia).
val cpdMinimumTokens = 80
val cpdClasspath = configurations.create("cpdClasspath")
dependencies {
    add(cpdClasspath.name, libs.pmd.cli)
    add(cpdClasspath.name, libs.pmd.kotlin)
}
val cpdSources = fileTree(rootDir) {
    include("app/src/main/**/*.kt", "app/src/test/**/*.kt", "app/src/androidTest/**/*.kt")
}
val cpdBaselineHeader = """
    # Kända kopior (PMD CPD, minst $cpdMinimumTokens tokens) – baslinjen för cpdCheck i build.gradle.kts.
    # En rad per uppsättning filer som delar en kopia. Ska bara krympa: slå ihop kopiorna
    # (skill shared-ui-components → Kända dubbletter) och skriv om med ./gradlew cpdCheck -PcpdWriteBaseline.
    # Lägg aldrig till en rad för hand.
""".trimIndent()

val cpdCheck = tasks.register<JavaExec>("cpdCheck") {
    group = "verification"
    description = "Hittar ny kopierad Kotlin-kod (PMD CPD, minst $cpdMinimumTokens tokens, mot config/cpd-baseline.txt)."
    val fileList = layout.buildDirectory.file("cpd/files.txt")
    val report = layout.buildDirectory.file("reports/cpd/cpd.csv")
    val root = rootDir
    val tokens = cpdMinimumTokens
    val sources: FileCollection = cpdSources
    val baseline = file("config/cpd-baseline.txt")
    val header = cpdBaselineHeader
    val writeBaseline = providers.gradleProperty("cpdWriteBaseline").isPresent
    val result = executionResult
    workingDir = root
    classpath = cpdClasspath
    mainClass.set("net.sourceforge.pmd.cli.PmdCli")
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(baseline).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("writeBaseline", writeBaseline)
    outputs.file(report)
    outputs.cacheIf { !writeBaseline }
    outputs.upToDateWhen { !writeBaseline }
    isIgnoreExitValue = true
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "cpd", "--language", "kotlin", "--minimum-tokens", "$tokens",
                "--file-list", fileList.get().asFile.path,
                "--format", "csv", "--report-file", report.get().asFile.path
            )
        }
    )
    doFirst {
        fileList.get().asFile.apply {
            parentFile.mkdirs()
            writeText(sources.files.map { it.relativeTo(root).path }.sorted().joinToString("\n"))
        }
        report.get().asFile.parentFile.mkdirs()
    }
    doLast {
        // PMD: 0 = inga kopior, 4 = kopior hittade, annat = verktygsfel.
        val exit = result.get().exitValue
        if (exit != 0 && exit != 4) throw GradleException("cpdCheck: PMD avslutades med kod $exit (se loggen ovan).")
        // CSV: lines,tokens,occurrences följt av (rad,fil) per förekomst.
        val rows = report.get().asFile.takeIf { exit == 4 && it.exists() }?.readLines().orEmpty().drop(1).filter { it.isNotBlank() }
        val copies = rows.map { row ->
            val cols = row.split(",")
            val places = (0 until cols[2].toInt()).map { i ->
                File(cols[4 + 2 * i]).relativeTo(root).invariantSeparatorsPath to cols[3 + 2 * i]
            }
            val key = places.map { it.first }.distinct().sorted().joinToString(" | ")
            key to "${cols[0]} rader: " + places.joinToString(", ") { "${it.first}:${it.second}" }
        }
        if (writeBaseline) {
            baseline.parentFile.mkdirs()
            baseline.writeText(header + "\n" + copies.map { it.first }.distinct().sorted().joinToString("\n", postfix = "\n"))
            logger.lifecycle("cpdCheck: ${baseline.relativeTo(root)} omskriven (${copies.map { it.first }.distinct().size} rader).")
            return@doLast
        }
        val known = baseline.takeIf { it.exists() }?.readLines().orEmpty().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
        val stale = known - copies.map { it.first }.toSet()
        if (stale.isNotEmpty()) {
            logger.warn(
                "cpdCheck: ${stale.size} rader i config/cpd-baseline.txt gäller kopior som inte finns längre – " +
                    "krymp baslinjen med ./gradlew cpdCheck -PcpdWriteBaseline."
            )
        }
        val fresh = copies.filter { it.first !in known }
        if (fresh.isNotEmpty()) {
            throw GradleException(
                "cpdCheck: ny kopierad kod (regel 4, skill shared-ui-components). Bryt ut till en delad " +
                    "byggsten i stället för att höja tröskeln eller utöka baslinjen.\n" +
                    fresh.joinToString("\n") { "  ${it.second}" }
            )
        }
    }
}

tasks.named("check") { dependsOn(cpdCheck) }
