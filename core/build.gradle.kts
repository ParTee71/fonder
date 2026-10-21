// Domänen (domain/model och de rena use casen i domain/usecase) som ren Kotlin/JVM – inga Android-,
// Room- eller Hilt-beroenden, så att testerna körs utan Android SDK (ARKITEKTUR.md → Moduler och
// paket, #171). Java-målet sätts en gång för alla moduler i rotens build.gradle.kts.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// TaxCalcTest jämför ISK_PARAMETERS med tools/fondmatte och skill fondmatematik (issue #199): de
// två filerna är testets indata, så att build cache inte hoppar över testet när bara de ändrats.
tasks.test {
    val taxParameterSources = listOf(
        rootProject.file("tools/fondmatte/fondmatte.py"),
        rootProject.file(".claude/skills/fondmatematik/SKILL.md")
    )
    inputs.files(taxParameterSources).withPropertyName("taxParameterSources").withPathSensitivity(PathSensitivity.NONE)
}
