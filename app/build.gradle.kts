import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

private val buildTimestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

private val versionNameOverride = findProperty("versionNameOverride") as String?

private val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "se.partee71.fonder"
    compileSdk = 36

    defaultConfig {
        applicationId = "se.partee71.fonder"
        minSdk = 30
        targetSdk = 35
        versionCode = 13
        versionName = versionNameOverride ?: "0.8.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseStorePassword = localProps.getProperty("signing.storePassword")
        ?: System.getenv("SIGNING_STORE_PASSWORD")
    val releaseKeyPassword = localProps.getProperty("signing.keyPassword")
        ?: System.getenv("SIGNING_KEY_PASSWORD")
    val hasSigningCredentials = releaseStorePassword != null && releaseKeyPassword != null

    signingConfigs {
        // Fast, incheckad debug-keystore (se .gitignore) — annars auto-genererar AGP en ny
        // ~/.android/debug.keystore per körning på GitHub Actions ephemeral runners, vilket
        // gör att varje CI-byggd debug-APK signeras med olika nyckel och inte går att
        // installera som uppgradering av föregående. Lösenord/alias är Android-standard.
        getByName("debug") {
            storeFile     = file("debug.keystore")
            storePassword = "android"
            keyAlias      = "androiddebugkey"
            keyPassword   = "android"
        }
        if (hasSigningCredentials) {
            create("release") {
                storeFile     = file("fonder.jks")
                storePassword = releaseStorePassword
                keyAlias      = localProps.getProperty("signing.keyAlias")
                    ?: System.getenv("SIGNING_KEY_ALIAS")
                    ?: "fonder"
                keyPassword   = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasSigningCredentials) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName =
                    "fonder-${variant.versionName}-${buildTimestamp}.apk"
            }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.DSA"
            excludes += "META-INF/*.RSA"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.compose.ui.text.googlefonts)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Image loading
    implementation(libs.coil.compose)

    // WorkManager + Hilt (backup/kursuppdatering)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Nätverk + HTML-parsning för FundPriceRepository (se issue #2/#3)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    // Diagram (kurshistorik i Fonddetalj, se issue #7)
    implementation(libs.vico.compose.m3)

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)

    // Instrumented tests
    androidTestImplementation(libs.androidx.junit.ext)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.turbine)
}
