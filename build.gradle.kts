import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
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
        "LocalFonderColors"
)
spotless {
    kotlin {
        target("core/src/**/*.kt", "app/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(ktlintRules)
            .customRuleSets(listOf(libs.compose.rules.ktlint.get().toString()))
    }
    kotlinGradle {
        target("*.gradle.kts", "core/*.gradle.kts", "app/*.gradle.kts")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRules)
    }
}

// Java-målet för alla moduler, på ett ställe (skill android:android-gradle-logic): källa och mål 17,
// JDK 21 kör bygget (ARKITEKTUR.md → Byggkonfiguration). Modulfilerna har bara det som är deras eget.
val javaVersion = JavaVersion.VERSION_17

subprojects {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
    }
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            compileOptions {
                sourceCompatibility = javaVersion
                targetCompatibility = javaVersion
            }
        }
    }
}

// Copy-paste-detektor (regel 4, skill shared-ui-components): PMD CPD för Kotlin via PMD:s CLI, som
// i Dagboken (Gradle-pluginen de.aaschmid.cpd fungerar inte med Gradle 9). Tröskeln 80 tokens är
// Dagbokens och sänks/höjs bara med motivering i skillen.
//
// Ingen baslinje: kopiorna som fanns när kontrollen infördes är sammanslagna (issue #175), och
// varje kopia fäller bygget.
val cpdMinimumTokens = 80
val cpdClasspath = configurations.create("cpdClasspath")
dependencies {
    add(cpdClasspath.name, libs.pmd.cli)
    add(cpdClasspath.name, libs.pmd.kotlin)
}
val cpdSources = fileTree(rootDir) {
    include("core/src/**/*.kt", "app/src/main/**/*.kt", "app/src/test/**/*.kt", "app/src/androidTest/**/*.kt")
}
val cpdCheck = tasks.register<JavaExec>("cpdCheck") {
    group = "verification"
    description = "Hittar kopierad Kotlin-kod (PMD CPD, minst $cpdMinimumTokens tokens)."
    val fileList = layout.buildDirectory.file("cpd/files.txt")
    val report = layout.buildDirectory.file("reports/cpd/cpd.csv")
    val root = rootDir
    val tokens = cpdMinimumTokens
    val sources: FileCollection = cpdSources
    val result = executionResult
    workingDir = root
    classpath = cpdClasspath
    mainClass.set("net.sourceforge.pmd.cli.PmdCli")
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(report)
    outputs.cacheIf { true }
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
                "${File(cols[4 + 2 * i]).relativeTo(root).invariantSeparatorsPath}:${cols[3 + 2 * i]}"
            }
            "${cols[0]} rader: " + places.joinToString(", ")
        }
        if (copies.isNotEmpty()) {
            throw GradleException(
                "cpdCheck: kopierad kod (regel 4, skill shared-ui-components). Bryt ut till en delad " +
                    "byggsten i stället för att höja tröskeln.\n" +
                    copies.joinToString("\n") { "  $it" }
            )
        }
    }
}

tasks.named("check") { dependsOn(cpdCheck) }
