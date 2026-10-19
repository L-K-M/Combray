plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ABIs with a staged native engine (scripts/build-native.sh). The APK must not offer an ABI the
// engine was not built for: Android would pick it and find no engine.
val engineAbis = rootProject.file("engine/jniLibs").listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()

android {
    namespace = "ch.lkmc.combray"
    compileSdk = 37

    defaultConfig {
        // Proposed ID (AGENTS.md "Identity"); confirm before signing or before real data relies on it.
        applicationId = "ch.lkmc.combray"
        // The native engine links with API 30 linker wrappers (docs/BUILDING.md).
        minSdk = 30
        targetSdk = 36
        // Plain literals: scripts/release.sh and the CI tag gate rewrite them with sed.
        versionCode = 1
        versionName = "0.1.0"

        if (engineAbis.isNotEmpty()) {
            ndk { abiFilters += engineAbis }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            // R8 needs keep rules for the JNI bridge first (M4).
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // scripts/build-native.sh already strips the engine; keep the symbols JNI needs.
            keepDebugSymbols += "**/*.so"
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)

    // Device checks against the real engine (scripts/check-app.sh).
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
