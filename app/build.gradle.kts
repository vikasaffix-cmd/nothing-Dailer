import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing reads keystore.properties at the project root (git-ignored).
// Without it, release builds are left unsigned and debug builds are unaffected.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.example.allinonedailer"
    compileSdk = 35

    defaultConfig {
        // `-PlegacyPackage` builds the old debug package id, so a debug build can be
        // installed over the old app (keeping its data) just to run Backup & restore → Export.
        applicationId = if (project.hasProperty("legacyPackage")) "com.example.allinonedailer" else "com.maihuvikas.dialer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Project keep rules live in src/main/keepRules/rules.keep.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // The app is translated into English, Hindi and Marathi only; library
        // strings in ~90 other languages made up most of resources.arsc.
        localeFilters += listOf("en", "hi", "mr")
    }
    packaging {
        resources {
            // NumberInfo always asks libphonenumber for English place and
            // carrier names, so the other languages' tables are never read
            // (86_zh alone is 380 KB). The time-zone mapper isn't used at all.
            val unusedLanguages = listOf(
                "ar", "be", "bg", "bs", "de", "el", "es", "fa", "fi", "fr", "hr", "hu", "hy", "id", "it",
                "iw", "ja", "kk", "ko", "nl", "pl", "pt", "ro", "ru", "sq", "sr", "sv", "th", "tr", "uk",
                "vi", "zh", "zh_Hant"
            )
            for (table in listOf("geocoding", "carrier")) {
                for (language in unusedLanguages) excludes += "com/google/i18n/phonenumbers/$table/data/*_$language"
            }
            excludes += "com/google/i18n/phonenumbers/timezones/**"
            excludes += "DebugProbesKt.bin"
        }
    }
    lint {
        // Lint's Kotlin analysis crashes on some Compose files ("Unexpected
        // failure during lint analysis ... Unexpected owner function: null"),
        // which fails lintVitalAnalyzeRelease and blocks every release build.
        // Skip the automatic release lint; run `./gradlew :app:lint` by hand.
        checkReleaseBuilds = false
        disable += "LintError"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Coroutines & Networking
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)

    // Offline carrier / region lookup for unknown numbers
    implementation(libs.libphonenumber)
    implementation(libs.libphonenumber.geocoder)
    implementation(libs.libphonenumber.carrier)

    // Home-screen widget (widget/) and its background refreshes
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime.ktx)

    // Nothing Glyph Matrix SDK 2.0 (official AAR from github.com/Nothing-Developer-Programme/Glyph-Developer-Kit,
    // sdk/glyph-matrix-sdk-2.0.aar, July 2026). Licence: assets/licenses/GlyphMatrixSDK-EULA.md (no commercial use).
    implementation(files("libs/glyph-matrix-sdk-2.0.aar"))

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
